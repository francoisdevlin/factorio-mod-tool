(ns factorio-mod-tool.state
  "Centralized application state for the Factorio mod tool.")

;; Map of mod-path (string) -> parsed mod data
;; {:info   <parsed info.json>
;;  :files  [<relative paths>]
;;  :diagnostics [<lint/validation results>]}
(defonce mod-state (atom {}))

;; Map of instance-name (string) -> RCON connection
;; {:host   "localhost"
;;  :port   27015
;;  :conn   <Rcon instance>}
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

(defn touch-rcon-query!
  "Record the current time as the last RCON query time for an instance."
  [instance-name]
  (swap! rcon-connections update instance-name
         assoc :last-query-at (.toISOString (js/Date.))))

(defn remove-rcon!
  "Removes an RCON connection from tracked state."
  [instance-name]
  (swap! rcon-connections dissoc instance-name))

;; ---------------------------------------------------------------------------
;; WebSocket state
;; ---------------------------------------------------------------------------

;; Set of all connected WebSocket client objects
(defonce ws-clients (atom #{}))

;; Set of clients that have subscribed to the live event stream
(defonce ws-subscribers (atom #{}))

;; User preferences (theme, etc.)
(defonce preferences (atom {:theme "dark"}))

(defn broadcast!
  "Send a message to all subscribed WebSocket clients."
  [msg]
  (let [data (js/JSON.stringify (clj->js msg))]
    (doseq [client @ws-subscribers]
      (when (= (.-readyState client) (.-OPEN client))
        (.send client data)))))
