(ns factorio-mod-tool.http.server
  "HTTP + WebSocket server entry point.
   Uses Node.js http module and ws npm package."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [factorio-mod-tool.http.routes :as routes]
            [factorio-mod-tool.queue :as queue]
            [factorio-mod-tool.state :as state]
            [factorio-mod-tool.util.config :as config]
            [factorio-mod-tool.http.static :as static]))

(def ^:private http (js/require "http"))
(def ^:private WebSocketServer (.-WebSocketServer (js/require "ws")))

;; ---------------------------------------------------------------------------
;; WebSocket management
;; ---------------------------------------------------------------------------

(defonce ws-clients (atom #{}))

(defn broadcast!
  "Send a message to all connected WebSocket clients."
  [msg]
  (let [data (js/JSON.stringify (clj->js msg))]
    (doseq [client @ws-clients]
      (when (= (.-readyState client) (.-OPEN client))
        (.send client data)))))

(defn- setup-websocket [^js wss]
  (.on wss "connection"
    (fn [^js ws _req]
      (swap! ws-clients conj ws)
      (.on ws "message"
        (fn [raw]
          (let [msg (try
                      (-> raw str js/JSON.parse (js->clj :keywordize-keys true))
                      (catch :default _ nil))]
            (when msg
              (let [{:keys [type]} msg]
                (case type
                  "ping"
                  (.send ws (js/JSON.stringify #js {:type "pong"}))

                  ;; Forward commands through the route table
                  "command"
                  (let [{:keys [method path body]} msg
                        method-kw (keyword (str/lower-case (or method "post")))
                        handler (get routes/route-table [method-kw path])]
                    (if handler
                      (-> (handler body)
                          (p/then (fn [{:keys [status body]}]
                                    (.send ws (js/JSON.stringify
                                               (clj->js {:type "response"
                                                         :id (:id msg)
                                                         :status status
                                                         :body body})))))
                          (p/catch (fn [err]
                                     (.send ws (js/JSON.stringify
                                                (clj->js {:type "error"
                                                          :id (:id msg)
                                                          :message (ex-message err)}))))))
                      (.send ws (js/JSON.stringify
                                 (clj->js {:type "error"
                                           :id (:id msg)
                                           :message (str "Unknown route: " method " " path)})))))

                  ;; Unknown message type
                  (.send ws (js/JSON.stringify
                             (clj->js {:type "error"
                                       :message (str "Unknown message type: " type)})))))))))
      (.on ws "close"
        (fn []
          (swap! ws-clients disj ws))))))

;; ---------------------------------------------------------------------------
;; HTTP request handling
;; ---------------------------------------------------------------------------

(defn- read-body
  "Read the request body as a string. Returns a promise."
  [^js req]
  (js/Promise.
   (fn [resolve _reject]
     (let [chunks (atom [])]
       (.on req "data" (fn [chunk] (swap! chunks conj chunk)))
       (.on req "end" (fn [] (resolve (str/join @chunks))))))))

(defn- parse-json-body [body-str]
  (when (not-empty body-str)
    (-> body-str js/JSON.parse (js->clj :keywordize-keys true))))

(defn- send-json-response [^js res status body]
  (let [json (js/JSON.stringify (clj->js body) nil 2)]
    (.writeHead res status #js {"Content-Type" "application/json"
                                "Access-Control-Allow-Origin" "*"})
    (.end res json)))

(defn- handle-cors-preflight [_req ^js res]
  (.writeHead res 204 #js {"Access-Control-Allow-Origin" "*"
                           "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
                           "Access-Control-Allow-Headers" "Content-Type"
                           "Access-Control-Max-Age" "86400"})
  (.end res))

(defn- handle-request [req res]
  (let [method (str/lower-case (.-method req))
        raw-url (.-url req)
        url (first (.split raw-url "?"))]
    (cond
      ;; CORS preflight
      (= method "options")
      (handle-cors-preflight req res)

      ;; API routes
      :else
      (let [handler (get routes/route-table [(keyword method) url])]
        (if handler
          (-> (p/let [body-str (if (= method "get") (p/resolved "") (read-body req))
                      body (parse-json-body body-str)
                      result (handler body)]
                (send-json-response res (:status result) (:body result)))
              (p/catch (fn [err]
                         (send-json-response res 500 {:error (ex-message err)}))))
          ;; Try serving static files for the GUI
          (static/serve-static req res))))))

;; ---------------------------------------------------------------------------
;; Server startup
;; ---------------------------------------------------------------------------

(defn- parse-port-arg
  "Parse --port flag from argv, falling back to config or default 3000."
  [config]
  (let [args (vec (drop 2 (.-argv js/process)))
        port-idx (.indexOf args "--port")
        arg-port (when (and (>= port-idx 0) (< (inc port-idx) (count args)))
                   (js/parseInt (nth args (inc port-idx))))]
    (or arg-port
        (get-in config [:http :port])
        3000)))

(defn start-server!
  "Start the HTTP+WS server on the given port. Returns a promise that resolves
   when the server is listening."
  [port]
  (queue/set-broadcast! broadcast!)
  (state/set-broadcast! broadcast!)
  (-> (p/let [_ (state/load-preferences!)
              ^js server (.createServer http handle-request)
              wss (WebSocketServer. #js {:server server})]
        (setup-websocket wss)
        (js/Promise.
         (fn [resolve _reject]
           (.listen server port
             (fn []
               (js/process.stderr.write (str "factorio-mod-tool HTTP server listening on port " port "\n"))
               (js/process.stderr.write (str "  REST API: http://localhost:" port "/api/status\n"))
               (js/process.stderr.write (str "  WebSocket: ws://localhost:" port "/ws\n"))
               (resolve server))))))
      (p/catch (fn [err]
                 (js/process.stderr.write (str "Failed to start HTTP server: " (ex-message err) "\n"))
                 (throw err)))))

(defn main [& _args]
  (-> (p/let [config-result (-> (config/read-config)
                                (p/catch (fn [_] {:config {} :config-path nil})))
              port (parse-port-arg (:config config-result))]
        (start-server! port))
      (p/catch (fn [err]
                 (js/console.error "Failed to start server:" (ex-message err))
                 (js/process.exit 1)))))
