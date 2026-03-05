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
            [factorio-mod-tool.analysis.validate :as validate]
            [factorio-mod-tool.analysis.lint :as lint]
            [factorio-mod-tool.rcon.client :as rcon]))

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

(def lint-mod-tool
  {:name        "lint-mod"
   :description "Run linting rules on a Factorio mod. Checks for deprecated API usage, missing locale strings, naming conventions, and data-lifecycle violations. Returns diagnostics with severity, scope, and category."
   :inputSchema {:type       "object"
                 :properties {:path {:type        "string"
                                     :description "Path to the mod directory"}}
                 :required   [:path]}
   :tool-fn     (fn [_context arguments]
                  (-> (p/let [mod-data    (mod/read-mod-dir (:path arguments))
                              diagnostics (lint/lint-mod mod-data)]
                        {:content [{:type "text"
                                    :text (pr-str {:diagnostics diagnostics
                                                   :count       (count diagnostics)
                                                   :has-warnings? (boolean (seq (filter #(= :warning (:severity %)) diagnostics)))})}]
                         :isError false})
                      (p/catch (fn [err]
                                 {:content [{:type "text"
                                             :text (str "Lint error: " (ex-message err))}]
                                  :isError true}))))})

(def rcon-exec-tool
  {:name        "rcon-exec"
   :description "Execute a command on a connected Factorio instance via RCON."
   :inputSchema {:type       "object"
                 :properties {:instance {:type        "string"
                                         :description "Name of the RCON connection"}
                              :command  {:type        "string"
                                         :description "Command to execute"}}
                 :required   [:instance :command]}
   :tool-fn     (fn [context arguments]
                  (-> (p/let [response (rcon/exec (:instance arguments)
                                                  (:command arguments))]
                        {:content [{:type "text"
                                    :text response}]
                         :isError false})
                      (p/catch (fn [err]
                                 {:content [{:type "text"
                                             :text (str "RCON error: " (ex-message err))}]
                                  :isError true}))))})

(def rcon-inspect-tool
  {:name        "rcon-inspect"
   :description "Query game state from a connected Factorio instance via RCON."
   :inputSchema {:type       "object"
                 :properties {:instance {:type        "string"
                                         :description "Name of the RCON connection"}
                              :query    {:type        "string"
                                         :description "Lua expression to evaluate (e.g. \"game.player.position\")"}}
                 :required   [:instance :query]}
   :tool-fn     (fn [context arguments]
                  (-> (p/let [result (rcon/inspect (:instance arguments)
                                                   (:query arguments))]
                        {:content [{:type "text"
                                    :text (pr-str result)}]
                         :isError false})
                      (p/catch (fn [err]
                                 {:content [{:type "text"
                                             :text (str "RCON error: " (ex-message err))}]
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
                     parse-lua-tool
                     lint-mod-tool
                     rcon-exec-tool
                     rcon-inspect-tool]})))

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
