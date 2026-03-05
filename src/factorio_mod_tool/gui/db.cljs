(ns factorio-mod-tool.gui.db
  "Single client-side state atom for the GUI.
   All state lives here; components subscribe via cursors."
  (:require [reagent.core :as r]))

(defonce app-db
  (r/atom
   {:navigation {:section        :projects
                 :selected-file  nil
                 :file-content   nil
                 :file-loading?  false
                 :file-meta      nil
                 :file-type      :text
                 :file-mime-type nil}

    :server {:status           nil
             :capabilities     nil
             :rcon-connections []
             :rcon-health      {}
             :diagnostics      []
             :pipeline-status  nil
             :pipeline-results {}}

    :project {:current-path nil
              :config       nil}

    :connection-status :disconnected
    :current-theme     "factorio"
    :file-tree         []
    :console-lines     []}))
