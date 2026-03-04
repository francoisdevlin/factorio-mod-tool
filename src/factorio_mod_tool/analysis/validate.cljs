(ns factorio-mod-tool.analysis.validate
  "Mod structure and load-order validation.

  Validates that a Factorio mod has the required files, correct structure,
  and proper load order. Emits diagnostics in the new multi-scope format."
  (:require [factorio-mod-tool.analysis.diagnostic :as diag]))

;; -- Required files for a valid Factorio mod --

(def required-files
  "Files that must exist in a valid Factorio mod."
  #{"info.json"})

(def standard-entry-points
  "Standard Lua entry points loaded by Factorio in order."
  ["settings.lua"
   "settings-updates.lua"
   "settings-final-fixes.lua"
   "data.lua"
   "data-updates.lua"
   "data-final-fixes.lua"
   "control.lua"])

;; -- Structure validation --

(defn validate-required-files
  "Check that all required files are present in the mod.
  `file-set` is a set of relative file paths in the mod."
  [file-set]
  (for [required required-files
        :when (not (contains? file-set required))]
    (diag/mod-diagnostic
     :missing-required-file
     :error
     :structure
     (str "Required file missing: " required)
     :suggestion (str "Create " required " in the mod root directory"))))

(defn validate-info-json
  "Validate info.json contents. `info` is the parsed info.json map."
  [info]
  (let [required-keys ["name" "version" "title" "author" "factorio_version"]]
    (for [k required-keys
          :when (not (get info k))]
      (diag/file-diagnostic
       (keyword (str "missing-info-" k))
       :error
       :structure
       "info.json"
       (str "info.json missing required field: " k)
       :suggestion (str "Add \"" k "\" to info.json")))))

;; -- Load order validation --

(defn validate-load-order
  "Check for load-order issues. Detects files that exist but may load
  in unexpected order due to missing intermediate files.
  `file-set` is a set of relative file paths in the mod."
  [file-set]
  (concat
     (when (and (contains? file-set "data-updates.lua")
                (not (contains? file-set "data.lua")))
       [(diag/file-diagnostic
         :data-updates-without-data
         :warning
         :load-order
         "data-updates.lua"
         "data-updates.lua exists but data.lua is missing"
         :suggestion "Create data.lua or rename data-updates.lua to data.lua")])
     (when (and (contains? file-set "data-final-fixes.lua")
                (not (contains? file-set "data.lua")))
       [(diag/file-diagnostic
         :data-final-fixes-without-data
         :warning
         :load-order
         "data-final-fixes.lua"
         "data-final-fixes.lua exists but data.lua is missing"
         :suggestion "Create data.lua to establish the base data stage")])
     (when (and (contains? file-set "settings-updates.lua")
                (not (contains? file-set "settings.lua")))
       [(diag/file-diagnostic
         :settings-updates-without-settings
         :warning
         :load-order
         "settings-updates.lua"
         "settings-updates.lua exists but settings.lua is missing"
         :suggestion "Create settings.lua or rename settings-updates.lua to settings.lua")])))

;; -- Dependency validation --

(defn validate-dependencies
  "Check declared dependencies in info.json for common issues.
  `info` is the parsed info.json map."
  [info]
  (let [deps (get info "dependencies" [])]
    (for [dep deps
          :when (and (string? dep)
                     (not (re-matches #"^[!?~]?\s*\S+.*$" dep)))]
      (diag/file-diagnostic
       :malformed-dependency
       :error
       :dependency
       "info.json"
       (str "Malformed dependency string: " (pr-str dep))
       :suggestion "Dependencies should be formatted as: \"mod-name >= 1.0.0\""))))

;; -- Cross-file validation --

(defn validate-require-consistency
  "Check for cross-file require consistency issues.
  `require-map` is a map of {file-path -> [required-paths]}."
  [require-map file-set]
  (for [[source-file requires] require-map
        req requires
        :when (not (contains? file-set req))]
    (diag/cross-file-diagnostic
     :require-missing-target
     :error
     :dependency
     [source-file req]
     (str source-file " requires " req " which does not exist in the mod")
     :suggestion (str "Create " req " or fix the require path"))))

;; -- Public API --

(defn validate-mod
  "Run all validation checks on a mod. Returns a sequence of diagnostics.

  `mod` is a map with:
    :files     — set of relative file paths in the mod
    :info      — parsed info.json contents (map), or nil if missing
    :requires  — optional map of {file -> [required-file-paths]}"
  [{:keys [files info requires]}]
  (let [file-set (set files)]
    (vec
     (concat
      (validate-required-files file-set)
      (when info
        (concat
         (validate-info-json info)
         (validate-dependencies info)))
      (validate-load-order file-set)
      (when requires
        (validate-require-consistency requires file-set))))))

(defn validate-mod-legacy
  "Run validation and return results in the legacy format for backward compatibility.
  Returns a seq of {:rule :severity :message :file :line}."
  [mod-data]
  (mapv diag/->legacy (validate-mod mod-data)))
