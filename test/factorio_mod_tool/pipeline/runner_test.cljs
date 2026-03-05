(ns factorio-mod-tool.pipeline.runner-test
  (:require [cljs.test :refer [deftest is async]]
            [promesa.core :as p]
            [factorio-mod-tool.pipeline.runner :as runner]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-run-fn
  "Create a run-fn that tracks calls and returns configurable results.
   failures is a set of target keywords that should fail."
  [calls failures]
  (fn [target-key]
    (swap! calls conj target-key)
    (p/resolved (if (contains? failures target-key)
                  {:exit 1 :error (str (name target-key) " failed")}
                  {:exit 0}))))

;; ---------------------------------------------------------------------------
;; execute
;; ---------------------------------------------------------------------------

(deftest execute-simple-plan
  (async done
    (let [dag {:a {:deps []} :b {:deps [:a]}}
          calls (atom [])
          run-fn (make-run-fn calls #{})]
      (-> (p/let [result (runner/execute [:a :b] dag {:pre {} :post {}} run-fn)]
            (is (= :ok (:status result)))
            (is (= [:a :b] (:completed result)))
            (is (= [:a :b] @calls))
            (done))
          (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done)))))))

(deftest execute-stops-on-failure
  (async done
    (let [dag {:a {:deps []} :b {:deps [:a]} :c {:deps [:b]}}
          calls (atom [])
          run-fn (make-run-fn calls #{:b})]
      (-> (p/let [result (runner/execute [:a :b :c] dag {:pre {} :post {}} run-fn)]
            (is (= :failed (:status result)))
            (is (= :b (:failed-target result)))
            (is (= [:a] (:completed result)))
            ;; :c should not have been called
            (is (= [:a :b] @calls))
            (done))
          (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done)))))))

(deftest execute-caches-completed-targets
  (async done
    (let [;; DAG where both :b and :c depend on :a
          ;; Plan includes :a twice (as it would from two topo-sorts merged)
          dag {:a {:deps []} :b {:deps [:a]} :c {:deps [:a]}}
          calls (atom [])
          run-fn (make-run-fn calls #{})]
      (-> (p/let [result (runner/execute [:a :b :a :c] dag {:pre {} :post {}} run-fn)]
            (is (= :ok (:status result)))
            ;; :a should only run once despite appearing twice in plan
            (is (= [:a :b :c] @calls))
            (done))
          (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done)))))))

(deftest execute-custom-target-with-run-command
  (async done
    (let [dag {:a {:deps [] :run "echo custom"}}
          calls (atom [])
          run-fn (make-run-fn calls #{})]
      (-> (p/let [result (runner/execute [:a] dag {:pre {} :post {}} run-fn)]
            (is (= :ok (:status result)))
            ;; run-fn should NOT be called for custom targets
            (is (= [] @calls))
            (done))
          (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done)))))))

(deftest execute-callbacks
  (async done
    (let [dag {:a {:deps []} :b {:deps [:a]}}
          calls (atom [])
          starts (atom [])
          finishes (atom [])
          run-fn (make-run-fn calls #{})]
      (-> (p/let [result (runner/execute [:a :b] dag {:pre {} :post {}} run-fn
                           {:on-start #(swap! starts conj %)
                            :on-finish #(swap! finishes conj [%1 %2])})]
            (is (= :ok (:status result)))
            (is (= [:a :b] @starts))
            (is (= [[:a :ok] [:b :ok]] @finishes))
            (done))
          (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done)))))))

(deftest execute-empty-plan
  (async done
    (let [calls (atom [])
          run-fn (make-run-fn calls #{})]
      (-> (p/let [result (runner/execute [] {} {:pre {} :post {}} run-fn)]
            (is (= :ok (:status result)))
            (is (= [] (:completed result)))
            (done))
          (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done)))))))
