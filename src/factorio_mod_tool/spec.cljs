(ns factorio-mod-tool.spec
  "cljs.spec definitions for the server-side app-state schema.
   Three top-level keys: :project, :connection, :preferences."
  (:require [cljs.spec.alpha :as s]))

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(s/def ::non-blank-string (s/and string? (complement empty?)))

;; ---------------------------------------------------------------------------
;; :project — loaded mod data, diagnostics, config
;; ---------------------------------------------------------------------------

(s/def :project/path (s/nilable string?))

(s/def :mod/info map?)
(s/def :mod/files (s/coll-of string? :kind vector?))

(s/def :diagnostic/severity #{:error :warning :info})
(s/def :diagnostic/message string?)
(s/def :diagnostic/entry (s/keys :req-un [:diagnostic/severity :diagnostic/message]))
(s/def :mod/diagnostics (s/coll-of :diagnostic/entry :kind vector?))

(s/def :project/mod-entry (s/keys :opt-un [:mod/info :mod/files :mod/diagnostics]))
(s/def :project/mods (s/map-of string? :project/mod-entry))

(s/def :project/config (s/nilable map?))

(s/def ::project (s/keys :opt-un [:project/path :project/mods :project/config]))

;; ---------------------------------------------------------------------------
;; :connection — RCON instances and selection
;; ---------------------------------------------------------------------------

(s/def :conn/host string?)
(s/def :conn/port pos-int?)
(s/def :conn/status #{:connected :disconnected :error})
(s/def :conn/conn any?)  ; opaque Rcon JS object

(s/def :connection/instance
  (s/keys :req-un [:conn/host :conn/port]
          :opt-un [:conn/conn :conn/status]))

(s/def :connection/instances (s/map-of string? :connection/instance))
(s/def :connection/active (s/nilable string?))

(s/def ::connection (s/keys :opt-un [:connection/instances :connection/active]))

;; ---------------------------------------------------------------------------
;; :preferences — persisted user settings
;; ---------------------------------------------------------------------------

(s/def :pref/theme #{:light :dark :factorio})
(s/def :pref/density #{:compact :comfortable :spacious})
(s/def :pref/layout map?)
(s/def :pref/default-paths map?)
(s/def :pref/editor map?)

(s/def ::preferences
  (s/keys :opt-un [:pref/theme :pref/density :pref/layout
                   :pref/default-paths :pref/editor]))

;; ---------------------------------------------------------------------------
;; Top-level app-state
;; ---------------------------------------------------------------------------

(s/def ::app-state
  (s/keys :req-un [::project ::connection ::preferences]))
