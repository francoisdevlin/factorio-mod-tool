(ns factorio-mod-tool.pipeline.dag
  "Pure DAG operations: define targets, topological sort, execution plan.
   No I/O or side effects — this is a data-in, data-out engine.")

(def default-dag
  "Built-in target DAG for fmod pipelines."
  {:check      {:deps []}
   :lint       {:deps [:check]}
   :check-live {:deps [:check]}
   :test       {:deps [:lint]}
   :pack       {:deps [:test]}
   :deploy     {:deps [:pack]}
   :test-live  {:deps [:pack]}})

(defn merge-custom-targets
  "Merge custom pipeline config from .fmod.json into the default DAG.
   Custom targets add new entries; extra-deps on built-in targets are appended.

   custom-pipeline shape:
     {:targets {:my-target {:deps [:lint] :run \"some-command\"}
                :lint {:extra-deps [:my-check]}}}"
  [base-dag custom-pipeline]
  (if-not custom-pipeline
    base-dag
    (let [targets (:targets custom-pipeline)]
      (reduce-kv
       (fn [dag target-key target-def]
         (if (contains? dag target-key)
           ;; Existing target: merge extra-deps
           (let [extra (:extra-deps target-def)
                 run-cmd (:run target-def)]
             (cond-> dag
               (seq extra)
               (update-in [target-key :deps] #(vec (distinct (into % extra))))
               run-cmd
               (assoc-in [target-key :run] run-cmd)))
           ;; New custom target
           (assoc dag target-key
                  (cond-> {:deps (vec (or (:deps target-def) []))}
                    (:run target-def) (assoc :run (:run target-def))
                    true (assoc :custom? true)))))
       base-dag
       (or targets {})))))

(defn merge-hooks
  "Extract pre/post hooks from custom pipeline config.
   Returns {:pre {:check [\"cmd1\"] ...} :post {:pack [\"cmd2\"] ...}}"
  [custom-pipeline]
  (let [hooks (:hooks custom-pipeline)]
    {:pre  (reduce-kv (fn [m k v] (if (:pre v) (assoc m k (vec (:pre v))) m))
                      {} (or hooks {}))
     :post (reduce-kv (fn [m k v] (if (:post v) (assoc m k (vec (:post v))) m))
                      {} (or hooks {}))}))

(defn- visit
  "DFS visit for Kahn-free topological sort. Detects cycles."
  [dag target visiting visited order]
  (cond
    (contains? @visited target)
    nil ;; already processed

    (contains? @visiting target)
    (throw (js/Error. (str "Cycle detected in pipeline DAG involving target: " (name target))))

    :else
    (do
      (swap! visiting conj target)
      (doseq [dep (get-in dag [target :deps])]
        (when-not (contains? dag dep)
          (throw (js/Error. (str "Unknown dependency '" (name dep)
                                 "' for target '" (name target) "'"))))
        (visit dag dep visiting visited order))
      (swap! visiting disj target)
      (swap! visited conj target)
      (swap! order conj target))))

(defn topo-sort
  "Topologically sort targets needed to reach `target` in the DAG.
   Returns a vector of target keywords in execution order (deps first).
   Throws on cycles or unknown targets."
  [dag target]
  (when-not (contains? dag target)
    (throw (js/Error. (str "Unknown target: " (name target)))))
  (let [visiting (atom #{})
        visited  (atom #{})
        order    (atom [])]
    (visit dag target visiting visited order)
    @order))

(defn execution-plan
  "Build an execution plan for the given target.

   Options:
     :only   - If true, run only the specified target (skip deps)
     :from   - Start from this target forward (skip earlier deps)

   Returns a vector of target keywords in execution order."
  [dag target opts]
  (cond
    (:only opts)
    (do
      (when-not (contains? dag target)
        (throw (js/Error. (str "Unknown target: " (name target)))))
      [target])

    (:from opts)
    (let [full-plan (topo-sort dag target)
          from-target (:from opts)
          idx (.indexOf full-plan from-target)]
      (when (= idx -1)
        (throw (js/Error. (str "Target '" (name from-target)
                               "' is not in the dependency chain of '"
                               (name target) "'"))))
      (subvec full-plan idx))

    :else
    (topo-sort dag target)))

(defn all-targets-plan
  "Build an execution plan that runs all reachable targets in the DAG.
   Useful for `fmod all`."
  [dag]
  (let [visiting (atom #{})
        visited  (atom #{})
        order    (atom [])]
    (doseq [target (keys dag)]
      (visit dag target visiting visited order))
    @order))
