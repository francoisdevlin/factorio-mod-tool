(ns factorio-mod-tool.queue
  "Central command queue. All operations flow through here regardless of
   transport (MCP, HTTP, WebSocket). Executes handlers from the command
   catalog, logs events, and broadcasts results to WebSocket clients."
  (:require [promesa.core :as p]
            [factorio-mod-tool.commands :as commands]
            [factorio-mod-tool.state :as state]))

;; ---------------------------------------------------------------------------
;; Event log
;; ---------------------------------------------------------------------------

;; Append-only log of command events. Each entry is a map with:
;; :id, :command, :params, :status, :result/:error, :timestamp
(defonce event-log (atom []))

(defonce ^:private event-counter (atom 0))

(defn- next-event-id []
  (swap! event-counter inc))

(defn- now []
  (.toISOString (js/Date.)))

(def ^:private rcon-commands
  "Commands that involve RCON queries."
  #{"rcon-exec" "rcon-inspect" "repl-eval" "repl-inspect"})

;; ---------------------------------------------------------------------------
;; Core API
;; ---------------------------------------------------------------------------

(defn submit!
  "Submit a command for execution. Looks up the handler in the catalog,
   executes it, logs the event, and broadcasts to WebSocket clients.
   Returns a promise of the command result."
  [command-name params]
  (let [cmd (commands/find-command command-name)]
    (if-not cmd
      (let [event {:id        (next-event-id)
                   :command   command-name
                   :params    params
                   :status    :error
                   :error     (str "Unknown command: " command-name)
                   :timestamp (now)}]
        (swap! event-log conj event)
        (state/broadcast! {:type  "event"
                           :event event})
        (p/rejected (ex-info (str "Unknown command: " command-name)
                             {:command command-name})))
      (let [event-id  (next-event-id)
            timestamp (now)]
        (-> ((:handler cmd) params)
            (p/then (fn [result]
                      (let [event {:id        event-id
                                   :command   command-name
                                   :params    params
                                   :status    :ok
                                   :result    result
                                   :timestamp timestamp}]
                        (swap! event-log conj event)
                        (when (rcon-commands command-name)
                          (when-let [instance (:instance params)]
                            (state/touch-rcon-query! instance)
                            (state/broadcast!
                             {:type     "rcon-state"
                              :instance instance
                              :last-query-at (:last-query-at
                                              (state/get-rcon instance))})))
                        (state/broadcast! {:type  "event"
                                           :event event})
                        result)))
            (p/catch (fn [err]
                       (let [event {:id        event-id
                                    :command   command-name
                                    :params    params
                                    :status    :error
                                    :error     (ex-message err)
                                    :timestamp timestamp}]
                         (swap! event-log conj event)
                         (state/broadcast! {:type  "event"
                                            :event event})
                         (throw err)))))))))

;; ---------------------------------------------------------------------------
;; Observability
;; ---------------------------------------------------------------------------

(defn history
  "Return command history. Optionally limited to the last n entries."
  ([] @event-log)
  ([n] (vec (take-last n @event-log))))

(defn clear-history!
  "Clear the event log."
  []
  (reset! event-log []))
