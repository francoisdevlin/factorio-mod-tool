(ns factorio-mod-tool.pipeline.runner
  "Pipeline execution engine. Takes a DAG + execution plan and runs targets
   in order, caching results and stopping on first failure.

   Separates the execution engine from CLI concerns — this module deals only
   with running targets, not with argument parsing or output formatting."
  (:require [promesa.core :as p]))

(def ^:private child-process (js/require "child_process"))

(defn- run-shell-command
  "Execute a shell command. Returns a promise that resolves to
   {:exit 0 :stdout \"...\" :stderr \"...\"} or rejects on spawn error."
  [cmd]
  (js/Promise.
   (fn [resolve _reject]
     (let [proc (.exec child-process cmd
                       #js {:shell true :maxBuffer (* 10 1024 1024)}
                       (fn [err stdout stderr]
                         (if err
                           (resolve {:exit (or (.-code err) 1)
                                     :stdout (or stdout "")
                                     :stderr (or stderr "")
                                     :error (ex-message err)})
                           (resolve {:exit 0
                                     :stdout (or stdout "")
                                     :stderr (or stderr "")}))))]
       ;; Pipe child output to parent for real-time feedback
       (when (.-stdout proc) (.pipe (.-stdout proc) js/process.stdout))
       (when (.-stderr proc) (.pipe (.-stderr proc) js/process.stderr))))))

(defn- run-hooks
  "Run a sequence of hook commands. Stops on first failure.
   Returns a promise of :ok or {:hook-failed cmd result}."
  [hooks]
  (if (empty? hooks)
    (p/resolved :ok)
    (p/loop [remaining hooks]
      (if (empty? remaining)
        :ok
        (p/let [cmd (first remaining)
                result (run-shell-command cmd)]
          (if (zero? (:exit result))
            (p/recur (rest remaining))
            {:hook-failed cmd :result result}))))))

(defn run-target
  "Run a single target. Custom targets execute their :run command.
   Built-in targets are dispatched via the provided run-fn.

   run-fn: (fn [target-key] -> promise of result)
     Should return a map with at least :exit (0 for success).

   Returns a promise of {:target key :status :ok|:failed :result map}"
  [target-key target-def run-fn]
  (p/let [result (if (:run target-def)
                   (run-shell-command (:run target-def))
                   (run-fn target-key))]
    (if (zero? (:exit result 0))
      {:target target-key :status :ok :result result}
      {:target target-key :status :failed :result result})))

(defn execute
  "Execute a pipeline plan.

   Arguments:
     plan     - Vector of target keywords in execution order
     dag      - The full DAG map
     hooks    - {:pre {:target [cmds]} :post {:target [cmds]}}
     run-fn   - (fn [target-key] -> promise of {:exit 0|1 ...})
                Called for built-in targets (those without :run in DAG)

   Options:
     :on-start  - (fn [target-key]) callback before each target
     :on-finish - (fn [target-key status]) callback after each target

   Returns a promise of:
     {:status :ok :completed [targets]}
   or
     {:status :failed :failed-target key :completed [targets] :result map}"
  [plan dag hooks run-fn & [{:keys [on-start on-finish]}]]
  (let [cache (atom #{})]
    (p/loop [remaining plan
             completed []]
      (if (empty? remaining)
        {:status :ok :completed completed}
        (let [target (first remaining)]
          (if (contains? @cache target)
            ;; Already ran this target (shared dep), skip
            (p/recur (rest remaining) completed)
            (p/let [_ (when on-start (on-start target))
                    ;; Run pre-hooks
                    pre-result (run-hooks (get-in hooks [:pre target]))
                    _ (when (and (map? pre-result) (:hook-failed pre-result))
                        (throw (ex-info (str "pre-" (name target) " hook failed: "
                                             (:hook-failed pre-result))
                                        {:target target
                                         :phase :pre-hook
                                         :result (:result pre-result)})))
                    ;; Run the target
                    target-def (get dag target {})
                    outcome (run-target target target-def run-fn)]
              (if (= :failed (:status outcome))
                (do
                  (when on-finish (on-finish target :failed))
                  {:status :failed
                   :failed-target target
                   :completed completed
                   :result (:result outcome)})
                ;; Target succeeded — run post-hooks
                (p/let [post-result (run-hooks (get-in hooks [:post target]))
                        _ (when (and (map? post-result) (:hook-failed post-result))
                            (throw (ex-info (str "post-" (name target) " hook failed: "
                                                 (:hook-failed post-result))
                                            {:target target
                                             :phase :post-hook
                                             :result (:result post-result)})))]
                  (swap! cache conj target)
                  (when on-finish (on-finish target :ok))
                  (p/recur (rest remaining) (conj completed target)))))))))))
