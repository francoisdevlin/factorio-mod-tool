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

(defn- do-scan!
  "Perform one scan cycle. Compares with previous result, updates state and
   broadcasts if changed."
  []
  (when-let [project-path (state/current-project-path)]
    (-> (p/let [entries (scan-dir project-path project-path)
                new-fp  (scan-fingerprint entries)
                old-fp  @previous-scan]
          (when (not= new-fp old-fp)
            (reset! previous-scan new-fp)
            (let [tree (entries->nested-tree entries)]
              (swap! state/app-state assoc-in [:project :file-tree] tree))))
        (p/catch (fn [err]
                   (js/process.stderr.write
                    (str "Scanner error: " (ex-message err) "\n")))))))

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
