(ns factorio-mod-tool.gui.state
  "Application state for the GUI."
  (:require [reagent.core :as r]))

;; Connection status: :disconnected, :connected, :error
(defonce connection-status (r/atom :disconnected))

;; Server info from /api/status
(defonce server-status (r/atom nil))

;; Capabilities from /api/capabilities
(defonce capabilities (r/atom nil))

;; File tree: vector of {:name, :path, :type (:file/:dir), :children, :expanded?}
(defonce file-tree (r/atom []))

;; Currently selected file path
(defonce selected-file (r/atom nil))

;; Content of the currently viewed file
(defonce file-content (r/atom nil))

;; Diagnostics: vector of diagnostic maps
(defonce diagnostics (r/atom []))

;; RCON console lines: vector of {:type (:command/:response/:error), :text}
(defonce console-lines (r/atom []))

;; Pipeline status: nil or {:target, :status (:running/:ok/:error)}
(defonce pipeline-status (r/atom nil))

;; Active navigation section
(defonce active-section (r/atom :projects))

;; Current theme: "factorio" (default), "light", or "dark"
(defonce current-theme (r/atom "factorio"))

;; RCON connection health: map of instance-name -> {:health, :last-heartbeat-at, :failures}
(defonce rcon-health (r/atom {}))
