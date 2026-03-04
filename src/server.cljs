(ns server
  "MCP server for Factorio mod development tools.

  Implements the Model Context Protocol over stdio using JSON-RPC 2.0."
  (:require ["readline" :as readline]
            [analysis.lint :as lint]
            [clojure.string :as str]))

(def server-info
  {:name    "factorio-mod-tool"
   :version "0.1.0"})

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def tools
  [{:name        "lint-mod"
    :description "Lint a Factorio mod directory for common issues including deprecated API usage, data-lifecycle violations, and missing locale strings."
    :inputSchema {:type       "object"
                  :properties {:mod_dir {:type        "string"
                                         :description "Path to the Factorio mod directory to lint"}
                               :rules   {:type        "array"
                                          :items       {:type "string"
                                                        :enum ["deprecated-api" "data-lifecycle" "missing-locale"]}
                                          :description "Specific rules to run (default: all rules)"}}
                  :required   ["mod_dir"]}}])

;; ---------------------------------------------------------------------------
;; Tool handlers
;; ---------------------------------------------------------------------------

(defn- handle-lint-mod
  "Execute the lint-mod tool."
  [args]
  (let [mod-dir (:mod_dir args)
        rules   (when-let [r (:rules args)]
                  (set (map keyword r)))]
    (if-not mod-dir
      {:content [{:type "text" :text "Error: mod_dir argument is required"}]
       :isError true}
      (let [opts        (cond-> {:mod-dir mod-dir}
                          rules (assoc :rules rules))
            diagnostics (lint/lint-mod opts)
            result      {:diagnostics diagnostics
                         :count       (count diagnostics)
                         :errors      (count (filter #(= :error (:severity %)) diagnostics))
                         :warnings    (count (filter #(= :warning (:severity %)) diagnostics))}]
        {:content [{:type "text" :text (js/JSON.stringify (clj->js result) nil 2)}]}))))

(defn- call-tool
  "Dispatch a tool call to the appropriate handler."
  [tool-name args]
  (case tool-name
    "lint-mod" (handle-lint-mod args)
    {:content [{:type "text" :text (str "Unknown tool: " tool-name)}]
     :isError true}))

;; ---------------------------------------------------------------------------
;; JSON-RPC / MCP protocol
;; ---------------------------------------------------------------------------

(defn- json-rpc-response
  "Build a JSON-RPC 2.0 response."
  [id result]
  {:jsonrpc "2.0"
   :id      id
   :result  result})

(defn- json-rpc-error
  "Build a JSON-RPC 2.0 error response."
  [id code message]
  {:jsonrpc "2.0"
   :id      id
   :error   {:code code :message message}})

(defn- handle-request
  "Handle a single MCP JSON-RPC request."
  [{:keys [id method params]}]
  (case method
    "initialize"
    (json-rpc-response id
                       {:protocolVersion "2024-11-05"
                        :capabilities    {:tools {}}
                        :serverInfo      server-info})

    "notifications/initialized"
    nil ;; Notification, no response needed

    "tools/list"
    (json-rpc-response id {:tools tools})

    "tools/call"
    (let [tool-name (:name params)
          args      (:arguments params)]
      (json-rpc-response id (call-tool tool-name args)))

    "ping"
    (json-rpc-response id {})

    ;; Unknown method
    (json-rpc-error id -32601 (str "Method not found: " method))))

(defn- send-response!
  "Write a JSON-RPC response to stdout."
  [response]
  (when response
    (let [json-str (js/JSON.stringify (clj->js response))]
      (.write js/process.stdout (str json-str "\n")))))

(defn- process-line!
  "Process a single line of input as a JSON-RPC message."
  [line]
  (when-not (str/blank? line)
    (try
      (let [msg      (js->clj (js/JSON.parse line) :keywordize-keys true)
            response (handle-request msg)]
        (send-response! response))
      (catch :default e
        (send-response! (json-rpc-error nil -32700 (str "Parse error: " (.-message e))))))))

;; ---------------------------------------------------------------------------
;; Server startup
;; ---------------------------------------------------------------------------

(defn start-server
  "Start the MCP server, reading JSON-RPC messages from stdin."
  []
  (let [rl (readline/createInterface
            #js {:input  js/process.stdin
                 :output js/process.stdout
                 :terminal false})]
    (.on rl "line" process-line!)
    (.on rl "close" #(.exit js/process 0))))

(defn -main []
  (start-server))
