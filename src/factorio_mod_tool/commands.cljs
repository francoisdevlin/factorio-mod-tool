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
            [factorio-mod-tool.rcon.queries :as rcon-queries]
            [factorio-mod-tool.bundle.pack :as pack]
            [factorio-mod-tool.repl :as repl]
            [factorio-mod-tool.scanner :as scanner]
            [factorio-mod-tool.util.config :as config]
            [factorio-mod-tool.util.fs :as fs]))

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
        :port             @state/server-port
        :started-at       @state/server-started-at
        :ws-client-count  (count @state/ws-clients)
        :rcon-connections  (mapv (fn [[k v]]
                                  {:instance       k
                                   :host           (:host v)
                                   :port           (:port v)
                                   :status         (:status v)
                                   :last-query-at  (:last-query-at v)})
                                (get-in @state/app-state [:connection :instances]))})))

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
                    (get-in @state/app-state [:project :mods]))})))

   (command
    "ping"
    "System health check. Returns pong."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved {:pong true :timestamp (.toISOString (js/Date.))})))

   (command
    "get-preferences"
    "Get current user preferences (theme, density, layout, etc.)."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved (state/get-preferences))))

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
      (state/set-preference! (keyword key) value)
      (p/resolved (state/get-preferences))))

   (command
    "update-preferences"
    "Update user preferences. Merged with existing preferences and persisted to disk."
    {:type       "object"
     :properties {:theme        {:type "string"
                                 :description "Theme: light, dark, or factorio"
                                 :enum ["light" "dark" "factorio"]}
                  :density      {:type "string"
                                 :description "UI density: compact, comfortable, or spacious"
                                 :enum ["compact" "comfortable" "spacious"]}
                  :layout       {:type "object"
                                 :description "Layout preferences"}
                  :default-paths {:type "object"
                                  :description "Default paths configuration"}
                  :editor       {:type "object"
                                 :description "Editor settings"}}}
    (fn [params]
      (let [prefs (cond-> params
                    (string? (:theme params))    (update :theme keyword)
                    (string? (:density params))  (update :density keyword))]
        (state/update-preferences! prefs)
        (p/resolved (state/get-preferences)))))

   (command
    "get-state"
    "Get a snapshot of the full server app state (project, connection, preferences)."
    {:type "object"
     :properties {}}
    (fn [_params]
      (let [state @state/app-state
            ;; Strip opaque RCON connection objects from the response
            sanitized (update-in state [:connection :instances]
                        (fn [instances]
                          (into {} (map (fn [[k v]] [k (dissoc v :conn)])) instances)))]
        (p/resolved sanitized))))

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
        (p/resolved {:error "Missing required field: files or source"}))))

   ;; ----- RCON query protocol commands -----

   (command
    "rcon-query"
    "Execute a structured RCON query against a live Factorio server. Supports categories: prototypes, entities, recipes, technology, forces, surfaces, blueprints. Returns parsed JSON data."
    {:type       "object"
     :properties {:instance {:type        "string"
                             :description "Name of the RCON connection"}
                  :category {:type        "string"
                             :description "Query category"
                             :enum        ["prototypes" "entities" "recipes" "technology" "forces" "surfaces" "blueprints"]}
                  :params   {:type        "object"
                             :description "Category-specific parameters (see rcon-query-catalog for details)"}}
     :required   ["instance" "category"]}
    (fn [{:keys [instance category params]}]
      (rcon-queries/execute-query instance (keyword category) params)))

   (command
    "rcon-query-catalog"
    "List all available RCON query categories with their parameters and response shapes."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved {:categories (rcon-queries/list-categories)})))

   (command
    "open-project"
    "Open a project directory as the active context. Reads .fmod.json config, populates the file tree, starts the periodic directory scanner, and sets the working mod path for all tools."
    {:type       "object"
     :properties {:path {:type        "string"
                         :description "Path to the project directory"}}
     :required   ["path"]}
    (fn [{:keys [path]}]
      (p/let [result (state/open-project! path)]
        (scanner/start-scanner!)
        {:path      (:path result)
         :mod-path  (:mod-path result)
         :has-config (some? (:config result))
         :file-count (count (get-in result [:mod-data :files]))
         :rcon      (get-in result [:config :rcon])})))

   (command
    "get-project"
    "Get the current project state including path, config, and file tree."
    {:type "object"
     :properties {}}
    (fn [_params]
      (p/resolved
       (let [project (:project @state/app-state)]
         {:current-path (:current-path project)
          :config       (:config project)
          :has-project  (some? (:current-path project))
          :file-tree    (:file-tree project)}))))

   (command
    "update-file-tree"
    "Update the project file tree in state. Used internally by the directory scanner to push file tree changes through the command queue for unified state management and WebSocket broadcast."
    {:type       "object"
     :properties {:tree {:type        "array"
                         :description "Nested file tree structure from the scanner"}}
     :required   ["tree"]}
    (fn [{:keys [tree]}]
      (swap! state/app-state assoc-in [:project :file-tree] tree)
      (p/resolved {:updated true
                   :entry-count (count tree)})))

   (command
    "reload-config"
    "Reload .fmod.json configuration from disk. Triggered automatically when the scanner detects the config file has changed. Updates project config in state and reconnects RCON if settings changed."
    {:type       "object"
     :properties {:path {:type        "string"
                         :description "Path to the project directory"}}
     :required   ["path"]}
    (fn [{:keys [path]}]
      (let [old-config (get-in @state/app-state [:project :config])]
        (-> (p/let [{:keys [config config-path]} (config/read-config path)]
              (swap! state/app-state
                     (fn [s]
                       (-> s
                           (assoc-in [:project :config] config)
                           (assoc-in [:project :config-path] config-path))))
              (let [old-rcon (:rcon old-config)
                    new-rcon (:rcon config)
                    rcon-changed? (or (not= (:host old-rcon) (:host new-rcon))
                                      (not= (:port old-rcon) (:port new-rcon)))]
                {:reloaded    true
                 :config-path config-path
                 :rcon-changed rcon-changed?}))
            (p/catch (fn [err]
                       (js/process.stderr.write
                        (str "Config reload failed: " (ex-message err) "\n"))
                       {:reloaded false
                        :error    (ex-message err)}))))))

   (command
    "list-files"
    "List all files in the currently opened project directory. Returns the scanned file tree with relative paths, types, mtimes, and sizes. The tree is maintained by a periodic background scanner."
    {:type "object"
     :properties {}}
    (fn [_params]
      (scanner/list-files)))

   (command
    "read-file"
    "Read the contents of a file in the currently opened project. Takes a relative path and returns the file content with metadata. Images return base64 data; binary files return a placeholder."
    {:type       "object"
     :properties {:path {:type        "string"
                         :description "Relative file path within the project directory"}}
     :required   ["path"]}
    (fn [{:keys [path]}]
      (let [project-path (state/current-project-path)
            ext          (let [dot-idx (.lastIndexOf path ".")]
                           (when (pos? dot-idx)
                             (.toLowerCase (.substring path dot-idx))))
            image-exts   #{".png" ".jpg" ".jpeg" ".gif" ".bmp" ".svg" ".ico" ".webp"}
            binary-exts  #{".zip" ".tar" ".gz" ".dat" ".bin" ".exe" ".dll" ".so"
                           ".woff" ".woff2" ".ttf" ".otf" ".mp3" ".wav" ".ogg"
                           ".mp4" ".avi" ".mov"}
            ext->mime    {".png" "image/png" ".jpg" "image/jpeg" ".jpeg" "image/jpeg"
                          ".gif" "image/gif" ".bmp" "image/bmp" ".svg" "image/svg+xml"
                          ".ico" "image/x-icon" ".webp" "image/webp"}
            file-type    (cond
                           (contains? image-exts ext) :image
                           (contains? binary-exts ext) :binary
                           :else :text)]
        (if-not project-path
          (p/rejected (ex-info "No project open" {}))
          (let [abs-path   (fs/resolve-path project-path path)
                normalized (fs/resolve-path abs-path)]
            ;; Prevent path traversal outside project directory
            (if-not (.startsWith normalized project-path)
              (p/rejected (ex-info "Path outside project directory" {:path path}))
              (-> (p/let [st (fs/stat abs-path)
                          content (case file-type
                                    :image  (fs/read-file-base64 abs-path)
                                    :binary nil
                                    (fs/read-file abs-path))]
                    (cond-> {:path      path
                             :file-type (name file-type)
                             :mtime     (.toISOString (.-mtime st))
                             :size      (.-size st)}
                      (= file-type :text)  (assoc :content content)
                      (= file-type :image) (assoc :content content
                                                  :mime-type (get ext->mime ext "image/png"))))
                  (p/catch (fn [err]
                             (throw (ex-info (str "Failed to read file: " (ex-message err))
                                             {:path path})))))))))))

   (command
    "check-lua-live"
    "Send a Lua file from the project to a running Factorio instance via RCON and check if it loads cleanly. Returns OK or the error message from Factorio's Lua runtime."
    {:type       "object"
     :properties {:path     {:type        "string"
                             :description "Relative file path within the project (must be a .lua file)"}
                  :instance {:type        "string"
                             :description "RCON instance name (default: __project__)"}}
     :required   ["path"]}
    (fn [{:keys [path instance]}]
      (let [project-path (state/current-project-path)
            inst         (or instance "__project__")]
        (if-not project-path
          (p/rejected (ex-info "No project open" {}))
          (let [abs-path   (fs/resolve-path project-path path)
                normalized (fs/resolve-path abs-path)]
            (if-not (.startsWith normalized project-path)
              (p/rejected (ex-info "Path outside project directory" {:path path}))
              (-> (p/let [src      (fs/read-file abs-path)
                          lua-cmd  (str "/silent-command "
                                        "local ok, err = load("
                                        (pr-str src)
                                        ") if not ok then rcon.print('ERROR: ' .. err) "
                                        "else rcon.print('OK') end")
                          response (rcon/exec inst lua-cmd)
                          trimmed  (.trim (str response))]
                    {:file   path
                     :status (if (.startsWith trimmed "OK") :ok :error)
                     :result trimmed})
                  (p/catch (fn [err]
                             {:file    path
                              :status  :error
                              :result  (ex-message err)})))))))))])

(def catalog-by-name
  "Index of commands by name for O(1) lookup."
  (into {} (map (juxt :name identity)) catalog))

(defn find-command
  "Look up a command by name. Returns the command map or nil."
  [name]
  (get catalog-by-name name))
