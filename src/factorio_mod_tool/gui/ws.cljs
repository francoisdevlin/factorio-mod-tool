(ns factorio-mod-tool.gui.ws
  "WebSocket client for connecting to the fmod HTTP+WS server.")

(defonce ^:private ws-atom (atom nil))
(defonce ^:private msg-id-counter (atom 0))
(defonce ^:private pending-requests (atom {}))
(defonce on-message (atom nil))
(defonce on-status-change (atom nil))

(defn connected? []
  (when-let [ws @ws-atom]
    (= (.-readyState ws) 1)))

(defn- next-id []
  (str "gui-" (swap! msg-id-counter inc)))

(defn send-command!
  "Send a command via WebSocket. Returns a promise that resolves with the response."
  [method path & [body]]
  (js/Promise.
   (fn [resolve reject]
     (if-not (connected?)
       (reject (js/Error. "WebSocket not connected"))
       (let [id (next-id)
             msg #js {:type "command"
                      :id id
                      :method method
                      :path path
                      :body (clj->js (or body {}))}]
         (swap! pending-requests assoc id {:resolve resolve :reject reject})
         (.send @ws-atom (js/JSON.stringify msg)))))))

(defn- handle-message [event]
  (let [data (try
               (-> (.-data event) js/JSON.parse (js->clj :keywordize-keys true))
               (catch :default _ nil))]
    (when data
      (case (:type data)
        "response"
        (when-let [{:keys [resolve]} (get @pending-requests (:id data))]
          (swap! pending-requests dissoc (:id data))
          (resolve (js->clj (:body data) :keywordize-keys true)))

        "error"
        (when-let [{:keys [reject]} (get @pending-requests (:id data))]
          (swap! pending-requests dissoc (:id data))
          (reject (js/Error. (:message data))))

        "broadcast"
        (when-let [handler @on-message]
          (handler data))

        ;; default - pass to on-message handler
        (when-let [handler @on-message]
          (handler data))))))

(defn connect!
  "Connect to the WebSocket server at the given URL."
  [url]
  (when-let [old @ws-atom]
    (.close old))
  (let [ws (js/WebSocket. url)]
    (set! (.-onopen ws)
          (fn [_]
            (when-let [cb @on-status-change] (cb :connected))))
    (set! (.-onclose ws)
          (fn [_]
            (reset! ws-atom nil)
            (when-let [cb @on-status-change] (cb :disconnected))))
    (set! (.-onerror ws)
          (fn [_]
            (when-let [cb @on-status-change] (cb :error))))
    (set! (.-onmessage ws) handle-message)
    (reset! ws-atom ws)))

(defn disconnect! []
  (when-let [ws @ws-atom]
    (.close ws)
    (reset! ws-atom nil)))
