(ns factorio-mod-tool.state
  "Centralized application state for the Factorio mod tool.
   Single app-state atom with three top-level keys: :project, :connection, :preferences.
   State changes are validated against specs (dev mode) and broadcast to WS clients."
  (:require [promesa.core :as p]
            [factorio-mod-tool.spec :as spec]
            [factorio-mod-tool.util.fs :as fs]))

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
         :preferences {:theme "factorio"}}))

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
                   :data (:project new-state)}))
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
      (save-preferences! (:preferences new-state)))))

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

;; ---------------------------------------------------------------------------
;; Initialization
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize state: load preferences from disk.
   Call this at server startup. Returns a promise."
  []
  (p/let [prefs (load-preferences!)]
    (swap! app-state assoc :preferences prefs)))
