(ns factorio-mod-tool.rcon.client
  "RCON connection management for live Factorio instances."
  (:require [promesa.core :as p]
            [factorio-mod-tool.state :as state]
            ["rcon-client" :refer [Rcon]]))

;; Forward-declared queue submit fn, set at startup to avoid circular dep
(defonce ^:private queue-submit! (atom nil))

(defn set-queue-submit!
  "Inject the queue/submit! function to avoid circular dependency."
  [submit-fn]
  (reset! queue-submit! submit-fn))

(def ^:private default-heartbeat-interval-ms
  "Default heartbeat interval: 15 seconds."
  15000)

(def ^:private smart-skip-threshold-ms
  "Skip heartbeat if a real query was sent within this window (30 seconds)."
  30000)

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

(defn- log-heartbeat?
  "Check if heartbeat logging is enabled via project config.
   Reads from app-state (updated by open-project! or config reload)."
  []
  (boolean (get-in @state/app-state [:project :config :log :heartbeat])))

(defn- log-hb
  "Write a heartbeat log line to stderr when logging is enabled."
  [& parts]
  (when (log-heartbeat?)
    (js/process.stderr.write (str "[heartbeat] " (apply str parts) "\n"))))

(defn heartbeat
  "Send a single heartbeat probe to a Factorio instance.
   Updates connection health state, broadcasts status, and records telemetry.
   Returns a promise of the health result."
  [instance-name]
  (if-let [{:keys [conn]} (state/get-rcon instance-name)]
    (let [start-ms (.now js/Date)]
      (log-hb "sending to " instance-name ": " heartbeat-command)
      (-> (.send conn heartbeat-command)
          (p/then (fn [response]
                    (state/record-thread-run! :heartbeat (- (.now js/Date) start-ms))
                    (log-hb "response from " instance-name ": " (pr-str response))
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
                     (state/record-thread-run! :heartbeat (- (.now js/Date) start-ms))
                     (log-hb "error from " instance-name ": " (ex-message err))
                     (state/update-rcon-health! instance-name :timeout)
                     (let [conn-data (state/get-rcon instance-name)]
                       (state/broadcast! {:type     "rcon-health"
                                          :instance instance-name
                                          :health   (name (:health conn-data))
                                          :failures (:heartbeat-failures conn-data)})
                       {:instance instance-name
                        :health   (:health conn-data)
                        :error    (ex-message err)})))))
    (p/resolved {:instance instance-name :health :unknown :error "No connection"})))

(defn- recently-queried?
  "Returns true if the connection had a real query within the smart-skip window."
  [instance-name]
  (when-let [{:keys [last-query-at]} (state/get-rcon instance-name)]
    (when last-query-at
      (let [last-ms (.getTime (js/Date. last-query-at))
            now-ms  (.getTime (js/Date.))]
        (< (- now-ms last-ms) smart-skip-threshold-ms)))))

(defn start-heartbeat!
  "Start periodic heartbeat for a connection with smart skip.
   Skips the heartbeat if a real RCON query was sent within the last 30s.
   Options: {:interval-ms 15000} (default 15s)."
  [instance-name & [{:keys [interval-ms]
                     :or   {interval-ms default-heartbeat-interval-ms}}]]
  (when-let [old-timer (:heartbeat-timer (state/get-rcon instance-name))]
    (js/clearInterval old-timer))
  (let [timer (js/setInterval
               (fn []
                 (if (recently-queried? instance-name)
                   (log-hb "skipped " instance-name " (recent query activity)")
                   (if-let [submit @queue-submit!]
                     (submit "rcon-heartbeat" {:instance instance-name})
                     (heartbeat instance-name))))
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

;; ---------------------------------------------------------------------------
;; Global heartbeat scheduler
;; ---------------------------------------------------------------------------

(defonce ^:private global-heartbeat-timer (atom nil))

(defn stop-heartbeat-scheduler!
  "Stop the global heartbeat scheduler. Called on server shutdown."
  []
  (when-let [timer @global-heartbeat-timer]
    (js/clearInterval timer)
    (reset! global-heartbeat-timer nil))
  {:heartbeat-scheduler :stopped})

(defn start-heartbeat-scheduler!
  "Start a global periodic task that heartbeats all active RCON connections.
   Connections with recent query activity (< 30s) are skipped.
   Called once at server startup."
  [& [{:keys [interval-ms]
       :or   {interval-ms default-heartbeat-interval-ms}}]]
  (stop-heartbeat-scheduler!)
  (let [timer (js/setInterval
               (fn []
                 (let [instances (get-in @state/app-state [:connection :instances])
                       start-ms (.now js/Date)]
                   (if (empty? instances)
                     ;; Record idle cycle so GUI shows scheduler is alive
                     (state/record-thread-run! :heartbeat (- (.now js/Date) start-ms))
                     (doseq [[instance-name _conn] instances]
                       (if (recently-queried? instance-name)
                         (log-hb "skipped " instance-name " (recent query activity)")
                         (if-let [submit @queue-submit!]
                           (submit "rcon-heartbeat" {:instance instance-name})
                           (heartbeat instance-name)))))))
               interval-ms)]
    (reset! global-heartbeat-timer timer)
    (js/process.stderr.write
     (str "RCON heartbeat scheduler started (interval: " interval-ms "ms, skip threshold: " smart-skip-threshold-ms "ms)\n"))
    {:heartbeat-scheduler :started
     :interval-ms interval-ms
     :skip-threshold-ms smart-skip-threshold-ms}))
