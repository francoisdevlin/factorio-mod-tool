(ns factorio-mod-tool.cli
  "CLI entry point for direct command-line usage without an MCP client."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [factorio-mod-tool.util.lua :as lua]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.analysis.validate :as validate]
            [factorio-mod-tool.analysis.diagnostic :as diag]))

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
;; Usage / dispatch
;; ---------------------------------------------------------------------------

(def usage-text
  "Usage: fmod <command> [args]

Commands:
  validate <mod-path>   Validate a Factorio mod directory
  parse <file.lua>      Parse a Lua file and print the AST
  parse -               Parse Lua from stdin
  lint <mod-path>       Run lint rules on a mod (not yet implemented)

Options:
  --help, -h            Show this help message

Examples:
  fmod validate ./my-mod
  fmod parse data.lua
  cat control.lua | fmod parse -")

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
                (print-err "Usage: fmt validate <mod-path>")
                (js/process.exit 1))
            (cmd-validate (first rest)))

          "parse"
          (cond
            (empty? rest)
            (do (print-err "Error: parse requires a file path or '-' for stdin")
                (print-err "Usage: fmt parse <file.lua>")
                (js/process.exit 1))

            (= "-" (first rest))
            (cmd-parse-stdin)

            :else
            (cmd-parse-file (first rest)))

          "lint"
          (if (empty? rest)
            (do (print-err "Error: lint requires a mod path")
                (print-err "Usage: fmt lint <mod-path>")
                (js/process.exit 1))
            (cmd-lint (first rest)))

          ;; unknown command
          (do (print-err (str "Unknown command: " cmd))
              (print-usage)
              (js/process.exit 1)))))))
