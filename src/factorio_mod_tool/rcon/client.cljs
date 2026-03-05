(ns factorio-mod-tool.rcon.client
  "RCON connection management for live Factorio instances."
  (:require [promesa.core :as p]
            [factorio-mod-tool.state :as state]
            ["rcon-client" :refer [Rcon]]))

(def ^:private default-heartbeat-interval-ms
  "Default heartbeat interval: 15 seconds."
  15000)

(def ^:private heartbeat-command
  "Lightest RCON command for connection health check.
   /silent-command suppresses game console output; rcon.print sends
   the response only to the RCON client."
  "/silent-command rcon.print('ok')")

(defn connect
  "Establish an RCON connection to a Factorio instance.
   Returns a promise that resolves when connected.
   Options: {:host \"localhost\" :port 27015 :password \"secret\"}"
  [instance-name {:keys [host port password]
                  :or   {host "localhost" port 27015}}]
  (p/let [rcon (Rcon. #js {:host     host
                            :port     port
                            :password password})
          _    (.connect rcon)]
    (state/set-rcon! instance-name {:host host
                                    :port port
                                    :conn rcon
                                    :health :alive
                                    :last-heartbeat-at (.toISOString (js/Date.))
                                    :heartbeat-failures 0
                                    :heartbeat-timer nil})
    {:status "connected"
     :instance instance-name
     :host host
     :port port}))

(defn disconnect
  "Close an RCON connection."
  [instance-name]
  (if-let [{:keys [conn]} (state/get-rcon instance-name)]
    (p/let [_ (.end conn)]
      (state/remove-rcon! instance-name)
      {:status "disconnected" :instance instance-name})
    (p/resolved {:status "not-found" :instance instance-name})))

(defn exec
  "Execute a command on a connected Factorio instance via RCON.
   Returns a promise of the response string."
  [instance-name command]
  (if-let [{:keys [conn]} (state/get-rcon instance-name)]
    (.send conn command)
    (p/rejected (ex-info "No RCON connection found"
                         {:instance instance-name}))))

(defn inspect
  "Query game state from a connected Factorio instance.
   Wraps the query in /silent-command with serpent.line serialization,
   then parses the returned Lua table string."
  [instance-name query]
  (if-let [{:keys [conn]} (state/get-rcon instance-name)]
    (p/let [lua-cmd (str "/silent-command rcon.print(serpent.line(" query "))")
            response (.send conn lua-cmd)]
      {:query query :result response})
    (p/rejected (ex-info "No RCON connection found"
                         {:instance instance-name}))))

(defn heartbeat
  "Send a single heartbeat probe to a Factorio instance.
   Updates connection health state and broadcasts status.
   Returns a promise of the health result."
  [instance-name]
  (if-let [{:keys [conn]} (state/get-rcon instance-name)]
    (-> (.send conn heartbeat-command)
        (p/then (fn [response]
                  (let [ok? (= (.trim (str response)) "ok")]
                    (state/update-rcon-health! instance-name (if ok? :alive :unreachable))
                    (let [conn-data (state/get-rcon instance-name)]
                      (state/broadcast! {:type     "rcon-health"
                                         :instance instance-name
                                         :health   (name (:health conn-data))
                                         :last-heartbeat-at (:last-heartbeat-at conn-data)})
                      {:instance instance-name
                       :health   (:health conn-data)
                       :last-heartbeat-at (:last-heartbeat-at conn-data)}))))
        (p/catch (fn [err]
                   (state/update-rcon-health! instance-name :timeout)
                   (let [conn-data (state/get-rcon instance-name)]
                     (state/broadcast! {:type     "rcon-health"
                                        :instance instance-name
                                        :health   (name (:health conn-data))
                                        :failures (:heartbeat-failures conn-data)})
                     {:instance instance-name
                      :health   (:health conn-data)
                      :error    (ex-message err)}))))
    (p/resolved {:instance instance-name :health :unknown :error "No connection"})))

(defn start-heartbeat!
  "Start periodic heartbeat for a connection.
   Options: {:interval-ms 15000} (default 15s)."
  [instance-name & [{:keys [interval-ms]
                     :or   {interval-ms default-heartbeat-interval-ms}}]]
  (when-let [old-timer (:heartbeat-timer (state/get-rcon instance-name))]
    (js/clearInterval old-timer))
  (let [timer (js/setInterval
               (fn [] (heartbeat instance-name))
               interval-ms)]
    (swap! state/app-state assoc-in [:connection :instances instance-name :heartbeat-timer] timer)
    ;; Send initial heartbeat immediately
    (heartbeat instance-name)
    {:instance instance-name
     :heartbeat :started
     :interval-ms interval-ms}))

(defn stop-heartbeat!
  "Stop periodic heartbeat for a connection."
  [instance-name]
  (when-let [timer (:heartbeat-timer (state/get-rcon instance-name))]
    (js/clearInterval timer)
    (swap! state/app-state update-in [:connection :instances instance-name] dissoc :heartbeat-timer))
  {:instance instance-name :heartbeat :stopped})

(defn connection-health
  "Get current health status for all or a specific connection."
  ([]
   (into {}
         (map (fn [[k v]]
                [k {:health             (or (:health v) :unknown)
                    :last-heartbeat-at  (:last-heartbeat-at v)
                    :heartbeat-failures (or (:heartbeat-failures v) 0)
                    :host               (:host v)
                    :port               (:port v)}]))
         (get-in @state/app-state [:connection :instances])))
  ([instance-name]
   (when-let [v (state/get-rcon instance-name)]
     {:health             (or (:health v) :unknown)
      :last-heartbeat-at  (:last-heartbeat-at v)
      :heartbeat-failures (or (:heartbeat-failures v) 0)
      :host               (:host v)
      :port               (:port v)})))
