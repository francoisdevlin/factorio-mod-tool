(ns factorio-mod-tool.rcon.client
  "RCON connection management for live Factorio instances. Stub."
  (:require [promesa.core :as p]
            [factorio-mod-tool.state :as state]))

(defn connect
  "Establish an RCON connection to a Factorio instance.
   Returns a promise that resolves when connected.
   Options: {:host \"localhost\" :port 27015 :password \"secret\"}"
  [instance-name {:keys [host port password]
                  :or   {host "localhost" port 27015}}]
  ;; TODO: implement using rcon-client npm package
  (p/resolved nil))

(defn disconnect
  "Close an RCON connection."
  [instance-name]
  ;; TODO: implement
  (p/resolved nil))

(defn exec
  "Execute a command on a connected Factorio instance via RCON.
   Returns a promise of the response string."
  [instance-name command]
  ;; TODO: implement
  (p/resolved ""))

(defn inspect
  "Query game state from a connected Factorio instance.
   Returns a promise of parsed game state data."
  [instance-name query]
  ;; TODO: implement
  (p/resolved {}))
