(ns factorio-mod-tool.gui.state
  "Application state for the GUI.
   All state lives in db/app-db. These cursors provide backward-compatible
   access for components that haven't migrated to dispatch! yet."
  (:require [reagent.core :as r]
            [factorio-mod-tool.gui.db :as db]))

;; Connection status: :disconnected, :connected, :error
(def connection-status (r/cursor db/app-db [:connection-status]))

;; Server info from /api/status
(def server-status (r/cursor db/app-db [:server :status]))

;; Capabilities from /api/capabilities
(def capabilities (r/cursor db/app-db [:server :capabilities]))

;; File tree: vector of {:name, :path, :type (:file/:dir), :children, :expanded?}
(def file-tree (r/cursor db/app-db [:file-tree]))

;; Currently selected file path
(def selected-file (r/cursor db/app-db [:navigation :selected-file]))

;; Content of the currently viewed file
(def file-content (r/cursor db/app-db [:navigation :file-content]))

;; Whether a file is currently loading
(def file-loading? (r/cursor db/app-db [:navigation :file-loading?]))

;; Metadata for the currently viewed file {:mtime, :size}
(def file-meta (r/cursor db/app-db [:navigation :file-meta]))

;; File type: :text, :image, or :binary
(def file-type (r/cursor db/app-db [:navigation :file-type]))

;; MIME type for image files (e.g. "image/png")
(def file-mime-type (r/cursor db/app-db [:navigation :file-mime-type]))

;; Diagnostics: vector of diagnostic maps
(def diagnostics (r/cursor db/app-db [:server :diagnostics]))

;; RCON console lines: vector of {:type (:command/:response/:error), :text}
(def console-lines (r/cursor db/app-db [:console-lines]))

;; Pipeline status: nil or {:target, :status (:running/:ok/:error)}
(def pipeline-status (r/cursor db/app-db [:server :pipeline-status]))

;; Pipeline results history: map of target -> {:status, :timestamp}
(def pipeline-results (r/cursor db/app-db [:server :pipeline-results]))

;; Active navigation section
(def active-section (r/cursor db/app-db [:navigation :section]))

;; Current theme: "factorio" (default), "light", or "dark"
(def current-theme (r/cursor db/app-db [:current-theme]))

;; RCON connection health: map of instance-name -> {:health, :last-heartbeat-at, :failures}
(def rcon-health (r/cursor db/app-db [:server :rcon-health]))

;; RCON connections: vector of {:instance, :host, :port, :last-query-at}
(def rcon-connections (r/cursor db/app-db [:server :rcon-connections]))

;; Project state
(def project-path (r/cursor db/app-db [:project :current-path]))
(def project-config (r/cursor db/app-db [:project :config]))

;; Check Lua live result: nil or {:file, :status (:ok/:error), :result, :checking?}
(def check-lua-live-result (r/cursor db/app-db [:check-lua-live]))
