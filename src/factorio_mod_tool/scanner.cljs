(ns factorio-mod-tool.scanner
  "Periodic directory scanner. Walks the project directory once per second,
   collects file metadata (path, type, mtime, size), and updates :project
   state when the tree changes. Broadcasts diffs to GUI via WebSocket."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.state :as state]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private scan-interval-ms 1000)

(def ^:private ignored-dirs
  "Directories to skip during recursive scan."
  #{".git" "node_modules" ".shadow-cljs" "out" "target" ".cpcache"})

;; ---------------------------------------------------------------------------
;; Scanner internals
;; ---------------------------------------------------------------------------

(defonce ^:private scanner-timer (atom nil))
(defonce ^:private previous-scan (atom nil))
(defonce ^:private previous-config-mtime (atom nil))

;; Late-bound queue submit to avoid circular dependency
;; (scanner -> queue -> commands -> scanner)
(defonce ^:private queue-submit-fn (atom nil))

(defn set-queue-submit!
  "Inject queue/submit! at startup to avoid circular dependency."
  [f]
  (reset! queue-submit-fn f))

(defn- scan-dir
  "Recursively scan a directory. Returns a promise of a vector of entry maps:
   {:path <relative> :type :file/:dir :mtime <iso-string> :size <bytes>}
   Directories appear before their children."
  [base-path dir-path]
  (-> (p/let [entries (.readdir (.-promises (js/require "fs")) dir-path
                                #js {:withFileTypes true})]
        (p/let [results
                (p/all
                 (map (fn [entry]
                        (let [name (.-name entry)
                              full (fs/join dir-path name)
                              rel  (fs/relative base-path full)]
                          (cond
                            ;; Skip ignored directories
                            (and (.isDirectory entry) (ignored-dirs name))
                            (p/resolved [])

                            (.isDirectory entry)
                            (-> (p/let [^js st (fs/stat full)
                                        children (scan-dir base-path full)]
                                  (into [{:path rel
                                          :type :dir
                                          :mtime (.toISOString (.-mtime st))
                                          :size 0}]
                                        children))
                                (p/catch (fn [_] [])))

                            :else
                            (-> (p/let [^js st (fs/stat full)]
                                  [{:path rel
                                    :type :file
                                    :mtime (.toISOString (.-mtime st))
                                    :size (.-size st)}])
                                (p/catch (fn [_] []))))))
                      entries))]
          (vec (apply concat results))))
      (p/catch (fn [_] []))))

(defn- entries->nested-tree
  "Convert flat scan entries into a nested tree structure.
   Each dir node: {:path, :type :dir, :mtime, :size, :children [...]}.
   Each file node: {:path, :type :file, :mtime, :size}."
  [entries]
  (let [children-of (fn [parent-path]
                      (->> entries
                           (filter (fn [{:keys [path]}]
                                     (if (= parent-path "")
                                       (not (.includes path "/"))
                                       (and (.startsWith path (str parent-path "/"))
                                            (= (count (.split path "/"))
                                               (inc (count (.split parent-path "/"))))))))
                           (sort-by :path)))]
    (letfn [(build-node [{:keys [path type mtime size] :as entry}]
              (if (= type :dir)
                {:path path :type :dir :mtime mtime :size size
                 :children (mapv build-node (children-of path))}
                entry))]
      (mapv build-node (children-of "")))))

(defn- scan-fingerprint
  "Create a comparable fingerprint from scan results (ignoring children nesting).
   Uses path+mtime+size to detect changes."
  [entries]
  (into (sorted-set)
        (map (fn [{:keys [path type mtime size]}]
               [path type mtime size]))
        entries))

(defn- check-config-change!
  "Check if .fmod.json has changed and trigger a reload if so."
  [project-path]
  (let [config-file (fs/join project-path ".fmod.json")]
    (-> (p/let [^js st (fs/stat config-file)
                new-mtime (.toISOString (.-mtime st))
                old-mtime @previous-config-mtime]
          (when (and old-mtime (not= new-mtime old-mtime))
            (reset! previous-config-mtime new-mtime)
            (js/process.stderr.write "Config .fmod.json changed, reloading\n")
            (if-let [submit! @queue-submit-fn]
              (submit! "reload-config" {:path project-path})
              nil))
          (when-not old-mtime
            (reset! previous-config-mtime new-mtime)))
        (p/catch (fn [_] nil)))))

(defn- do-scan!
  "Perform one scan cycle. Compares with previous result, submits changes
   through the command queue for unified state management and WS broadcast.
   Also checks .fmod.json for changes and triggers config reload.
   Records telemetry for the :scanner thread."
  []
  (when-let [project-path (state/current-project-path)]
    (let [start-ms (.now js/Date)]
      (-> (p/let [entries (scan-dir project-path project-path)
                  new-fp  (scan-fingerprint entries)
                  old-fp  @previous-scan
                  _       (check-config-change! project-path)]
            (when (not= new-fp old-fp)
              (reset! previous-scan new-fp)
              (let [tree (entries->nested-tree entries)]
                (if-let [submit! @queue-submit-fn]
                  (submit! "update-file-tree" {:tree tree})
                  (swap! state/app-state assoc-in [:project :file-tree] tree))))
            (state/record-thread-run! :scanner (- (.now js/Date) start-ms)))
          (p/catch (fn [err]
                     (js/process.stderr.write
                      (str "Scanner error: " (ex-message err) "\n"))))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn stop-scanner!
  "Stop the periodic directory scanner."
  []
  (when-let [timer @scanner-timer]
    (js/clearInterval timer)
    (reset! scanner-timer nil)))

(defn start-scanner!
  "Start the periodic directory scanner. Safe to call multiple times."
  []
  (stop-scanner!)
  (js/process.stderr.write "Starting directory scanner\n")
  ;; Run first scan immediately
  (do-scan!)
  ;; Then schedule periodic scans
  (reset! scanner-timer (js/setInterval do-scan! scan-interval-ms)))

(defn list-files
  "Return the current scanned file tree from state.
   Returns a promise of {:path, :file-tree, :has-project}."
  []
  (p/resolved
   (let [project-path (state/current-project-path)
         file-tree    (get-in @state/app-state [:project :file-tree])]
     {:path      project-path
      :file-tree (or file-tree [])
      :has-project (some? project-path)})))
