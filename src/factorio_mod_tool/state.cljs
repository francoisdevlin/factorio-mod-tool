(ns factorio-mod-tool.state
  "Centralized application state for the Factorio mod tool.")

;; Map of mod-path (string) -> parsed mod data
;; {:info   <parsed info.json>
;;  :files  [<relative paths>]
;;  :diagnostics [<lint/validation results>]}
(defonce mod-state (atom {}))

;; Map of instance-name (string) -> RCON connection
;; {:host               "localhost"
;;  :port               27015
;;  :conn               <Rcon instance>
;;  :health             :alive | :unreachable | :timeout | :unknown
;;  :last-heartbeat-at  <ISO string or nil>
;;  :heartbeat-failures 0
;;  :heartbeat-timer    <timer-id or nil>}
(defonce rcon-connections (atom {}))

(defn get-mod
  "Returns the state for a given mod path, or nil."
  [path]
  (get @mod-state path))

(defn update-mod!
  "Updates the state for a given mod path."
  [path data]
  (swap! mod-state assoc path data))

(defn remove-mod!
  "Removes a mod from tracked state."
  [path]
  (swap! mod-state dissoc path))

(defn get-rcon
  "Returns the RCON connection for a given instance name, or nil."
  [instance-name]
  (get @rcon-connections instance-name))

(defn set-rcon!
  "Stores an RCON connection for a given instance name."
  [instance-name conn-data]
  (swap! rcon-connections assoc instance-name conn-data))

(defn remove-rcon!
  "Removes an RCON connection from tracked state."
  [instance-name]
  (when-let [timer (:heartbeat-timer (get @rcon-connections instance-name))]
    (js/clearInterval timer))
  (swap! rcon-connections dissoc instance-name))

(defn update-rcon-health!
  "Update health status for an RCON connection after a heartbeat."
  [instance-name health-status]
  (swap! rcon-connections update instance-name
         (fn [conn]
           (if (= health-status :alive)
             (assoc conn
                    :health :alive
                    :last-heartbeat-at (.toISOString (js/Date.))
                    :heartbeat-failures 0)
             (let [failures (inc (or (:heartbeat-failures conn) 0))]
               (assoc conn
                      :health (if (>= failures 3) :unreachable health-status)
                      :heartbeat-failures failures))))))

;; ---------------------------------------------------------------------------
;; WebSocket state
;; ---------------------------------------------------------------------------

;; Set of all connected WebSocket client objects
(defonce ws-clients (atom #{}))

;; Set of clients that have subscribed to the live event stream
(defonce ws-subscribers (atom #{}))

;; User preferences (theme, etc.)
(defonce preferences (atom {:theme "factorio"}))

(defn broadcast!
  "Send a message to all subscribed WebSocket clients."
  [msg]
  (let [data (js/JSON.stringify (clj->js msg))]
    (doseq [client @ws-subscribers]
      (when (= (.-readyState client) (.-OPEN client))
        (.send client data)))))
