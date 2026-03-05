(ns factorio-mod-tool.state
  "Centralized application state for the Factorio mod tool.
   Single app-state atom with three top-level keys:
   :project, :connection, :preferences.
   Spec-validated in dev mode, broadcasts changes to WebSocket clients."
  (:require [cljs.spec.alpha :as s]
            [promesa.core :as p]
            [factorio-mod-tool.spec :as spec]
            [factorio-mod-tool.util.fs :as fs]))

;; ---------------------------------------------------------------------------
;; Default state
;; ---------------------------------------------------------------------------

(def default-state
  {:project     {:path nil
                 :mods {}
                 :config nil}
   :connection  {:instances {}
                 :active nil}
   :preferences {:theme :dark
                 :density :comfortable
                 :layout {}
                 :default-paths {}
                 :editor {}}})

;; ---------------------------------------------------------------------------
;; The single app-state atom
;; ---------------------------------------------------------------------------

(defonce app-state (atom default-state))

;; ---------------------------------------------------------------------------
;; Spec validation (dev mode)
;; ---------------------------------------------------------------------------

(def ^:private dev-mode?
  "Enable spec validation. Controlled by goog.DEBUG (true in dev builds)."
  ^boolean js/goog.DEBUG)

(defn- validate-state!
  "Validate app-state against spec. Logs warnings in dev mode."
  [new-state]
  (when dev-mode?
    (when-not (s/valid? ::spec/app-state new-state)
      (js/console.warn "app-state spec violation:"
                       (s/explain-str ::spec/app-state new-state)))))

;; ---------------------------------------------------------------------------
;; Broadcast hook — set by the HTTP server to avoid circular dependency
;; ---------------------------------------------------------------------------

(defonce ^:private broadcast-fn (atom nil))

(defn set-broadcast!
  "Register a broadcast function for state changes."
  [f]
  (reset! broadcast-fn f))

(defn- broadcast-state-change! [old-state new-state]
  (when-let [f @broadcast-fn]
    ;; Only broadcast the diff keys that changed
    (let [changed (into {}
                        (filter (fn [[k v]] (not= v (get old-state k))))
                        new-state)]
      (when (seq changed)
        (f {:type "state-change"
            :changes changed})))))

;; Add watcher for validation and broadcast
(add-watch app-state ::validate-and-broadcast
  (fn [_key _ref old-state new-state]
    (validate-state! new-state)
    (broadcast-state-change! old-state new-state)))

;; ---------------------------------------------------------------------------
;; Preferences persistence
;; ---------------------------------------------------------------------------

(def ^:private prefs-path
  "Path to persisted preferences file."
  (fs/join (or (.. js/process -env -HOME) "/tmp") ".fmod-preferences.json"))

(defn load-preferences!
  "Load preferences from disk. Returns a promise."
  []
  (-> (p/let [content (fs/read-file prefs-path)
              prefs (-> content js/JSON.parse (js->clj :keywordize-keys true))
              ;; Keywordize theme and density
              prefs (cond-> prefs
                      (string? (:theme prefs))
                      (update :theme keyword)
                      (string? (:density prefs))
                      (update :density keyword))]
        (swap! app-state assoc :preferences (merge (:preferences default-state) prefs))
        :loaded)
      (p/catch (fn [_] :no-prefs-file))))

(defn- save-preferences!
  "Save current preferences to disk. Returns a promise."
  []
  (let [prefs (:preferences @app-state)
        json (js/JSON.stringify (clj->js prefs) nil 2)]
    (fs/write-file prefs-path json)))

;; Watch for preference changes and persist
(add-watch app-state ::persist-preferences
  (fn [_key _ref old-state new-state]
    (when (not= (:preferences old-state) (:preferences new-state))
      (save-preferences!))))

;; ---------------------------------------------------------------------------
;; Project (mod) accessors — backward-compatible API
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
;; Connection (RCON) accessors — backward-compatible API
;; ---------------------------------------------------------------------------

(defn get-rcon
  "Returns the RCON connection for a given instance name, or nil."
  [instance-name]
  (get-in @app-state [:connection :instances instance-name]))

(defn set-rcon!
  "Stores an RCON connection for a given instance name."
  [instance-name conn-data]
  (swap! app-state assoc-in [:connection :instances instance-name]
         (assoc conn-data :status :connected)))

(defn remove-rcon!
  "Removes an RCON connection from tracked state."
  [instance-name]
  (swap! app-state update-in [:connection :instances] dissoc instance-name))

;; ---------------------------------------------------------------------------
;; Preference accessors
;; ---------------------------------------------------------------------------

(defn get-preferences
  "Returns the current preferences map."
  []
  (:preferences @app-state))

(defn update-preferences!
  "Merge new preference values into preferences."
  [prefs]
  (swap! app-state update :preferences merge prefs))

