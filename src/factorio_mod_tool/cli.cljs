(ns factorio-mod-tool.cli
  "CLI entry point for direct command-line usage without an MCP client."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [factorio-mod-tool.util.lua :as lua]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.analysis.validate :as validate]
            [factorio-mod-tool.analysis.diagnostic :as diag]
            [factorio-mod-tool.rcon.client :as rcon]))

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
;; Commands
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

(defn cmd-lint [_mod-path]
  (print-err "lint is not yet implemented")
  (js/process.exit 1))

;; ---------------------------------------------------------------------------
;; Check command — offline (luaparse) and live (RCON) Lua validation
;; ---------------------------------------------------------------------------

(defn- find-long-bracket-level
  "Find a long bracket level N such that `]=*=]` with N equals doesn't appear in source."
  [source]
  (loop [n 0]
    (let [close-bracket (str "]" (apply str (repeat n "=")) "]")]
      (if (str/includes? source close-bracket)
        (recur (inc n))
        n))))

(defn- lua-long-string
  "Wrap source in Lua long bracket string [=[...]=] with auto-detected level."
  [source]
  (let [n (find-long-bracket-level source)
        eq (apply str (repeat n "="))]
    (str "[" eq "[" source "]" eq "]")))

(defn- rcon-check-command
  "Build the RCON command to validate Lua source via Factorio's load()."
  [source]
  (str "/silent-command local ok, err = load("
       (lua-long-string source)
       ") if not ok then rcon.print(err) else rcon.print('OK') end"))

(defn- check-file-offline
  "Check a single Lua file offline using luaparse. Returns a promise of
   {:file path :status :ok|:error :message string?}."
  [file-path]
  (-> (p/let [source (fs/read-file file-path)
              _ast (lua/parse source)]
        {:file file-path :status :ok})
      (p/catch (fn [err]
                 {:file file-path :status :error :message (ex-message err)}))))

(defn- check-file-live
  "Check a single Lua file via RCON using Factorio's load(). Returns a promise of
   {:file path :status :ok|:error :message string?}."
  [instance-name file-path]
  (-> (p/let [source (fs/read-file file-path)
              cmd (rcon-check-command source)
              response (rcon/exec instance-name cmd)
              trimmed (str/trim response)]
        (if (= trimmed "OK")
          {:file file-path :status :ok}
          {:file file-path :status :error :message trimmed}))
      (p/catch (fn [err]
                 {:file file-path :status :error :message (ex-message err)}))))

(defn- print-check-results
  "Print check results in ✓/✗ format and exit with appropriate code."
  [results]
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
   a running Factorio instance. Without --live, uses luaparse offline."
  [opts files]
  (if (:live opts)
    ;; Live mode: connect via RCON, check each file, disconnect
    (let [instance "__check__"
          rcon-opts (cond-> {}
                      (:host opts) (assoc :host (:host opts))
                      (:port opts) (assoc :port (:port opts))
                      (:password opts) (assoc :password (:password opts)))]
      (-> (p/let [_ (rcon/connect instance rcon-opts)
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
    ;; Offline mode: use luaparse
    (-> (p/let [results (p/all (mapv check-file-offline files))]
          (println "Checking (offline via luaparse):")
          (println)
          (print-check-results results))
        (p/catch (fn [err]
                   (print-err "Error: " (ex-message err))
                   (js/process.exit 1))))))

;; ---------------------------------------------------------------------------
;; Usage / dispatch
;; ---------------------------------------------------------------------------

(def usage-text
  "Usage: fmod <command> [args]

Commands:
  validate <mod-path>   Validate a Factorio mod directory
  parse <file.lua>      Parse a Lua file and print the AST
  parse -               Parse Lua from stdin
  check <files...>      Check Lua files for syntax errors (offline)
  check --live <files>  Check Lua files via RCON against running Factorio
  lint <mod-path>       Run lint rules on a mod (not yet implemented)

Check options:
  --live                Validate via RCON against a running Factorio instance
  --host <host>         RCON host (default: localhost)
  --port <port>         RCON port (default: 27015)
  --password <pass>     RCON password

General options:
  --help, -h            Show this help message

Examples:
  fmod validate ./my-mod
  fmod parse data.lua
  fmod check data.lua control.lua
  fmod check --live --host localhost --port 27015 --password secret *.lua")

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
          (if (empty? rest)
            (do (print-err "Error: validate requires a mod path")
                (print-err "Usage: fmod validate <mod-path>")
                (js/process.exit 1))
            (cmd-validate (first rest)))

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
          (let [;; Parse flags from rest args
                parse-check-args
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
            (if (empty? files)
              (do (print-err "Error: check requires at least one Lua file")
                  (print-err "Usage: fmod check [--live] <file.lua> [...]")
                  (js/process.exit 1))
              (cmd-check opts files)))

          "lint"
          (if (empty? rest)
            (do (print-err "Error: lint requires a mod path")
                (print-err "Usage: fmod lint <mod-path>")
                (js/process.exit 1))
            (cmd-lint (first rest)))

          ;; unknown command
          (do (print-err (str "Unknown command: " cmd))
              (print-usage)
              (js/process.exit 1)))))))
