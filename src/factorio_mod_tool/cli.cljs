(ns factorio-mod-tool.cli
  "CLI entry point for direct command-line usage without an MCP client."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [factorio-mod-tool.util.lua :as lua]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.util.capabilities :as caps]
            [factorio-mod-tool.util.config :as config]
            [factorio-mod-tool.analysis.validate :as validate]
            [factorio-mod-tool.analysis.diagnostic :as diag]
            [factorio-mod-tool.rcon.client :as rcon]
            [factorio-mod-tool.repl :as repl]
            [factorio-mod-tool.scaffold :as scaffold]
            [factorio-mod-tool.pipeline.dag :as dag]
            [factorio-mod-tool.pipeline.runner :as runner]
            [factorio-mod-tool.pipeline.targets :as targets]
            [factorio-mod-tool.http.server :as http-server]))

;; ---------------------------------------------------------------------------
;; Output formatting
;; ---------------------------------------------------------------------------

(def ^:private severity-icons
  {:error   "✗"
   :warning "⚠"
   :info    "ℹ"})

(defn- format-diagnostic [{:keys [severity message file suggestion]}]
  (let [icon (get severity-icons severity "?")
        loc  (when file (str " [" file "]"))]
    (str "  " icon " " message loc
         (when suggestion (str "\n    → " suggestion)))))

(defn- print-err [& args]
  (js/process.stderr.write (str (apply str args) "\n")))

;; ---------------------------------------------------------------------------
;; Standalone commands (not pipeline targets)
;; ---------------------------------------------------------------------------

(defn cmd-validate [mod-path]
  (-> (p/let [mod-data (mod/read-mod-dir mod-path)
              diagnostics (validate/validate-mod mod-data)
              errors (diag/errors diagnostics)
              warnings (diag/warnings diagnostics)]
        (println (str "Validating: " mod-path))
        (println)
        (if (empty? diagnostics)
          (do
            (println "  ✓ No issues found")
            (js/process.exit 0))
          (do
            (doseq [d diagnostics]
              (println (format-diagnostic d)))
            (println)
            (println (str "  " (count errors) " error(s), "
                         (count warnings) " warning(s), "
                         (- (count diagnostics) (count errors) (count warnings)) " info"))
            (js/process.exit (if (seq errors) 1 0)))))
      (p/catch (fn [err]
                 (print-err "Error: " (ex-message err))
                 (js/process.exit 1)))))

(defn cmd-parse-file [file-path]
  (-> (p/let [source (fs/read-file file-path)
              ast (lua/parse source)]
        (println (js/JSON.stringify (clj->js ast) nil 2))
        (js/process.exit 0))
      (p/catch (fn [err]
                 (print-err "Parse error: " (ex-message err))
                 (js/process.exit 1)))))

(defn cmd-parse-stdin []
  (let [chunks (atom [])]
    (js/process.stdin.setEncoding "utf8")
    (js/process.stdin.on "data" (fn [chunk] (swap! chunks conj chunk)))
    (js/process.stdin.on "end"
      (fn []
        (let [source (str/join @chunks)]
          (-> (p/let [ast (lua/parse source)]
                (println (js/JSON.stringify (clj->js ast) nil 2))
                (js/process.exit 0))
              (p/catch (fn [err]
                         (print-err "Parse error: " (ex-message err))
                         (js/process.exit 1)))))))))

(defn cmd-new-project [project-name]
  (-> (p/let [result (scaffold/new-project project-name)]
        (println (str "Created new Factorio mod project: " (:name result)))
        (println (str "  " (:path result)))
        (println)
        (println "Project structure:")
        (println "  src/info.json")
        (println "  src/data.lua")
        (println "  src/control.lua")
        (println "  src/locale/en/locale.cfg")
        (println "  test/control_test.lua")
        (println "  .fmod.json")
        (println "  .gitignore")
        (println)
        (println "Next steps:")
        (println "  cd" project-name)
        (println "  fmod validate src/")
        (js/process.exit 0))
      (p/catch (fn [err]
                 (print-err "Error: " (ex-message err))
                 (js/process.exit 1)))))

;; ---------------------------------------------------------------------------
;; Doctor command — show capability detection status
;; ---------------------------------------------------------------------------

(defn cmd-doctor []
  (-> (p/let [capabilities (caps/detect-all)
              statuses (caps/format-status capabilities)]
        (println "fmod doctor — capability detection")
        (println)
        (doseq [{:keys [capability available detail install suggestion]} statuses]
          (if available
            (do
              (println (str "  OK " capability
                            (when (not-empty detail) (str " (" detail ")"))))
              (when suggestion
                (println (str "     Suggestion: " suggestion))))
            (println (str "  -- " capability " (not found)"
                          (when install (str "\n     " install))))))
        (println)
        (let [all-ok (every? :available statuses)]
          (if all-ok
            (println "  All capabilities detected.")
            (println "  Some capabilities are missing. Related pipeline targets will be skipped."))
          (js/process.exit 0)))
      (p/catch (fn [err]
                 (print-err "Error: " (ex-message err))
                 (js/process.exit 1)))))

;; ---------------------------------------------------------------------------
;; REPL command — interactive Lua REPL via RCON
;; ---------------------------------------------------------------------------

(defn- print-repl-result [{:keys [output parsed]}]
  (case (:type parsed)
    :empty   nil
    :error   (println (str "  ERROR: " (:message parsed)))
    :ok      (println (str "  " (:value parsed)))
    (when (not-empty output) (println (str "  " output)))))

(defn cmd-repl
  "Start an interactive REPL session connected to a running Factorio instance."
  [opts]
  (let [instance "__repl__"
        readline (js/require "readline")]
    (-> (p/let [cfg (-> (config/read-config)
                        (p/catch (fn [_] {:config {}})))
                cfg-rcon (get-in (:config cfg) [:rcon] {})
                rcon-opts (cond-> cfg-rcon
                            (:host opts) (assoc :host (:host opts))
                            (:port opts) (assoc :port (:port opts))
                            (:password opts) (assoc :password (:password opts)))
                _ (rcon/connect instance rcon-opts)]
          (println "Factorio REPL connected.")
          (println "  Type Lua code to evaluate. Special commands:")
          (println "  .entities  — list entity prototypes")
          (println "  .recipes   — list recipes")
          (println "  .forces    — list forces")
          (println "  .surface   — inspect current surface")
          (println "  .history   — show command history")
          (println "  .clear     — clear history")
          (println "  .exit      — quit REPL")
          (println)
          (let [rl (.createInterface readline
                     #js {:input  js/process.stdin
                          :output js/process.stdout
                          :prompt "factorio> "})]
            (.prompt rl)
            (.on rl "line"
              (fn [line]
                (let [trimmed (str/trim line)]
                  (cond
                    (or (= trimmed ".exit") (= trimmed ".quit"))
                    (do (println "Goodbye.")
                        (-> (rcon/disconnect instance)
                            (p/then (fn [_] (js/process.exit 0)))))

                    (= trimmed ".history")
                    (do (doseq [{:keys [input output]} (repl/get-history)]
                          (println (str "  > " input))
                          (when (not-empty output)
                            (println (str "    " output))))
                        (.prompt rl))

                    (= trimmed ".clear")
                    (do (repl/clear-history!)
                        (println "  History cleared.")
                        (.prompt rl))

                    (str/blank? trimmed)
                    (.prompt rl)

                    :else
                    (-> (p/let [result (repl/eval-lua instance trimmed)]
                          (print-repl-result result)
                          (.prompt rl))
                        (p/catch (fn [err]
                                   (println (str "  ERROR: " (ex-message err)))
                                   (.prompt rl))))))))
            (.on rl "close"
              (fn []
                (println)
                (-> (rcon/disconnect instance)
                    (p/then (fn [_] (js/process.exit 0))))))))
        (p/catch (fn [err]
                   (let [msg (or (not-empty (ex-message err))
                                 (.-code err)
                                 "unknown error")]
                     (print-err "RCON connection failed: " msg)
                     (print-err "Ensure Factorio is running with RCON enabled.")
                     (js/process.exit 1)))))))

;; ---------------------------------------------------------------------------
;; Check command — offline (luaparse) and live (RCON) Lua validation
;; ---------------------------------------------------------------------------

(defn- find-long-bracket-level [source]
  (loop [n 0]
    (let [close-bracket (str "]" (apply str (repeat n "=")) "]")]
      (if (str/includes? source close-bracket)
        (recur (inc n))
        n))))

(defn- lua-long-string [source]
  (let [n (find-long-bracket-level source)
        eq (apply str (repeat n "="))]
    (str "[" eq "[" source "]" eq "]")))

(defn- rcon-check-command [source]
  (str "/silent-command local ok, err = load("
       (lua-long-string source)
       ") if not ok then rcon.print(err) else rcon.print('OK') end"))

(defn- check-file-offline [file-path]
  (-> (p/let [source (fs/read-file file-path)
              _ast (lua/parse source)]
        {:file file-path :status :ok})
      (p/catch (fn [err]
                 {:file file-path :status :error :message (ex-message err)}))))

(defn- check-file-live [instance-name file-path]
  (-> (p/let [source (fs/read-file file-path)
              cmd (rcon-check-command source)
              response (rcon/exec instance-name cmd)
              trimmed (str/trim response)]
        (if (= trimmed "OK")
          {:file file-path :status :ok}
          {:file file-path :status :error :message trimmed}))
      (p/catch (fn [err]
                 {:file file-path :status :error :message (ex-message err)}))))

(defn- print-check-results [results]
  (let [errors (filter #(= :error (:status %)) results)]
    (doseq [{:keys [file status message]} results]
      (if (= status :ok)
        (println (str "  ✓ " file))
        (println (str "  ✗ " file ": " message))))
    (println)
    (println (str "  " (- (count results) (count errors)) " passed, "
                  (count errors) " failed"))
    (js/process.exit (if (seq errors) 1 0))))

(defn cmd-check
  "Check Lua files for syntax errors. With --live, validates via RCON against
   a running Factorio instance. Without --live, uses luaparse offline.
   For --live mode, reads rcon.host/port from .fmod.json config and
   FMOD_RCON_PASSWORD from env. CLI flags override config values."
  [opts files]
  (if (:live opts)
    (let [instance "__check__"]
      (-> (p/let [;; Read config for RCON defaults (best-effort — CLI flags override)
                  cfg (-> (config/read-config)
                          (p/catch (fn [_] {:config {}})))
                  cfg-rcon (get-in (:config cfg) [:rcon] {})
                  ;; CLI flags override config values
                  rcon-opts (cond-> cfg-rcon
                              (:host opts) (assoc :host (:host opts))
                              (:port opts) (assoc :port (:port opts))
                              (:password opts) (assoc :password (:password opts)))
                  _ (rcon/connect instance rcon-opts)
                  results (p/all (mapv #(check-file-live instance %) files))]
            (rcon/disconnect instance)
            (println "Checking (live via RCON):")
            (println)
            (print-check-results results))
          (p/catch (fn [err]
                     (let [msg (or (not-empty (ex-message err))
                                   (.-code err)
                                   "unknown error")]
                       (print-err "RCON connection failed: " msg)
                       (print-err "Ensure Factorio is running with RCON enabled.")
                       (js/process.exit 1))))))
    (-> (p/let [results (p/all (mapv check-file-offline files))]
          (println "Checking (offline via luaparse):")
          (println)
          (print-check-results results))
        (p/catch (fn [err]
                   (print-err "Error: " (ex-message err))
                   (js/process.exit 1))))))

;; ---------------------------------------------------------------------------
;; Pipeline dispatch
;; ---------------------------------------------------------------------------

(def ^:private dag-target-names
  "Set of target names that can be dispatched through the pipeline."
  (set (map name (keys dag/default-dag))))

(defn- run-pipeline
  "Run one or more targets through the pipeline DAG.
   Reads .fmod.json config, builds the DAG, and executes."
  [target-key & [{:keys [only from]}]]
  (-> (p/let [{:keys [config config-path]} (config/read-config)
              path-mod (js/require "path")
              project-root (.dirname path-mod config-path)
              mod-path (fs/join project-root (get-in config [:structure :src] "src"))
              custom-pipeline (:pipeline config)
              merged-dag (dag/merge-custom-targets dag/default-dag custom-pipeline)
              hooks (dag/merge-hooks custom-pipeline)
              plan (dag/execution-plan merged-dag target-key
                     (cond-> {}
                       only (assoc :only true)
                       from (assoc :from from)))
              rcon-opts (cond-> {}
                          (get-in config [:rcon :host]) (assoc :host (get-in config [:rcon :host]))
                          (get-in config [:rcon :port]) (assoc :port (get-in config [:rcon :port]))
                          (get-in config [:rcon :password]) (assoc :password (get-in config [:rcon :password])))
              ctx {:mod-path mod-path
                   :config config
                   :rcon-opts rcon-opts}
              run-fn (targets/make-run-fn ctx)
              result (runner/execute plan merged-dag hooks run-fn
                       {:on-start  (fn [t] (println (str "▸ " (name t))))
                        :on-finish (fn [t s] (println (str (if (= s :ok) "  ✓" "  ✗") " " (name t))))})]
        (println)
        (if (= :ok (:status result))
          (do
            (println (str "Pipeline complete: " (count (:completed result)) " targets"))
            (js/process.exit 0))
          (do
            (println (str "Pipeline failed at: " (name (:failed-target result))))
            (js/process.exit 1))))
      (p/catch (fn [err]
                 (print-err "Pipeline error: " (ex-message err))
                 (js/process.exit 1)))))

(defn- run-all-pipeline
  "Run all targets in the default pipeline."
  []
  (-> (p/let [{:keys [config config-path]} (config/read-config)
              path-mod (js/require "path")
              project-root (.dirname path-mod config-path)
              mod-path (fs/join project-root (get-in config [:structure :src] "src"))
              custom-pipeline (:pipeline config)
              merged-dag (dag/merge-custom-targets dag/default-dag custom-pipeline)
              hooks (dag/merge-hooks custom-pipeline)
              plan (dag/all-targets-plan merged-dag)
              rcon-opts (cond-> {}
                          (get-in config [:rcon :host]) (assoc :host (get-in config [:rcon :host]))
                          (get-in config [:rcon :port]) (assoc :port (get-in config [:rcon :port]))
                          (get-in config [:rcon :password]) (assoc :password (get-in config [:rcon :password])))
              ctx {:mod-path mod-path
                   :config config
                   :rcon-opts rcon-opts}
              run-fn (targets/make-run-fn ctx)
              result (runner/execute plan merged-dag hooks run-fn
                       {:on-start  (fn [t] (println (str "▸ " (name t))))
                        :on-finish (fn [t s] (println (str (if (= s :ok) "  ✓" "  ✗") " " (name t))))})]
        (println)
        (if (= :ok (:status result))
          (do
            (println (str "Pipeline complete: " (count (:completed result)) " targets"))
            (js/process.exit 0))
          (do
            (println (str "Pipeline failed at: " (name (:failed-target result))))
            (js/process.exit 1))))
      (p/catch (fn [err]
                 (print-err "Pipeline error: " (ex-message err))
                 (js/process.exit 1)))))

;; ---------------------------------------------------------------------------
;; Usage / dispatch
;; ---------------------------------------------------------------------------

(def usage-text
  "Usage: fmod <command> [args]

Commands:
  new-project <name>    Create a new Factorio mod project
  validate <mod-path>   Validate a Factorio mod directory
  parse <file.lua>      Parse a Lua file and print the AST
  parse -               Parse Lua from stdin
  check <files...>      Check Lua files for syntax errors (offline)
  check --live <files>  Check Lua files via RCON against running Factorio
  repl                  Interactive Lua REPL via RCON to running Factorio
  lint <mod-path>       Run lint rules on a mod (not yet implemented)
  serve [path]          Start HTTP + WebSocket server (optionally open a project)
  ui                    Open browser GUI (starts server if needed)
  doctor                Show detected capabilities and install guidance

Pipeline targets (run through DAG with dependencies):
  check                 Offline Lua syntax check (via .fmod.json config)
  lint                  Lint rules
  check-live            RCON Lua validation
  test                  Run mod unit tests
  pack                  Bundle mod into distributable zip
  deploy                Deploy mod to Factorio mods folder
  test-live             Run live tests against Factorio
  all                   Run full default pipeline

Pipeline options:
  --only                Run only the specified target (skip dependencies)
  --from <target>       Start pipeline from this target forward

Check options:
  --live                Validate via RCON against a running Factorio instance
  --host <host>         RCON host (default: localhost)
  --port <port>         RCON port (default: 27015) / HTTP server port (default: 3000)
  --password <pass>     RCON password
  --project <path>      Open a project directory on startup

General options:
  --help, -h            Show this help message

Examples:
  fmod validate ./my-mod
  fmod parse data.lua
  fmod check data.lua control.lua
  fmod check --live --host localhost --port 27015 --password secret *.lua
  fmod lint
  fmod pack
  fmod all
  fmod repl
  fmod repl --host localhost --port 27015 --password secret
  fmod new-project my-awesome-mod
  fmod serve ./my-mod
  fmod serve --project ./my-mod --port 8080")

(defn- print-usage []
  (println usage-text))

(defn main [& _]
  (let [args (vec (drop 2 (.-argv js/process)))]
    (cond
      (or (empty? args)
          (some #{"--help" "-h"} args))
      (do (print-usage)
          (js/process.exit 0))

      :else
      (let [cmd  (first args)
            rest (subvec args 1)]
        (case cmd
          "validate"
          (if (seq rest)
            (cmd-validate (first rest))
            ;; No path given: read structure.src from .fmod.json
            (-> (p/let [{:keys [config config-path]} (config/read-config)
                        path-mod (js/require "path")
                        project-root (.dirname path-mod config-path)
                        src-dir (get-in config [:structure :src] "src")]
                  (cmd-validate (fs/join project-root src-dir)))
                (p/catch (fn [err]
                           (print-err "Error: " (ex-message err))
                           (print-err "Usage: fmod validate <mod-path>")
                           (js/process.exit 1)))))

          "parse"
          (cond
            (empty? rest)
            (do (print-err "Error: parse requires a file path or '-' for stdin")
                (print-err "Usage: fmod parse <file.lua>")
                (js/process.exit 1))

            (= "-" (first rest))
            (cmd-parse-stdin)

            :else
            (cmd-parse-file (first rest)))

          "check"
          (let [parse-check-args
                (fn [args]
                  (loop [args args
                         opts {}
                         files []]
                    (if (empty? args)
                      [opts files]
                      (let [a (first args)
                            more (subvec (vec args) 1)]
                        (cond
                          (= a "--live")     (recur more (assoc opts :live true) files)
                          (= a "--host")     (recur (subvec (vec more) 1) (assoc opts :host (first more)) files)
                          (= a "--port")     (recur (subvec (vec more) 1) (assoc opts :port (js/parseInt (first more))) files)
                          (= a "--password") (recur (subvec (vec more) 1) (assoc opts :password (first more)) files)
                          :else              (recur more opts (conj files a)))))))
                [opts files] (parse-check-args rest)]
            (if (seq files)
              ;; Explicit file list: use direct check command (not pipeline)
              (cmd-check opts files)
              ;; No files: route through pipeline (uses .fmod.json config)
              (if (:live opts)
                (run-pipeline :check-live)
                (run-pipeline :check))))

          "lint"
          (if (and (seq rest) (not (str/starts-with? (first rest) "-")))
            ;; Legacy: fmod lint <mod-path> — direct command (not pipeline)
            (do (print-err "lint direct mode is not yet implemented")
                (js/process.exit 1))
            ;; No path: route through pipeline
            (run-pipeline :lint))

          "check-live"
          (run-pipeline :check-live)

          "test"
          (run-pipeline :test)

          "pack"
          (run-pipeline :pack)

          "deploy"
          (run-pipeline :deploy)

          "test-live"
          (run-pipeline :test-live)

          "all"
          (run-all-pipeline)

          "new-project"
          (if (empty? rest)
            (do (print-err "Error: new-project requires a project name")
                (print-err "Usage: fmod new-project <name>")
                (js/process.exit 1))
            (cmd-new-project (first rest)))

          "repl"
          (let [parse-repl-args
                (fn [args]
                  (loop [args args opts {}]
                    (if (empty? args)
                      opts
                      (let [a (first args)
                            more (subvec (vec args) 1)]
                        (cond
                          (= a "--host")     (recur (subvec (vec more) 1) (assoc opts :host (first more)))
                          (= a "--port")     (recur (subvec (vec more) 1) (assoc opts :port (js/parseInt (first more))))
                          (= a "--password") (recur (subvec (vec more) 1) (assoc opts :password (first more)))
                          :else              (recur more opts))))))]
            (cmd-repl (parse-repl-args rest)))

          "doctor"
          (cmd-doctor)

          "serve"
          (let [;; Parse --project flag or positional arg
                project-idx (.indexOf (to-array rest) "--project")
                project-path (cond
                               (and (>= project-idx 0) (< (inc project-idx) (count rest)))
                               (nth rest (inc project-idx))
                               ;; Support: fmod serve /path/to/mod (positional)
                               (and (seq rest) (not (str/starts-with? (first rest) "-")))
                               (first rest))]
            ;; Set --project in argv for server.cljs to pick up
            (when project-path
              (let [args (.-argv js/process)]
                (.push args "--project")
                (.push args project-path)))
            (http-server/main))

          "ui"
          (let [child-process (js/require "child_process")
                port-args (filter #(str/starts-with? % "--port") rest)
                port (if (seq port-args)
                       (js/parseInt (second rest))
                       3000)
                url (str "http://localhost:" port)]
            (-> (http-server/main)
                (p/then (fn []
                          (println (str "Opening browser at " url))
                          (let [cmd (case (.-platform js/process)
                                     "darwin" "open"
                                     "win32" "start"
                                     "xdg-open")]
                            (.exec child-process (str cmd " " url)))))))

          ;; Check if it's a known DAG target name
          (if (contains? dag-target-names cmd)
            (let [opts (cond-> {}
                         (some #{"--only"} rest) (assoc :only true)
                         (some #{"--from"} rest) (assoc :from (keyword (second (drop-while #(not= "--from" %) rest)))))]
              (run-pipeline (keyword cmd) opts))
            (do (print-err (str "Unknown command: " cmd))
                (print-usage)
                (js/process.exit 1))))))))

