(ns factorio-mod-tool.server
  "MCP server entry point. Thin adapter that wires the command catalog
   through the central queue. Each MCP tool delegates to queue/submit!
   rather than calling domain functions directly."
  (:require [clojure.string :as str]
            [mcp-toolkit.server :as server]
            [mcp-toolkit.json-rpc :as json-rpc]
            [promesa.core :as p]
            [factorio-mod-tool.commands :as commands]
            [factorio-mod-tool.queue :as queue]
            [factorio-mod-tool.http.server :as http-server]
            [factorio-mod-tool.util.config :as config]))

;; ---------------------------------------------------------------------------
;; Catalog → MCP tool adapter
;; ---------------------------------------------------------------------------

(defn- catalog-entry->mcp-tool
  "Convert a command catalog entry into an MCP tool definition.
   The tool-fn delegates to queue/submit! so all commands flow through
   the central queue regardless of transport."
  [{:keys [name description input-schema]}]
  {:name        name
   :description description
   :inputSchema input-schema
   :tool-fn     (fn [_context arguments]
                  (-> (queue/submit! name arguments)
                      (p/then (fn [result]
                                {:content [{:type "text"
                                            :text (pr-str result)}]
                                 :isError false}))
                      (p/catch (fn [err]
                                 {:content [{:type "text"
                                             :text (str (str/capitalize (first (str/split name #"-")))
                                                        " error: " (ex-message err))}]
                                  :isError true}))))})

(def ^:private mcp-tools
  "MCP tool definitions generated from the command catalog."
  (mapv catalog-entry->mcp-tool commands/catalog))

;; ---------------------------------------------------------------------------
;; Session & context
;; ---------------------------------------------------------------------------

(def session
  (atom
    (server/create-session
      {:server-info {:name    "factorio-mod-tool"
                     :version "0.1.0"}
       :tools       mcp-tools})))

(def context
  {:session      session
   :send-message (fn [message]
                   (js/process.stdout.write
                     (-> message clj->js js/JSON.stringify (str "\n"))))})

;; ---------------------------------------------------------------------------
;; stdio transport
;; ---------------------------------------------------------------------------

(defn- handle-stdin-data [chunk]
  (doseq [line (str/split-lines chunk)]
    (when-not (str/blank? line)
      (when-some [message (try
                            (-> line js/JSON.parse (js->clj :keywordize-keys true))
                            (catch js/SyntaxError _e
                              (json-rpc/send-message context json-rpc/parse-error-response)
                              nil))]
        (json-rpc/handle-message context message)))))

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

(defn main [& _args]
  (js/process.stdin.setEncoding "utf8")
  (when (.-setEncoding js/process.stdout)
    (.setEncoding js/process.stdout "utf8"))
  (js/process.stdin.on "data" handle-stdin-data)
  ;; When stdin closes (MCP client disconnects), only exit if the HTTP server
  ;; isn't keeping the process alive. Without process.exit, the event loop
  ;; naturally exits when no listeners remain, but stays alive if the HTTP
  ;; server is running.
  (js/process.stdin.on "end" (fn [] nil))
  (js/process.stderr.write "factorio-mod-tool MCP server started\n")
  ;; Start the HTTP+WS server alongside the MCP stdio transport
  (-> (p/let [config-result (-> (config/read-config)
                                (p/catch (fn [_] {:config {} :config-path nil})))
              port (parse-port-arg (:config config-result))]
        (http-server/start-server! port))
      (p/catch (fn [err]
                 (js/process.stderr.write (str "Warning: HTTP server failed to start: " (ex-message err) "\n"))))))
