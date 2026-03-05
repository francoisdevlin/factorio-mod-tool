(ns factorio-mod-tool.state
  "Centralized application state for the Factorio mod tool.
   Single app-state atom with four top-level keys: :project, :connection, :preferences, :telemetry.
   State changes are validated against specs (dev mode) and broadcast to WS clients."
  (:require [promesa.core :as p]
            [factorio-mod-tool.spec :as spec]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.util.config :as config]))

;; ---------------------------------------------------------------------------
;; Preferences persistence
;; ---------------------------------------------------------------------------

(def ^:private prefs-filename ".fmod-preferences.json")

(defn- prefs-path []
  (let [home (or (.. js/process -env -HOME)
                 (.. js/process -env -USERPROFILE)
                 ".")]
    (fs/join home prefs-filename)))

(defn load-preferences!
  "Load preferences from disk. Returns a promise of the preferences map."
  []
  (-> (p/let [content (fs/read-file (prefs-path))
              parsed (-> content js/JSON.parse (js->clj :keywordize-keys true))]
        parsed)
      (p/catch (fn [_] {:theme "factorio"}))))

(defn- save-preferences!
  "Persist preferences to disk. Returns a promise."
  [prefs]
  (-> (fs/write-file (prefs-path) (js/JSON.stringify (clj->js prefs) nil 2))
      (p/catch (fn [err]
                 (js/process.stderr.write
                  (str "Warning: failed to save preferences: " (ex-message err) "\n"))))))

;; ---------------------------------------------------------------------------
;; Unified app-state atom
;; ---------------------------------------------------------------------------

(defonce app-state
  (atom {:project     {:current-path nil
                       :mods         {}}
         :connection  {:instances       {}
                       :active-instance nil}
         :preferences {:theme "factorio"}
         :telemetry   {:threads {}}}))

;; ---------------------------------------------------------------------------
;; WebSocket state (transport-level, not part of app-state spec)
;; ---------------------------------------------------------------------------

(defonce ws-clients (atom #{}))
(defonce ws-subscribers (atom #{}))

;; Server metadata (set at startup)
(defonce server-port (atom nil))
(defonce server-started-at (atom nil))

(defn broadcast!
  "Send a message to all subscribed WebSocket clients."
  [msg]
  (let [data (js/JSON.stringify (clj->js msg))]
    (doseq [client @ws-subscribers]
      (when (= (.-readyState client) (.-OPEN client))
        (.send client data)))))

;; ---------------------------------------------------------------------------
;; Dev-mode spec validation via add-watch
;; ---------------------------------------------------------------------------

(defonce ^:private validation-watch-key ::spec-validation)

(defn enable-validation!
  "Enable spec validation on every state transition (dev mode)."
  []
  (add-watch app-state validation-watch-key
    (fn [_key _ref _old new-state]
      (when-not (spec/valid? new-state)
        (js/console.warn "App state spec violation:" (spec/explain-str new-state))))))

(defn disable-validation!
  "Disable spec validation."
  []
  (remove-watch app-state validation-watch-key))

;; Enable by default in dev mode (goog.DEBUG is true in dev builds)
(when ^boolean js/goog.DEBUG
  (enable-validation!))

;; ---------------------------------------------------------------------------
;; State change broadcast via add-watch
;; ---------------------------------------------------------------------------

(defonce ^:private broadcast-watch-key ::state-broadcast)

(add-watch app-state broadcast-watch-key
  (fn [_key _ref old-state new-state]
    (when (not= (:project old-state) (:project new-state))
      (broadcast! {:type "state-change" :key "project"
                   :data (select-keys (:project new-state)
                                      [:current-path :config :file-tree])}))
    (when (not= (:connection old-state) (:connection new-state))
      (broadcast! {:type "state-change" :key "connection"
                   :data (update (:connection new-state) :instances
                                 (fn [instances]
                                   (into {}
                                         (map (fn [[k v]] [k (dissoc v :conn :heartbeat-timer)]))
                                         instances)))}))
    (when (not= (:preferences old-state) (:preferences new-state))
      (broadcast! {:type "state-change" :key "preferences"
                   :data (:preferences new-state)})
      (save-preferences! (:preferences new-state)))
    (when (not= (:telemetry old-state) (:telemetry new-state))
      (broadcast! {:type "state-change" :key "telemetry"
                   :data (:telemetry new-state)}))))

;; ---------------------------------------------------------------------------
;; Project accessors
;; ---------------------------------------------------------------------------

(defn get-mod
  "Returns the state for a given mod path, or nil."
  [path]
  (get-in @app-state [:project :mods path]))

(defn update-mod!
  "Updates the state for a given mod path."
  [path data]
  (swap! app-state assoc-in [:project :mods path] data))

(defn remove-mod!
  "Removes a mod from tracked state."
  [path]
  (swap! app-state update-in [:project :mods] dissoc path))

;; ---------------------------------------------------------------------------
;; Connection (RCON) accessors
;; ---------------------------------------------------------------------------

(defn get-rcon
  "Returns the RCON connection for a given instance name, or nil."
  [instance-name]
  (get-in @app-state [:connection :instances instance-name]))

(defn set-rcon!
  "Stores an RCON connection for a given instance name."
  [instance-name conn-data]
  (swap! app-state assoc-in [:connection :instances instance-name] conn-data))

(defn touch-rcon-query!
  "Record the current time as the last RCON query time for an instance."
  [instance-name]
  (swap! app-state update-in [:connection :instances instance-name]
         assoc :last-query-at (.toISOString (js/Date.))))

(defn remove-rcon!
  "Removes an RCON connection from tracked state."
  [instance-name]
  (when-let [timer (:heartbeat-timer (get-in @app-state [:connection :instances instance-name]))]
    (js/clearInterval timer))
  (swap! app-state update-in [:connection :instances] dissoc instance-name))

(defn update-rcon-health!
  "Update health status for an RCON connection after a heartbeat."
  [instance-name health-status]
  (swap! app-state update-in [:connection :instances instance-name]
         (fn [conn]
           (if (= health-status :alive)
             (assoc conn
                    :health :alive
                    :last-heartbeat-at (.toISOString (js/Date.))
                    :heartbeat-failures 0)
             (let [failures (inc (or (:heartbeat-failures conn) 0))]
               (assoc conn
                      :health (if (>= failures 3) :unreachable health-status)
                      :heartbeat-failures failures))))))

;; ---------------------------------------------------------------------------
;; Preferences accessors
;; ---------------------------------------------------------------------------

(defn get-preferences
  "Returns the current preferences map."
  []
  (:preferences @app-state))

(defn set-preference!
  "Set a single preference key-value pair."
  [k v]
  (swap! app-state assoc-in [:preferences k] v))

(defn update-preferences!
  "Merge new preference values into preferences."
  [prefs]
  (swap! app-state update :preferences merge prefs))

;; ---------------------------------------------------------------------------
;; Telemetry accessors
;; ---------------------------------------------------------------------------

(defn record-thread-run!
  "Record a completed run for a background thread.
   Updates last-run-at, increments run-count, and recalculates avg-ms."
  [thread-key elapsed-ms]
  (swap! app-state update-in [:telemetry :threads thread-key]
         (fn [entry]
           (let [prev-count (or (:run-count entry) 0)
                 prev-avg   (or (:avg-ms entry) 0)
                 new-count  (inc prev-count)
                 new-avg    (/ (+ (* prev-avg prev-count) elapsed-ms) new-count)]
             {:last-run-at (.toISOString (js/Date.))
              :run-count   new-count
              :avg-ms      new-avg}))))

(defn get-thread-telemetry
  "Returns telemetry data for a specific thread, or all threads if no key given."
  ([] (get-in @app-state [:telemetry :threads]))
  ([thread-key] (get-in @app-state [:telemetry :threads thread-key])))

;; ---------------------------------------------------------------------------
;; Config file watcher (hot-reload)
;; ---------------------------------------------------------------------------

(defonce ^:private config-watcher (atom nil))

(defn stop-config-watcher!
  "Stop watching .fmod.json for changes."
  []
  (when-let [watcher @config-watcher]
    (.close watcher)
    (reset! config-watcher nil)))

(defn start-config-watcher!
  "Watch the project's .fmod.json for changes and reload config into app-state.
   Call after open-project! sets :config-path."
  []
  (stop-config-watcher!)
  (when-let [config-path (get-in @app-state [:project :config-path])]
    (let [fs-mod (js/require "fs")
          watcher (.watch fs-mod config-path
                    (fn [_event-type _filename]
                      (-> (p/let [content (fs/read-file config-path)
                                  raw (-> content js/JSON.parse (js->clj :keywordize-keys true))]
                            (swap! app-state assoc-in [:project :config]
                                   (-> raw
                                       (update :log #(merge {:heartbeats false} %)))))
                          (p/catch (fn [err]
                                     (js/process.stderr.write
                                      (str "Warning: failed to reload " config-path ": "
                                           (ex-message err) "\n")))))))]
      (reset! config-watcher watcher))))

;; ---------------------------------------------------------------------------
;; Project open
;; ---------------------------------------------------------------------------

(defn- build-file-tree
  "Build a nested tree structure from a flat list of relative file paths.
   Each node: {:name, :path, :type (:file/:dir), :children}."
  [base-path files]
  (let [tree (atom {})]
    ;; Build a nested map: {"dir" {:children {"file.lua" {:leaf true}}}}
    (doseq [f files]
      (let [parts (.split f "/")]
        (loop [parts (vec parts)
               path-acc base-path
               cursor []]
          (when (seq parts)
            (let [part (first parts)
                  full-path (fs/join path-acc part)
                  new-cursor (conj cursor part)]
              (if (= 1 (count parts))
                ;; leaf file
                (swap! tree assoc-in (conj new-cursor ::leaf) true)
                ;; directory
                (do
                  (swap! tree update-in new-cursor #(or % {}))
                  (recur (rest parts) full-path new-cursor))))))))
    ;; Convert nested map to tree nodes
    (letfn [(map->nodes [m parent-path]
              (->> (dissoc m ::leaf)
                   (sort-by key)
                   (mapv (fn [[name children]]
                           (let [full-path (fs/join parent-path name)]
                             (if (and (::leaf children) (= 1 (count children)))
                               {:name name :path full-path :type :file}
                               {:name name :path full-path :type :dir
                                :expanded? false
                                :children (map->nodes children full-path)}))))))]
      (map->nodes @tree base-path))))

(defn open-project!
  "Open a project directory. Reads .fmod.json config, lists files, updates state.
   Returns a promise of the project info map."
  [project-path]
  (p/let [abs-path (fs/resolve-path project-path)
          config-result (-> (config/read-config abs-path)
                            (p/catch (fn [_] {:config nil :config-path nil})))
          {:keys [config config-path]} config-result
          ;; Determine mod source path
          src-dir (if config
                    (get-in config [:structure :src] "src")
                    "src")
          mod-path (fs/join abs-path src-dir)
          ;; Read mod data from src dir (best effort)
          mod-data (-> (mod/read-mod-dir mod-path)
                       (p/catch (fn [_] {:path mod-path :info nil :files []})))
          ;; List all project files for the file tree
          all-files (-> (fs/list-files-recursive abs-path)
                        (p/catch (fn [_] [])))
          file-tree (build-file-tree abs-path all-files)]
    ;; Update app state
    (swap! app-state
           (fn [state]
             (-> state
                 (assoc-in [:project :current-path] abs-path)
                 (assoc-in [:project :config] config)
                 (assoc-in [:project :config-path] config-path)
                 (assoc-in [:project :file-tree] file-tree)
                 (assoc-in [:project :mods mod-path] mod-data))))
    ;; Start watching .fmod.json for hot-reload of config (e.g. log settings)
    (start-config-watcher!)
    {:path        abs-path
     :config      config
     :config-path config-path
     :mod-path    mod-path
     :file-tree   file-tree
     :mod-data    mod-data}))

(defn current-project-path
  "Returns the current project path, or nil if no project is open."
  []
  (get-in @app-state [:project :current-path]))

(defn current-mod-path
  "Returns the mod source path for the current project.
   Falls back to config structure.src or 'src' default."
  []
  (when-let [project-path (current-project-path)]
    (let [config (get-in @app-state [:project :config])
          src-dir (if config
                    (get-in config [:structure :src] "src")
                    "src")]
      (fs/join project-path src-dir))))

;; ---------------------------------------------------------------------------
;; Initialization
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize state: load preferences from disk.
   Call this at server startup. Returns a promise."
  []
  (p/let [prefs (load-preferences!)]
    (swap! app-state assoc :preferences prefs)))
