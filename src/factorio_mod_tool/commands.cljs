(ns factorio-mod-tool.commands
  "Unified command catalog. Each command is defined once with its name,
   description, input schema, and handler. Transport adapters (MCP, HTTP, WS)
   consume this catalog to expose commands through their respective protocols."
  (:require [promesa.core :as p]
            [factorio-mod-tool.state :as state]
            [factorio-mod-tool.util.lua :as lua]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.util.capabilities :as caps]
            [factorio-mod-tool.analysis.validate :as validate]
            [factorio-mod-tool.analysis.lint :as lint]
            [factorio-mod-tool.analysis.diagnostic :as diag]
            [factorio-mod-tool.rcon.client :as rcon]
            [factorio-mod-tool.bundle.pack :as pack]
            [factorio-mod-tool.repl :as repl]))

(defn- command
  "Create a command catalog entry."
  [name description input-schema handler]
  {:name         name
   :description  description
   :input-schema input-schema
   :handler      handler})

(def catalog
  "Canonical catalog of all commands. A vector of command maps."
  [(command
    "validate-mod"
    "Validate a Factorio mod's structure, load order, info.json, and dependencies. Returns diagnostics with severity, scope, and category."
    {:type       "object"
     :properties {:path {:type        "string"
                         :description "Path to the mod directory"}}
     :required   ["path"]}
    (fn [{:keys [path]}]
      (p/let [mod-data    (mod/read-mod-dir path)
              diagnostics (validate/validate-mod mod-data)]
        {:valid?      (not (diag/has-errors? diagnostics))
         :diagnostics diagnostics
         :counts      {:errors   (count (diag/errors diagnostics))
                       :warnings (count (diag/warnings diagnostics))
                       :total    (count diagnostics)}})))

   (command
    "parse-lua"
    "Parse a Lua source file and return its AST."
    {:type       "object"
     :properties {:source {:type        "string"
                           :description "Lua source code to parse"}}
     :required   ["source"]}
    (fn [{:keys [source]}]
      (p/let [ast (lua/parse source)]
        {:ast ast})))

   (command
    "lint-mod"
    "Run linting rules on a Factorio mod. Checks for deprecated API usage, missing locale strings, naming conventions, and data-lifecycle violations. Returns diagnostics with severity, scope, and category."
    {:type       "object"
     :properties {:path {:type        "string"
                         :description "Path to the mod directory"}}
     :required   ["path"]}
    (fn [{:keys [path]}]
      (p/let [mod-data    (mod/read-mod-dir path)
              diagnostics (lint/lint-mod mod-data)]
        {:diagnostics   diagnostics
         :count         (count diagnostics)
         :has-warnings? (boolean (seq (filter #(= :warning (:severity %)) diagnostics)))})))

   (command
    "pack-mod"
    "Bundle a Factorio mod directory into a distributable zip file. Creates modname_version.zip with the top-level directory structure Factorio expects."
    {:type       "object"
     :properties {:path       {:type        "string"
                               :description "Path to the mod directory"}
                  :output-dir {:type        "string"
                               :description "Directory to write the zip file to (defaults to current directory)"}
                  :exclude    {:type        "array"
                               :items       {:type "string"}
                               :description "Glob patterns of files to exclude from the zip"}}
     :required   ["path"]}
    (fn [{:keys [path output-dir exclude]}]
      (pack/pack-mod path (or output-dir ".") {:exclude (vec (or exclude []))})))

   (command
    "rcon-exec"
    "Execute a command on a connected Factorio instance via RCON."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of the RCON connection"}
                  :command  {:type        "string"
                             :description "Command to execute"}}
     :required   ["instance" "command"]}
    (fn [{:keys [instance command]}]
      (p/let [response (rcon/exec instance command)]
        {:response response})))

   (command
    "rcon-inspect"
    "Query game state from a connected Factorio instance via RCON."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of the RCON connection"}
                  :query    {:type        "string"
                             :description "Lua expression to evaluate (e.g. \"game.player.position\")"}}
     :required   ["instance" "query"]}
    (fn [{:keys [instance query]}]
      (p/let [result (rcon/inspect instance query)]
        result)))

   (command
    "repl-eval"
    "Evaluate Lua code against a running Factorio instance via REPL. Supports dot-commands: .entities, .recipes, .forces, .surface for structured inspection."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of the RCON connection"}
                  :code     {:type        "string"
                             :description "Lua code to evaluate (or dot-command like .entities)"}}
     :required   ["instance" "code"]}
    (fn [{:keys [instance code]}]
      (repl/eval-lua instance code)))

   (command
    "repl-history"
    "View REPL command history. Returns previous commands and their results."
    {:type       "object"
     :properties {:limit {:type        "number"
                          :description "Number of recent entries to return (default: all)"}}}
    (fn [{:keys [limit]}]
      (let [history (if limit
                      (repl/get-history limit)
                      (repl/get-history))]
        (p/resolved {:count   (count history)
                     :entries history}))))

   (command
    "repl-inspect"
    "Structured game state inspection. Query entities, recipes, forces, or surface state from a running Factorio instance."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of the RCON connection"}
                  :category {:type        "string"
                             :description "What to inspect: entities, recipes, forces, or surface"
                             :enum        ["entities" "recipes" "forces" "surface"]}
                  :filter   {:type        "string"
                             :description "Optional substring filter for results"}}
     :required   ["instance" "category"]}
    (fn [{:keys [instance category filter]}]
      (repl/inspect instance category filter)))

   (command
    "status"
    "Server health and connected Factorio instances."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved
       {:server           "factorio-mod-tool"
        :version          "0.1.0"
        :status           "running"
        :rcon-connections  (mapv (fn [[k v]]
                                  {:instance k
                                   :host     (:host v)
                                   :port     (:port v)})
                                @state/rcon-connections)})))

   (command
    "capabilities"
    "Detect availability of external tools (Lua, LuaRocks, Factorio, etc.)."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/let [capabilities (caps/detect-all)]
        {:capabilities
         (into {}
               (map (fn [[k v]] [(name k) v]))
               capabilities)})))

   (command
    "diagnostics"
    "Current diagnostics for loaded projects."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved
       {:mods (into {}
                    (map (fn [[path data]]
                           [path {:diagnostics (:diagnostics data)}]))
                    @state/mod-state)})))

   (command
    "ping"
    "System health check. Returns pong."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved {:pong true :timestamp (.toISOString (js/Date.))})))

   (command
    "get-preferences"
    "Get current user preferences."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved @state/preferences)))

   (command
    "set-preference"
    "Set a user preference. Broadcasts the change to all connected clients."
    {:type       "object"
     :properties {:key   {:type        "string"
                          :description "Preference key (e.g. \"theme\")"}
                  :value {:type        "string"
                          :description "Preference value (e.g. \"dark\", \"light\", \"factorio\")"}}
     :required   ["key" "value"]}
    (fn [{:keys [key value]}]
      (swap! state/preferences assoc (keyword key) value)
      (state/broadcast! {:type "preference-change"
                          :key  key
                          :value value})
      (p/resolved @state/preferences)))

   (command
    "rcon-heartbeat"
    "Send a heartbeat probe to a connected Factorio instance to check connection health."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of the RCON connection"}}
     :required   ["instance"]}
    (fn [{:keys [instance]}]
      (rcon/heartbeat instance)))

   (command
    "rcon-start-heartbeat"
    "Start periodic heartbeat monitoring for an RCON connection."
    {:type       "object"
     :properties {:instance    {:type        "string"
                                :description "Name of the RCON connection"}
                  :interval-ms {:type        "number"
                                :description "Heartbeat interval in milliseconds (default: 15000)"}}
     :required   ["instance"]}
    (fn [{:keys [instance interval-ms]}]
      (p/resolved
       (rcon/start-heartbeat! instance (when interval-ms {:interval-ms interval-ms})))))

   (command
    "rcon-stop-heartbeat"
    "Stop periodic heartbeat monitoring for an RCON connection."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of the RCON connection"}}
     :required   ["instance"]}
    (fn [{:keys [instance]}]
      (p/resolved (rcon/stop-heartbeat! instance))))

   (command
    "rcon-health"
    "Get connection health status for all RCON connections or a specific one."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of a specific RCON connection (optional, omit for all)"}}}
    (fn [{:keys [instance]}]
      (p/resolved
       (if instance
         (or (rcon/connection-health instance)
             {:error "Connection not found" :instance instance})
         {:connections (rcon/connection-health)}))))

   (command
    "check"
    "Check Lua files for syntax errors. Supports offline parsing or live validation against a running Factorio instance."
    {:type       "object"
     :properties {:files    {:type        "array"
                             :items       {:type "string"}
                             :description "Lua file paths to check"}
                  :source   {:type        "string"
                             :description "Lua source code to check (alternative to files)"}
                  :live     {:type        "boolean"
                             :description "If true, validate against a running Factorio instance via RCON"}
                  :instance {:type        "string"
                             :description "RCON instance name (used with live mode)"}}}
    (fn [{:keys [files source live instance]}]
      (cond
        source
        (-> (p/let [_ast (lua/parse source)]
              {:status :ok})
            (p/catch (fn [err]
                       {:status :error :message (ex-message err)})))

        (seq files)
        (let [check-fn (if live
                         (fn [f]
                           (-> (p/let [src (.. (js/require "fs") -promises (readFile f "utf8"))
                                       cmd (str "/silent-command local ok, err = load("
                                                (pr-str src) ") if not ok then rcon.print(err) else rcon.print('OK') end")
                                       response (rcon/exec (or instance "__check__") cmd)
                                       trimmed (.trim response)]
                                 {:file f :status (if (= trimmed "OK") :ok :error)
                                  :message (when (not= trimmed "OK") trimmed)})
                               (p/catch (fn [err]
                                          {:file f :status :error :message (ex-message err)}))))
                         (fn [f]
                           (-> (p/let [src (.. (js/require "fs") -promises (readFile f "utf8"))
                                       _ast (lua/parse src)]
                                 {:file f :status :ok})
                               (p/catch (fn [err]
                                          {:file f :status :error :message (ex-message err)})))))]
          (p/let [results (p/all (mapv check-fn files))]
            {:results results}))

        :else
        (p/resolved {:error "Missing required field: files or source"}))))])

(def catalog-by-name
  "Index of commands by name for O(1) lookup."
  (into {} (map (juxt :name identity)) catalog))

(defn find-command
  "Look up a command by name. Returns the command map or nil."
  [name]
  (get catalog-by-name name))
