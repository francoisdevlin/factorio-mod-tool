(ns factorio-mod-tool.gui.app
  "Main entry point for the browser GUI."
  (:require [reagent.dom :as rdom]
            [factorio-mod-tool.gui.state :as state]
            [factorio-mod-tool.gui.dispatch :as dispatch]
            [factorio-mod-tool.gui.ws :as ws]
            [factorio-mod-tool.gui.components :as c]))

(defn app []
  [:div.app-layout
   {:class (when (not= :projects @state/active-section) "single-panel")}
   [c/top-bar]
   [c/nav-bar]
   [c/section-content]
   [c/console-panel]])

(defn- setup-ws-handlers []
  (reset! ws/on-status-change
          (fn [status]
            (dispatch/dispatch! [:set-connection-status status])
            (when (= status :connected)
              (dispatch/dispatch! [:cmd/fetch-initial-data]))))
  (reset! ws/on-message
          (fn [msg]
            (case (:type msg)
              "diagnostics"
              (dispatch/dispatch! [:server/diagnostics (:diagnostics msg)])

              "pipeline-status"
              (dispatch/dispatch! [:server/pipeline-status msg])

              "preference-change"
              (dispatch/dispatch! [:server/preference-change (:key msg) (:value msg)])

              "rcon-health"
              (dispatch/dispatch! [:server/rcon-health msg])

              "rcon-state"
              (dispatch/dispatch! [:server/rcon-state msg])

              "state-change"
              (case (:key msg)
                "project"    (dispatch/dispatch! [:server/project (:data msg)])
                "connection" (dispatch/dispatch! [:server/connection-state (:data msg)])
                "telemetry"  (dispatch/dispatch! [:server/telemetry (:data msg)])
                nil)

              nil))))

(defn- ws-url []
  (let [loc (.-location js/window)
        protocol (if (= "https:" (.-protocol loc)) "wss:" "ws:")
        host (.-host loc)]
    (str protocol "//" host "/ws")))

(defn init []
  (setup-ws-handlers)
  (ws/connect! (ws-url))
  (rdom/render [app] (.getElementById js/document "app")))
