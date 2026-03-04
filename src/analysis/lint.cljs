(ns analysis.lint
  "Linting rules for Factorio mod Lua files.

  Parses Lua files in a mod directory and runs configurable rules to detect
  common issues like deprecated API usage, missing locale strings, and
  data-lifecycle violations.

  Returns a vector of diagnostic maps with :rule, :severity, :message, :file, :line."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Rule definitions
;; ---------------------------------------------------------------------------

(def ^:private deprecated-apis
  "Map of deprecated Factorio API patterns to their replacements."
  {"game.player"             "game.players[index] or game.get_player()"
   "entity.count"            "entity.amount"
   "player.gui.left"         "player.gui.top or relative GUI"
   "game.get_surface"        "game.surfaces[name]"
   "entity.last_user"        "entity.last_user (now LuaPlayer, not string)"})

(def ^:private data-stage-files
  "Set of filenames that run during the data stage."
  #{"data.lua" "data-updates.lua" "data-final-fixes.lua"})

(def ^:private data-lifecycle-violations
  "Patterns that indicate data-lifecycle violations in Factorio mods."
  [;; data:extend used outside data stage
   {:pattern #"data:extend"
    :check   :outside-data-stage
    :message "data:extend should only be used in data.lua, data-updates.lua, or data-final-fixes.lua"
    :rule    :data-lifecycle/extend-outside-data-stage}

   ;; game object accessed in data stage files
   {:pattern #"\bgame\."
    :check   :inside-data-stage
    :message "`game` object is not available during data stage"
    :rule    :data-lifecycle/game-in-data-stage}

   ;; script.on_event in data stage files
   {:pattern #"\bscript\.on_event\b"
    :check   :inside-data-stage
    :message "script.on_event is not available during data stage"
    :rule    :data-lifecycle/event-in-data-stage}])

(def ^:private locale-key-pattern
  "Pattern matching Lua strings that look like locale keys."
  #"\{\"([^\"]+)\"\}")

;; ---------------------------------------------------------------------------
;; File discovery
;; ---------------------------------------------------------------------------

(defn- lua-files
  "Recursively find all .lua files under `dir`."
  [dir]
  (when (fs/existsSync dir)
    (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
      (reduce
       (fn [acc entry]
         (let [full-path (path/join dir (.-name entry))]
           (cond
             (.isDirectory entry)
             (into acc (lua-files full-path))

             (str/ends-with? (.-name entry) ".lua")
             (conj acc full-path)

             :else acc)))
       []
       entries))))

(defn- read-lines
  "Read a file and return a vector of [line-number line-text] pairs (1-indexed)."
  [file-path]
  (let [content (fs/readFileSync file-path "utf8")
        lines   (str/split content #"\n")]
    (map-indexed (fn [i line] [(inc i) line]) lines)))

;; ---------------------------------------------------------------------------
;; Rule runners
;; ---------------------------------------------------------------------------

(defn- check-deprecated-apis
  "Check a file for deprecated Factorio API usage."
  [file-path lines]
  (reduce
   (fn [diagnostics [line-num line-text]]
     (reduce
      (fn [diags [deprecated replacement]]
        (if (str/includes? line-text deprecated)
          (conj diags {:rule     :deprecated-api
                       :severity :warning
                       :message  (str "Deprecated API `" deprecated "` — use " replacement " instead")
                       :file     file-path
                       :line     line-num})
          diags))
      diagnostics
      deprecated-apis))
   []
   lines))

(defn- check-data-lifecycle
  "Check a file for data-lifecycle violations."
  [file-path lines]
  (let [filename      (path/basename file-path)
        is-data-file? (contains? data-stage-files filename)]
    (reduce
     (fn [diagnostics rule-def]
       (let [{:keys [pattern check message rule]} rule-def
             applicable? (case check
                           :inside-data-stage  is-data-file?
                           :outside-data-stage (not is-data-file?)
                           true)]
         (if applicable?
           (reduce
            (fn [diags [line-num line-text]]
              (if (re-find pattern line-text)
                (conj diags {:rule     rule
                             :severity :error
                             :message  message
                             :file     file-path
                             :line     line-num})
                diags))
            diagnostics
            lines)
           diagnostics)))
     []
     data-lifecycle-violations)))

(defn- check-missing-locale
  "Check a file for locale keys that might be missing from locale files."
  [file-path lines locale-keys]
  (reduce
   (fn [diagnostics [line-num line-text]]
     (let [matches (re-seq locale-key-pattern line-text)]
       (reduce
        (fn [diags [_ key]]
          (if (and key (not (contains? locale-keys key)))
            (conj diags {:rule     :missing-locale
                         :severity :warning
                         :message  (str "Locale key `" key "` not found in locale files")
                         :file     file-path
                         :line     line-num})
            diags))
        diagnostics
        matches)))
   []
   lines))

;; ---------------------------------------------------------------------------
;; Locale key loading
;; ---------------------------------------------------------------------------

(defn- cfg-files
  "Find all .cfg files under `dir` recursively."
  [dir]
  (when (fs/existsSync dir)
    (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
      (reduce
       (fn [acc entry]
         (let [full-path (path/join dir (.-name entry))]
           (cond
             (.isDirectory entry)
             (into acc (cfg-files full-path))

             (str/ends-with? (.-name entry) ".cfg")
             (conj acc full-path)

             :else acc)))
       []
       entries))))

(defn- load-locale-keys
  "Load all locale keys from .cfg files in the mod's locale directory."
  [mod-dir]
  (let [locale-dir (path/join mod-dir "locale")
        files      (cfg-files locale-dir)]
    (reduce
     (fn [keys file]
       (let [content (fs/readFileSync file "utf8")
             lines   (str/split content #"\n")]
         (reduce
          (fn [ks line]
            (if-let [match (re-find #"^([^=\[#;]+)=" line)]
              (conj ks (second match))
              ks))
          keys
          lines)))
     #{}
     (or files []))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(def default-rules
  "Default set of lint rules to run."
  #{:deprecated-api :data-lifecycle :missing-locale})

(defn lint-mod
  "Lint a Factorio mod directory.

  Options:
    :mod-dir  - Path to the mod directory (required)
    :rules    - Set of rule keywords to run (default: all rules)

  Returns a vector of diagnostic maps, each with:
    :rule     - Keyword identifying the lint rule
    :severity - :error or :warning
    :message  - Human-readable description
    :file     - Path to the file
    :line     - Line number (1-indexed)"
  [{:keys [mod-dir rules] :or {rules default-rules}}]
  (let [files       (lua-files mod-dir)
        locale-keys (when (:missing-locale rules)
                      (load-locale-keys mod-dir))]
    (reduce
     (fn [all-diagnostics file]
       (let [lines (read-lines file)
             diags (cond-> []
                     (:deprecated-api rules)
                     (into (check-deprecated-apis file lines))

                     (:data-lifecycle rules)
                     (into (check-data-lifecycle file lines))

                     (:missing-locale rules)
                     (into (check-missing-locale file lines locale-keys)))]
         (into all-diagnostics diags)))
     []
     (or files []))))
