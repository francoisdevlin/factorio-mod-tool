(ns factorio-mod-tool.rcon.client
  "RCON connection management for live Factorio instances."
  (:require [promesa.core :as p]
            [factorio-mod-tool.state :as state]
            ["rcon-client" :refer [Rcon]]))

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
                                    :conn rcon})
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
