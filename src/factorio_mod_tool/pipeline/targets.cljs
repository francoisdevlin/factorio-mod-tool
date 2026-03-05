(ns factorio-mod-tool.pipeline.targets
  "Built-in pipeline target implementations.
   Each target returns a promise of {:exit 0|1 :stdout string? :stderr string?}
   matching the contract expected by the pipeline runner."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.util.lua :as lua]
            [factorio-mod-tool.analysis.diagnostic :as diag]
            [factorio-mod-tool.analysis.lint :as lint]
            [factorio-mod-tool.rcon.client :as rcon]
            [factorio-mod-tool.testing.harness :as harness]
            [factorio-mod-tool.bundle.pack :as pack]))

;; ---------------------------------------------------------------------------
;; Target: check (offline Lua syntax via luaparse)
;; ---------------------------------------------------------------------------

(defn- check-file-offline [file-path]
  (-> (p/let [source (fs/read-file file-path)
              _ast (lua/parse source)]
        {:file file-path :status :ok})
      (p/catch (fn [err]
                 {:file file-path :status :error :message (ex-message err)}))))

(defn target-check
  "Check all Lua files in the mod for syntax errors using luaparse.
   Returns {:exit 0|1 :results [...]}."
  [{:keys [mod-path]}]
  (p/let [lua-files (mod/list-lua-files mod-path)
          full-paths (mapv #(fs/join mod-path %) lua-files)
          results (p/all (mapv check-file-offline full-paths))
          errors (filter #(= :error (:status %)) results)]
    (doseq [{:keys [file status message]} results]
      (if (= status :ok)
        (println (str "  ✓ " file))
        (println (str "  ✗ " file ": " message))))
    {:exit (if (seq errors) 1 0) :results results}))

;; ---------------------------------------------------------------------------
;; Target: lint
;; ---------------------------------------------------------------------------

(defn target-lint
  "Run lint rules on the mod. Returns {:exit 0|1 :diagnostics [...]}."
  [{:keys [mod-path]}]
  (p/let [mod-data (mod/read-mod-dir mod-path)
          diagnostics (lint/lint-mod mod-data)
          errors (diag/errors diagnostics)]
    (if (empty? diagnostics)
      (println "  ✓ No lint issues found")
      (doseq [{:keys [severity message file suggestion]} diagnostics]
        (let [icon (case severity :error "✗" :warning "⚠" :info "ℹ" "?")]
          (println (str "  " icon " " message (when file (str " [" file "]"))))
          (when suggestion
            (println (str "    → " suggestion))))))
    {:exit (if (seq errors) 1 0) :diagnostics diagnostics}))

;; ---------------------------------------------------------------------------
;; Target: check-live (RCON validation)
;; ---------------------------------------------------------------------------

(defn- find-long-bracket-level [source]
  (loop [n 0]
    (let [close-bracket (str "]" (apply str (repeat n "=")) "]")]
      (if (.includes source close-bracket)
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

(defn- check-file-live [instance-name file-path]
  (-> (p/let [source (fs/read-file file-path)
              cmd (rcon-check-command source)
              response (rcon/exec instance-name cmd)
              trimmed (.trim response)]
        (if (= trimmed "OK")
          {:file file-path :status :ok}
          {:file file-path :status :error :message trimmed}))
      (p/catch (fn [err]
                 {:file file-path :status :error :message (ex-message err)}))))

(defn target-check-live
  "Check Lua files via RCON against a running Factorio instance.
   Returns {:exit 0|1 :results [...]}."
  [{:keys [mod-path rcon-opts]}]
  (let [instance "__pipeline-check__"
        rcon-config (or rcon-opts {})]
    (-> (p/let [_ (rcon/connect instance rcon-config)
                lua-files (mod/list-lua-files mod-path)
                full-paths (mapv #(fs/join mod-path %) lua-files)
                results (p/all (mapv #(check-file-live instance %) full-paths))
                errors (filter #(= :error (:status %)) results)]
          (rcon/disconnect instance)
          (doseq [{:keys [file status message]} results]
            (if (= status :ok)
              (println (str "  ✓ " file))
              (println (str "  ✗ " file ": " message))))
          {:exit (if (seq errors) 1 0) :results results})
        (p/catch (fn [err]
                   (let [msg (or (not-empty (ex-message err))
                                 (.-code err)
                                 "unknown error")]
                     (println (str "  ✗ RCON connection failed: " msg))
                     {:exit 1 :error msg}))))))

;; ---------------------------------------------------------------------------
;; Target: test
;; ---------------------------------------------------------------------------

(defn target-test
  "Run mod unit tests. Returns {:exit 0|1}."
  [{:keys [mod-path]}]
  (p/let [result (harness/run-tests mod-path)]
    (println (str "  " (:passed result) " passed, " (:failed result) " failed"))
    {:exit (if (pos? (:failed result)) 1 0)}))

;; ---------------------------------------------------------------------------
;; Target: pack
;; ---------------------------------------------------------------------------

(defn target-pack
  "Bundle mod into a distributable zip. Returns {:exit 0|1}.
   Reads structure.src, structure.dist, pack.exclude, name, and version from config."
  [{:keys [mod-path config]}]
  (let [dist-dir (or (get-in config [:structure :dist]) "dist")
        name (:name config)
        version (:version config)
        excludes (get-in config [:pack :exclude] [])
        output-path (fs/join dist-dir (str name "_" version ".zip"))]
    (p/let [result (pack/pack-mod mod-path output-path {:exclude excludes})]
      (println (str "  → " (:output-path result)))
      {:exit 0 :output-path (:output-path result)})))

;; ---------------------------------------------------------------------------
;; Target: deploy (stub)
;; ---------------------------------------------------------------------------

(defn target-deploy
  "Deploy mod to Factorio mods folder. Returns {:exit 0|1}."
  [_ctx]
  (println "  ✗ deploy is not yet implemented")
  (p/resolved {:exit 1 :error "not implemented"}))

;; ---------------------------------------------------------------------------
;; Target: test-live (stub)
;; ---------------------------------------------------------------------------

(defn target-test-live
  "Run live tests against a running Factorio instance. Returns {:exit 0|1}."
  [_ctx]
  (println "  ✗ test-live is not yet implemented")
  (p/resolved {:exit 1 :error "not implemented"}))

;; ---------------------------------------------------------------------------
;; Public: make-run-fn
;; ---------------------------------------------------------------------------

(def ^:private target-fns
  {:check      target-check
  :lint       target-lint
  :check-live target-check-live
  :test       target-test
  :pack       target-pack
  :deploy     target-deploy
  :test-live  target-test-live})

(defn make-run-fn
  "Create a run-fn closure for the pipeline runner.

   ctx is a map with:
     :mod-path   - path to the mod source directory
     :config     - parsed .fmod.json config map
     :rcon-opts  - RCON connection options (optional)

   Returns (fn [target-key] -> promise of {:exit 0|1 ...})"
  [ctx]
  (fn [target-key]
    (if-let [f (get target-fns target-key)]
      (f ctx)
      (do
        (println (str "  ✗ Unknown built-in target: " (name target-key)))
        (p/resolved {:exit 1 :error (str "unknown target: " (name target-key))})))))
