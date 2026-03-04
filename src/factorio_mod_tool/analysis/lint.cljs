(ns factorio-mod-tool.analysis.lint
  "Linting rules for Factorio mod files.

  Applies style and best-practice checks to individual mod files,
  emitting diagnostics in the new multi-scope format."
  (:require [factorio-mod-tool.analysis.diagnostic :as diag]))

;; -- Lint rules --

(defn lint-info-json
  "Lint info.json for style and best-practice issues.
  `info` is the parsed info.json map."
  [info]
  (concat
   ;; Check for missing optional but recommended fields
   (when-not (get info "description")
     [(diag/file-diagnostic
       :missing-description
       :info
       :lint
       "info.json"
       "info.json has no \"description\" field"
       :suggestion "Add a description to help users understand what the mod does")])

   (when-not (get info "homepage")
     [(diag/file-diagnostic
       :missing-homepage
       :info
       :lint
       "info.json"
       "info.json has no \"homepage\" field"
       :suggestion "Add a homepage URL for your mod's documentation or source")])

   ;; Check for empty title
   (when (and (get info "title") (empty? (get info "title")))
     [(diag/file-diagnostic
       :empty-title
       :warning
       :lint
       "info.json"
       "info.json has an empty \"title\" field"
       :suggestion "Provide a meaningful title for your mod")])

   ;; Check version format
   (when-let [version (get info "version")]
     (when-not (re-matches #"\d+\.\d+\.\d+" version)
       [(diag/file-diagnostic
         :non-semver-version
         :warning
         :lint
         "info.json"
         (str "Version \"" version "\" does not follow major.minor.patch format")
         :suggestion "Use semantic versioning: major.minor.patch (e.g., 1.0.0)")]))))

(defn lint-factorio-version
  "Check factorio_version field for issues.
  `info` is the parsed info.json map."
  [info]
  (when-let [fv (get info "factorio_version")]
    (when-not (re-matches #"\d+\.\d+" fv)
      [(diag/file-diagnostic
        :invalid-factorio-version
        :warning
        :lint
        "info.json"
        (str "factorio_version \"" fv "\" should be in major.minor format")
        :suggestion "Use format: major.minor (e.g., 1.1)")])))

(defn lint-duplicate-dependencies
  "Check for duplicate entries in the dependencies list.
  `info` is the parsed info.json map."
  [info]
  (let [deps (get info "dependencies" [])
        ;; Extract mod names (strip prefix markers and version constraints)
        mod-names (map (fn [dep]
                         (when (string? dep)
                           (second (re-find #"^[!?~]?\s*(\S+)" dep))))
                       deps)
        freqs (frequencies (remove nil? mod-names))]
    (for [[mod-name cnt] freqs
          :when (> cnt 1)]
      (diag/file-diagnostic
       :duplicate-dependency
       :warning
       :lint
       "info.json"
       (str "Dependency \"" mod-name "\" is listed " cnt " times")
       :suggestion (str "Remove duplicate entries for " mod-name)))))

(defn lint-file-naming
  "Check for non-standard file naming conventions.
  `files` is a set of relative file paths in the mod."
  [files]
  (for [f files
        :when (and (or (.endsWith f ".lua") (.endsWith f ".json"))
                   (re-find #"[A-Z]" f)
                   ;; Exclude standard Factorio files that may use capitals
                   (not (#{"README.md" "CHANGELOG.md" "LICENSE"} f)))]
    (diag/file-diagnostic
     :uppercase-filename
     :info
     :lint
     f
     (str "File \"" f "\" contains uppercase characters")
     :suggestion "Factorio mods conventionally use lowercase file names")))

;; -- Public API --

(defn lint-mod
  "Run all lint checks on a mod. Returns a sequence of diagnostics.

  `mod` is a map with:
    :files — set of relative file paths in the mod
    :info  — parsed info.json contents (map), or nil if missing"
  [{:keys [files info]}]
  (vec
   (concat
    (when info
      (concat
       (lint-info-json info)
       (lint-factorio-version info)
       (lint-duplicate-dependencies info)))
    (when files
      (lint-file-naming (set files))))))

(defn lint-mod-legacy
  "Run linting and return results in the legacy format for backward compatibility.
  Returns a seq of {:rule :severity :message :file :line}."
  [mod-data]
  (mapv diag/->legacy (lint-mod mod-data)))
