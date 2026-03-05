(ns factorio-mod-tool.analysis.validate-test
  (:require [cljs.test :refer [deftest testing is]]
            [factorio-mod-tool.analysis.validate :as validate]))

;; -- validate-info-json --

(deftest validate-info-json-complete
  (testing "returns no diagnostics for complete info.json"
    (let [info {"name" "test-mod"
                "version" "1.0.0"
                "title" "Test Mod"
                "author" "Tester"
                "factorio_version" "1.1"}
          result (validate/validate-info-json info)]
      (is (empty? result)))))

(deftest validate-info-json-missing-name
  (testing "returns error when name is missing"
    (let [info {"version" "1.0.0"
                "title" "Test Mod"
                "author" "Tester"
                "factorio_version" "1.1"}
          result (validate/validate-info-json info)]
      (is (= 1 (count result)))
      (is (= :missing-info-name (:rule (first result))))
      (is (= :error (:severity (first result))))
      (is (= :structure (:category (first result)))))))

(deftest validate-info-json-missing-multiple-fields
  (testing "returns errors for each missing field"
    (let [info {}
          result (validate/validate-info-json info)]
      (is (= 5 (count result)))
      (is (every? #(= :error (:severity %)) result))
      (is (= #{:missing-info-name
               :missing-info-version
               :missing-info-title
               :missing-info-author
               :missing-info-factorio_version}
             (set (map :rule result)))))))

;; -- validate-required-files --

(deftest validate-required-files-with-info-json
  (testing "returns no diagnostics when info.json is present"
    (let [result (validate/validate-required-files #{"info.json" "data.lua"})]
      (is (empty? result)))))

(deftest validate-required-files-missing-info-json
  (testing "returns error when info.json is missing"
    (let [result (validate/validate-required-files #{"data.lua" "control.lua"})]
      (is (= 1 (count result)))
      (is (= :missing-required-file (:rule (first result))))
      (is (= :error (:severity (first result))))
      (is (= :mod (:scope (first result)))))))

;; -- validate-load-order --

(deftest validate-load-order-no-issues
  (testing "returns no diagnostics when load order is correct"
    (let [result (validate/validate-load-order #{"data.lua" "data-updates.lua" "control.lua"})]
      (is (empty? result)))))

(deftest validate-load-order-data-updates-without-data
  (testing "warns when data-updates.lua exists without data.lua"
    (let [result (validate/validate-load-order #{"data-updates.lua" "control.lua"})]
      (is (= 1 (count result)))
      (is (= :data-updates-without-data (:rule (first result))))
      (is (= :warning (:severity (first result)))))))

(deftest validate-load-order-data-final-fixes-without-data
  (testing "warns when data-final-fixes.lua exists without data.lua"
    (let [result (validate/validate-load-order #{"data-final-fixes.lua"})]
      (is (= 1 (count result)))
      (is (= :data-final-fixes-without-data (:rule (first result)))))))

(deftest validate-load-order-settings-updates-without-settings
  (testing "warns when settings-updates.lua exists without settings.lua"
    (let [result (validate/validate-load-order #{"settings-updates.lua"})]
      (is (= 1 (count result)))
      (is (= :settings-updates-without-settings (:rule (first result)))))))

;; -- validate-dependencies --

(deftest validate-dependencies-valid
  (testing "returns no diagnostics for valid dependency strings"
    (let [info {"dependencies" ["base >= 1.1" "? optional-mod"]}
          result (validate/validate-dependencies info)]
      (is (empty? result)))))

(deftest validate-dependencies-no-deps
  (testing "returns no diagnostics when no dependencies declared"
    (let [info {}
          result (validate/validate-dependencies info)]
      (is (empty? result)))))

;; -- validate-require-consistency --

(deftest validate-require-consistency-all-present
  (testing "returns no diagnostics when all required files exist"
    (let [req-map {"control.lua" ["lib/util.lua"]}
          file-set #{"control.lua" "lib/util.lua"}
          result (validate/validate-require-consistency req-map file-set)]
      (is (empty? result)))))

(deftest validate-require-consistency-missing-target
  (testing "returns error when required file is missing"
    (let [req-map {"control.lua" ["lib/missing.lua"]}
          file-set #{"control.lua"}
          result (validate/validate-require-consistency req-map file-set)]
      (is (= 1 (count result)))
      (is (= :require-missing-target (:rule (first result))))
      (is (= :cross-file (:scope (first result)))))))

;; -- validate-mod (end-to-end) --

(deftest validate-mod-valid
  (testing "returns no diagnostics for a valid mod"
    (let [mod-data {:files #{"info.json" "data.lua" "control.lua"}
                    :info {"name" "test-mod"
                           "version" "1.0.0"
                           "title" "Test"
                           "author" "Me"
                           "factorio_version" "1.1"}}
          result (validate/validate-mod mod-data)]
      (is (empty? result)))))

(deftest validate-mod-missing-info-json-file
  (testing "returns error when info.json file is missing from file set"
    (let [mod-data {:files #{"data.lua"}
                    :info nil}
          result (validate/validate-mod mod-data)]
      (is (some #(= :missing-required-file (:rule %)) result)))))

(deftest validate-mod-combines-all-checks
  (testing "returns diagnostics from structure, info, and load-order checks"
    (let [mod-data {:files #{"data-updates.lua"}
                    :info {"name" "test"}}
          result (validate/validate-mod mod-data)]
      ;; Missing info.json file
      (is (some #(= :missing-required-file (:rule %)) result))
      ;; Missing info fields
      (is (some #(= :missing-info-version (:rule %)) result))
      ;; Load order warning
      (is (some #(= :data-updates-without-data (:rule %)) result)))))
