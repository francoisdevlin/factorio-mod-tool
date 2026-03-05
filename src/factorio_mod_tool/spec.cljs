(ns factorio-mod-tool.spec
  "clojure.spec definitions for the server-side app state schema.
   Four top-level keys: :project, :connection, :preferences, :telemetry."
  (:require [cljs.spec.alpha :as s]))

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(s/def ::non-empty-string (s/and string? not-empty))
(s/def ::iso-timestamp (s/nilable string?))

;; ---------------------------------------------------------------------------
;; :project — loaded mod data indexed by path
;; ---------------------------------------------------------------------------

;; info.json contents (parsed)
(s/def :project/info (s/nilable map?))

;; Vector of relative file paths
(s/def :project/files (s/coll-of string? :kind vector?))

;; Diagnostics from validation/lint
(s/def :diagnostic/severity #{:error :warning :info})
(s/def :diagnostic/message string?)
(s/def :project.diagnostic/entry (s/keys :req-un [:diagnostic/severity :diagnostic/message]))
(s/def :project/diagnostics (s/coll-of :project.diagnostic/entry :kind vector?))

;; .fmod.json config contents
(s/def :project/config (s/nilable map?))

;; Single mod entry
(s/def :project/mod-entry
  (s/keys :opt-un [:project/info :project/files :project/diagnostics :project/config]))

;; Current project path
(s/def :project/current-path (s/nilable string?))

;; Project config from .fmod.json
(s/def :project/config-path (s/nilable string?))

;; File tree nodes for GUI
(s/def :project/file-tree (s/nilable vector?))

;; Project state: map of path -> mod-entry, plus current-path and config
(s/def ::project
  (s/keys :opt-un [:project/current-path :project/mods
                   :project/config :project/config-path :project/file-tree]))

(s/def :project/mods (s/map-of string? :project/mod-entry))

;; ---------------------------------------------------------------------------
;; :connection — RCON connections and health
;; ---------------------------------------------------------------------------

(s/def :connection/host string?)
(s/def :connection/port pos-int?)
(s/def :connection/conn any?)  ; opaque Rcon JS object
(s/def :connection/health #{:alive :unreachable :timeout :unknown})
(s/def :connection/last-heartbeat-at ::iso-timestamp)
(s/def :connection/heartbeat-failures nat-int?)
(s/def :connection/heartbeat-timer any?)  ; JS timer id
(s/def :connection/last-query-at ::iso-timestamp)
(s/def :connection/status #{:connected :disconnected :error})

;; Single RCON connection entry
(s/def :connection/entry
  (s/keys :req-un [:connection/host :connection/port]
          :opt-un [:connection/conn :connection/health :connection/status
                   :connection/last-heartbeat-at :connection/heartbeat-failures
                   :connection/heartbeat-timer :connection/last-query-at]))

;; Active instance selection
(s/def :connection/active-instance (s/nilable string?))

;; Connection state: map of instance-name -> entry, plus active-instance
(s/def ::connection
  (s/keys :opt-un [:connection/instances :connection/active-instance]))

(s/def :connection/instances (s/map-of string? :connection/entry))

;; ---------------------------------------------------------------------------
;; :preferences — user preferences, persisted to disk
;; ---------------------------------------------------------------------------

(s/def :preferences/theme #{"light" "dark" "factorio"})
(s/def :preferences/density (s/nilable #{"compact" "comfortable" "spacious"}))
(s/def :preferences/layout (s/nilable map?))
(s/def :preferences/default-paths (s/nilable map?))
(s/def :preferences/editor (s/nilable map?))

(s/def ::preferences
  (s/keys :opt-un [:preferences/theme :preferences/density
                   :preferences/layout :preferences/default-paths
                   :preferences/editor]))

;; ---------------------------------------------------------------------------
;; :telemetry — background thread observability
;; ---------------------------------------------------------------------------

(s/def :telemetry.thread/last-run-at ::iso-timestamp)
(s/def :telemetry.thread/run-count nat-int?)
(s/def :telemetry.thread/avg-ms number?)

(s/def :telemetry/thread-entry
  (s/keys :req-un [:telemetry.thread/last-run-at
                    :telemetry.thread/run-count
                    :telemetry.thread/avg-ms]))

(s/def :telemetry/threads (s/map-of keyword? :telemetry/thread-entry))

(s/def ::telemetry
  (s/keys :opt-un [:telemetry/threads]))

;; ---------------------------------------------------------------------------
;; Top-level app-state
;; ---------------------------------------------------------------------------

(s/def ::app-state
  (s/keys :req-un [::project ::connection ::preferences ::telemetry]))

;; ---------------------------------------------------------------------------
;; Validation helpers
;; ---------------------------------------------------------------------------

(defn valid?
  "Check if a value conforms to the app-state spec."
  [state]
  (s/valid? ::app-state state))

(defn explain-str
  "Return a human-readable explanation of why state doesn't conform."
  [state]
  (s/explain-str ::app-state state))

(defn validate!
  "Validate state, throwing on failure. For dev-mode checks."
  [state]
  (when-not (s/valid? ::app-state state)
    (throw (ex-info "App state spec violation"
                    {:explanation (s/explain-str ::app-state state)})))
  state)
