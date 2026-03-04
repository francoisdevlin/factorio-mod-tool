(ns factorio-mod-tool.analysis.diagnostic
  "Diagnostic model for Factorio mod analysis.

  Diagnostics represent issues found during validation, linting, and
  cross-file analysis. The model supports three scopes:

    :file       — issue within a single file (has :file and optional :line)
    :mod        — issue with the mod as a whole (no specific file)
    :cross-file — issue spanning multiple files (has :files list)

  Categories classify the kind of issue:
    :structure   — mod file/directory structure problems
    :load-order  — script loading order issues
    :syntax      — parse or syntax errors
    :lint        — style and best-practice warnings
    :dependency  — missing or incompatible dependencies

  A diagnostic map has the following shape:

    {:rule        keyword       ; unique rule identifier, e.g. :missing-info-json
     :severity    keyword       ; :error, :warning, or :info
     :message     string        ; human-readable description
     :scope       keyword       ; :file, :mod, or :cross-file
     :category    keyword       ; :structure, :load-order, :syntax, :lint, :dependency
     :file        string|nil    ; source file (required for :file scope)
     :line        number|nil    ; line number (optional, :file scope only)
     :files       [string]|nil  ; related files (required for :cross-file scope)
     :suggestion  string|nil}   ; optional fix hint")

(def valid-scopes #{:file :mod :cross-file})
(def valid-severities #{:error :warning :info})
(def valid-categories #{:structure :load-order :syntax :lint :dependency})

(defn diagnostic
  "Create a diagnostic. Required keys: :rule, :severity, :message, :scope, :category.
  Additional keys depend on scope:
    :file scope       — :file required, :line optional
    :cross-file scope — :files required
    :mod scope        — no location keys required"
  [{:keys [rule severity message scope category file line files suggestion]}]
  (assert (keyword? rule) ":rule must be a keyword")
  (assert (valid-severities severity) (str ":severity must be one of " valid-severities))
  (assert (string? message) ":message must be a string")
  (assert (valid-scopes scope) (str ":scope must be one of " valid-scopes))
  (assert (valid-categories category) (str ":category must be one of " valid-categories))
  (case scope
    :file       (assert (string? file) ":file scope requires :file")
    :cross-file (assert (and (sequential? files) (seq files))
                        ":cross-file scope requires non-empty :files")
    :mod        nil)
  (cond-> {:rule rule
           :severity severity
           :message message
           :scope scope
           :category category}
    file       (assoc :file file)
    line       (assoc :line line)
    files      (assoc :files (vec files))
    suggestion (assoc :suggestion suggestion)))

(defn file-diagnostic
  "Convenience: create a file-scoped diagnostic."
  [rule severity category file message & {:keys [line suggestion]}]
  (diagnostic (cond-> {:rule rule
                       :severity severity
                       :message message
                       :scope :file
                       :category category
                       :file file}
                line       (assoc :line line)
                suggestion (assoc :suggestion suggestion))))

(defn mod-diagnostic
  "Convenience: create a mod-scoped diagnostic."
  [rule severity category message & {:keys [suggestion]}]
  (diagnostic (cond-> {:rule rule
                       :severity severity
                       :message message
                       :scope :mod
                       :category category}
                suggestion (assoc :suggestion suggestion))))

(defn cross-file-diagnostic
  "Convenience: create a cross-file diagnostic."
  [rule severity category files message & {:keys [suggestion]}]
  (diagnostic (cond-> {:rule rule
                       :severity severity
                       :message message
                       :scope :cross-file
                       :category category
                       :files files}
                suggestion (assoc :suggestion suggestion))))

(defn ->legacy
  "Convert a diagnostic to the legacy {:rule :severity :message :file :line} format.
  For :mod and :cross-file scoped diagnostics, :file is nil."
  [{:keys [rule severity message file line]}]
  {:rule rule
   :severity severity
   :message message
   :file file
   :line line})

(defn legacy->diagnostic
  "Upgrade a legacy {:rule :severity :message :file :line} map to the new format.
  Assumes :file scope with :lint category if file is present, :mod scope otherwise."
  [{:keys [rule severity message file line]}]
  (if file
    (file-diagnostic rule severity :lint file message :line line)
    (mod-diagnostic rule severity :lint message)))

(defn errors
  "Filter diagnostics to errors only."
  [diagnostics]
  (filter #(= :error (:severity %)) diagnostics))

(defn warnings
  "Filter diagnostics to warnings only."
  [diagnostics]
  (filter #(= :warning (:severity %)) diagnostics))

(defn by-category
  "Group diagnostics by category."
  [diagnostics]
  (group-by :category diagnostics))

(defn by-scope
  "Group diagnostics by scope."
  [diagnostics]
  (group-by :scope diagnostics))

(defn has-errors?
  "Returns true if any diagnostic is an error."
  [diagnostics]
  (boolean (seq (errors diagnostics))))
