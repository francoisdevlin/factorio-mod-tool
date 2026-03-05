(ns factorio-mod-tool.http.sse
  "HTTP+SSE transport adapter for MCP.
   - GET /mcp/sse — opens an SSE stream, sends an `endpoint` event with the POST URL
   - POST /mcp — receives JSON-RPC requests, dispatches through the MCP session,
     responses are pushed to the matching SSE stream"
  (:require [clojure.string :as str]
            [mcp-toolkit.json-rpc :as json-rpc]))

;; ---------------------------------------------------------------------------
;; SSE session registry
;; ---------------------------------------------------------------------------

(defonce ^:private sse-sessions
  (atom {}))

(defonce ^:private mcp-session-atom
  (atom nil))

(defn set-mcp-session!
  "Inject the MCP session atom to avoid circular dependency with server.cljs."
  [session-atom]
  (reset! mcp-session-atom session-atom))

(defn- generate-session-id []
  (let [chars "abcdefghijklmnopqrstuvwxyz0123456789"
        len 16]
    (apply str (repeatedly len #(nth chars (rand-int (count chars)))))))

(defn- write-sse-event
  "Write a Server-Sent Event to the response stream."
  [^js res event-type data]
  (let [json (if (string? data) data (js/JSON.stringify (clj->js data)))]
    (.write res (str "event: " event-type "\n"))
    (.write res (str "data: " json "\n\n"))))

;; ---------------------------------------------------------------------------
;; GET /mcp/sse — open SSE stream
;; ---------------------------------------------------------------------------

(defn handle-sse-connect
  "Handle GET /mcp/sse. Opens an SSE stream and registers the session."
  [^js req ^js res]
  (.writeHead res 200
    #js {"Content-Type"                "text/event-stream"
         "Cache-Control"               "no-cache"
         "Connection"                  "keep-alive"
         "Access-Control-Allow-Origin" "*"})
  (let [session-id (generate-session-id)
        send-fn    (fn [message]
                     (write-sse-event res "message" message))]
    ;; Register session
    (swap! sse-sessions assoc session-id
           {:res     res
            :send-fn send-fn})
    ;; Send the endpoint event so the client knows where to POST
    (write-sse-event res "endpoint" (str "/mcp?sessionId=" session-id))
    ;; Keep-alive heartbeat every 30s
    (let [heartbeat (js/setInterval
                      (fn [] (.write res ":heartbeat\n\n"))
                      30000)]
      ;; Clean up on disconnect
      (.on req "close"
        (fn []
          (js/clearInterval heartbeat)
          (swap! sse-sessions dissoc session-id))))))

;; ---------------------------------------------------------------------------
;; POST /mcp — receive JSON-RPC requests
;; ---------------------------------------------------------------------------

(defn- read-body
  "Read request body as string. Returns a promise."
  [^js req]
  (js/Promise.
   (fn [resolve _reject]
     (let [chunks (atom [])]
       (.on req "data" (fn [chunk] (swap! chunks conj chunk)))
       (.on req "end" (fn [] (resolve (str/join @chunks))))))))

(defn handle-mcp-post
  "Handle POST /mcp. Parses JSON-RPC request, dispatches through the MCP
   session, and pushes the response to the matching SSE stream."
  [^js req ^js res]
  (let [raw-url  (.-url req)
        url-obj  (js/URL. raw-url "http://localhost")
        session-id (.get (.-searchParams url-obj) "sessionId")]
    (if-not session-id
      (do
        (.writeHead res 400 #js {"Content-Type" "application/json"
                                 "Access-Control-Allow-Origin" "*"})
        (.end res (js/JSON.stringify #js {:error "Missing sessionId parameter"})))
      (if-let [{:keys [send-fn]} (get @sse-sessions session-id)]
        ;; Build an MCP context that sends responses through the SSE stream
        (let [context {:session      @mcp-session-atom
                       :send-message send-fn}]
          (-> (read-body req)
              (.then (fn [body-str]
                       (let [message (try
                                       (-> body-str js/JSON.parse
                                           (js->clj :keywordize-keys true))
                                       (catch js/SyntaxError _e nil))]
                         (if-not message
                           (do
                             (send-fn json-rpc/parse-error-response)
                             (.writeHead res 400 #js {"Content-Type" "application/json"
                                                      "Access-Control-Allow-Origin" "*"})
                             (.end res (js/JSON.stringify #js {:error "Invalid JSON"})))
                           (do
                             (json-rpc/handle-message context message)
                             (.writeHead res 202 #js {"Content-Type" "application/json"
                                                      "Access-Control-Allow-Origin" "*"})
                             (.end res (js/JSON.stringify #js {:accepted true})))))))
              (.catch (fn [err]
                        (.writeHead res 500 #js {"Content-Type" "application/json"
                                                 "Access-Control-Allow-Origin" "*"})
                        (.end res (js/JSON.stringify
                                   #js {:error (or (ex-message err) "Internal error")}))))))
        ;; Session not found
        (do
          (.writeHead res 404 #js {"Content-Type" "application/json"
                                   "Access-Control-Allow-Origin" "*"})
          (.end res (js/JSON.stringify
                     #js {:error "SSE session not found"
                          :sessionId session-id})))))))
