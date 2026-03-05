(ns factorio-mod-tool.pipeline.dag-test
  (:require [cljs.test :refer [deftest is]]
            [factorio-mod-tool.pipeline.dag :as dag]))

;; ---------------------------------------------------------------------------
;; topo-sort
;; ---------------------------------------------------------------------------

(deftest topo-sort-single-no-deps
  (let [result (dag/topo-sort dag/default-dag :check)]
    (is (= [:check] result))))

(deftest topo-sort-with-deps
  (let [result (dag/topo-sort dag/default-dag :lint)]
    (is (= [:check :lint] result))))

(deftest topo-sort-deep-chain
  (let [result (dag/topo-sort dag/default-dag :pack)]
    (is (= [:check :lint :test :pack] result))))

(deftest topo-sort-deploy-full-chain
  (let [result (dag/topo-sort dag/default-dag :deploy)]
    (is (= [:check :lint :test :pack :deploy] result))))

(deftest topo-sort-check-live
  (let [result (dag/topo-sort dag/default-dag :check-live)]
    (is (= [:check :check-live] result))))

(deftest topo-sort-test-live
  (let [result (dag/topo-sort dag/default-dag :test-live)]
    ;; test-live depends on pack, which depends on test -> lint -> check
    (is (= [:check :lint :test :pack :test-live] result))))

(deftest topo-sort-unknown-target
  (is (thrown-with-msg? js/Error #"Unknown target: nope"
        (dag/topo-sort dag/default-dag :nope))))

(deftest topo-sort-cycle-detection
  (let [cyclic {:a {:deps [:b]}
                :b {:deps [:a]}}]
    (is (thrown-with-msg? js/Error #"Cycle detected"
          (dag/topo-sort cyclic :a)))))

(deftest topo-sort-unknown-dep
  (let [bad-dag {:a {:deps [:missing]}}]
    (is (thrown-with-msg? js/Error #"Unknown dependency 'missing'"
          (dag/topo-sort bad-dag :a)))))

;; ---------------------------------------------------------------------------
;; execution-plan
;; ---------------------------------------------------------------------------

(deftest execution-plan-normal
  (let [plan (dag/execution-plan dag/default-dag :pack {})]
    (is (= [:check :lint :test :pack] plan))))

(deftest execution-plan-only
  (let [plan (dag/execution-plan dag/default-dag :pack {:only true})]
    (is (= [:pack] plan))))

(deftest execution-plan-from
  (let [plan (dag/execution-plan dag/default-dag :pack {:from :lint})]
    (is (= [:lint :test :pack] plan))))

(deftest execution-plan-from-not-in-chain
  (is (thrown-with-msg? js/Error #"not in the dependency chain"
        (dag/execution-plan dag/default-dag :pack {:from :deploy}))))

;; ---------------------------------------------------------------------------
;; merge-custom-targets
;; ---------------------------------------------------------------------------

(deftest merge-custom-targets-nil
  (is (= dag/default-dag (dag/merge-custom-targets dag/default-dag nil))))

(deftest merge-custom-targets-new-target
  (let [custom {:targets {:my-step {:deps [:lint] :run "echo hello"}}}
        merged (dag/merge-custom-targets dag/default-dag custom)]
    (is (= {:deps [:lint] :run "echo hello" :custom? true}
           (get merged :my-step)))))

(deftest merge-custom-targets-extra-deps
  (let [custom {:targets {:lint {:extra-deps [:check-live]}}}
        merged (dag/merge-custom-targets dag/default-dag custom)]
    (is (= [:check :check-live] (get-in merged [:lint :deps])))))

(deftest merge-custom-targets-no-duplicate-deps
  (let [custom {:targets {:lint {:extra-deps [:check]}}}
        merged (dag/merge-custom-targets dag/default-dag custom)]
    (is (= [:check] (get-in merged [:lint :deps])))))

;; ---------------------------------------------------------------------------
;; merge-hooks
;; ---------------------------------------------------------------------------

(deftest merge-hooks-empty
  (is (= {:pre {} :post {}} (dag/merge-hooks nil))))

(deftest merge-hooks-with-values
  (let [pipeline {:hooks {:pack {:pre ["echo pre-pack"]
                                 :post ["echo post-pack"]}
                          :test {:pre ["echo pre-test"]}}}
        hooks (dag/merge-hooks pipeline)]
    (is (= ["echo pre-pack"] (get-in hooks [:pre :pack])))
    (is (= ["echo post-pack"] (get-in hooks [:post :pack])))
    (is (= ["echo pre-test"] (get-in hooks [:pre :test])))
    (is (nil? (get-in hooks [:post :test])))))

;; ---------------------------------------------------------------------------
;; all-targets-plan
;; ---------------------------------------------------------------------------

(deftest all-targets-plan-covers-all
  (let [plan (dag/all-targets-plan dag/default-dag)]
    (is (= (set (keys dag/default-dag)) (set plan)))
    ;; Check comes before everything that depends on it
    (is (< (.indexOf plan :check) (.indexOf plan :lint)))
    (is (< (.indexOf plan :check) (.indexOf plan :check-live)))
    (is (< (.indexOf plan :lint) (.indexOf plan :test)))
    (is (< (.indexOf plan :test) (.indexOf plan :pack)))
    (is (< (.indexOf plan :pack) (.indexOf plan :deploy)))
    (is (< (.indexOf plan :pack) (.indexOf plan :test-live)))))
