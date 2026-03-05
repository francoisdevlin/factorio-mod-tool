(ns factorio-mod-tool.gui.app
  "Main entry point for the browser GUI."
  (:require [reagent.dom :as rdom]
            [factorio-mod-tool.gui.state :as state]
            [factorio-mod-tool.gui.ws :as ws]
            [factorio-mod-tool.gui.components :as c]))

(defn app []
  [:div.app-layout
   {:class (when (not= :projects @state/active-section) "single-panel")}
   [c/top-bar]
   [c/nav-bar]
   [c/section-content]
   [c/console-panel]])

(defn- fetch-initial-data []
  ;; Fetch server status
  (-> (ws/send-command! "GET" "/api/status")
      (.then (fn [res] (reset! state/server-status res)))
      (.catch (fn [_])))
  ;; Fetch capabilities
  (-> (ws/send-command! "GET" "/api/capabilities")
      (.then (fn [res]
               (reset! state/capabilities (:capabilities res))))
      (.catch (fn [_])))
  ;; Fetch diagnostics
  (-> (ws/send-command! "GET" "/api/diagnostics")
      (.then (fn [res]
               (when-let [mods (:mods res)]
                 (let [all-diags (mapcat (fn [[_path data]]
                                          (:diagnostics data))
                                        mods)]
                   (reset! state/diagnostics (vec all-diags))))))
      (.catch (fn [_]))))

(defn- setup-ws-handlers []
  (reset! ws/on-status-change
          (fn [status]
            (reset! state/connection-status status)
            (when (= status :connected)
              (fetch-initial-data))))
  (reset! ws/on-message
          (fn [msg]
            (case (:type msg)
              "diagnostics"
              (reset! state/diagnostics (vec (:diagnostics msg)))

              "pipeline-status"
              (reset! state/pipeline-status {:target (:target msg)
                                             :status (keyword (:status msg))})
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
