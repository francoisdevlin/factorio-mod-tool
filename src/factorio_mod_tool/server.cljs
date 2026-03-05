(ns factorio-mod-tool.server
  "MCP server entry point. Creates the server, registers tools/resources,
   and starts the stdio transport."
  (:require [clojure.string :as str]
            [mcp-toolkit.server :as server]
            [mcp-toolkit.json-rpc :as json-rpc]
            [promesa.core :as p]
            [factorio-mod-tool.state :as state]
            [factorio-mod-tool.util.lua :as lua]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.analysis.validate :as validate]))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def validate-mod-tool
  {:name        "validate-mod"
   :description "Validate a Factorio mod's structure, load order, info.json, and dependencies. Returns diagnostics with severity, scope, and category."
   :inputSchema {:type       "object"
                 :properties {:path {:type        "string"
                                     :description "Path to the mod directory"}}
                 :required   [:path]}
   :tool-fn     (fn [_context arguments]
                  (-> (p/let [mod-data (mod/read-mod-dir (:path arguments))
                              diagnostics (validate/validate-mod mod-data)]
                        {:content [{:type "text"
                                    :text (pr-str {:valid? (not (some #(= :error (:severity %)) diagnostics))
                                                   :diagnostics diagnostics})}]
                         :isError false})
                      (p/catch (fn [err]
                                 {:content [{:type "text"
                                             :text (str "Validation error: " (ex-message err))}]
                                  :isError true}))))})

(def parse-lua-tool
  {:name        "parse-lua"
   :description "Parse a Lua source file and return its AST."
   :inputSchema {:type       "object"
                 :properties {:source {:type        "string"
                                       :description "Lua source code to parse"}}
                 :required   [:source]}
   :tool-fn     (fn [_context arguments]
                  (-> (p/let [ast (lua/parse (:source arguments))]
                        {:content [{:type "text"
                                    :text (pr-str ast)}]
                         :isError false})
                      (p/catch (fn [err]
                                 {:content [{:type "text"
                                             :text (str "Parse error: " (ex-message err))}]
                                  :isError true}))))})

;; ---------------------------------------------------------------------------
;; Session & context
;; ---------------------------------------------------------------------------

(def session
  (atom
    (server/create-session
      {:server-info {:name    "factorio-mod-tool"
                     :version "0.1.0"}
       :tools       [validate-mod-tool
                     parse-lua-tool]})))

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

(defn main [& _args]
  (js/process.stdin.setEncoding "utf8")
  (js/process.stdout.setEncoding "utf8")
  (js/process.stdin.on "data" handle-stdin-data)
  (js/process.stdin.on "end" (fn [] (js/process.exit 0)))
  (js/process.stderr.write "factorio-mod-tool MCP server started\n"))
