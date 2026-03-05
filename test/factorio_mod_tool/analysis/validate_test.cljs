(ns factorio-mod-tool.analysis.validate-test
  (:require [cljs.test :refer [deftest testing is]]
            [factorio-mod-tool.analysis.validate :as validate]))

(deftest validate-required-files-test
  (testing "passes when info.json present"
    (is (empty? (validate/validate-required-files #{"info.json" "data.lua"}))))
  (testing "errors when info.json missing"
    (let [diags (validate/validate-required-files #{"data.lua"})]
      (is (= 1 (count diags)))
      (is (= :missing-required-file (:rule (first diags))))
      (is (= :error (:severity (first diags)))))))

(deftest validate-minimum-entry-points-test
  (testing "passes with data.lua"
    (is (empty? (validate/validate-minimum-entry-points #{"info.json" "data.lua"}))))
  (testing "passes with control.lua"
    (is (empty? (validate/validate-minimum-entry-points #{"info.json" "control.lua"}))))
  (testing "warns when no entry point"
    (let [diags (validate/validate-minimum-entry-points #{"info.json"})]
      (is (= 1 (count diags)))
      (is (= :no-entry-point (:rule (first diags))))
      (is (= :warning (:severity (first diags)))))))

(deftest validate-entry-point-locations-test
  (testing "passes when entry points at root"
    (is (empty? (validate/validate-entry-point-locations #{"data.lua" "control.lua"}))))
  (testing "errors when entry point in subdirectory"
    (let [diags (validate/validate-entry-point-locations #{"prototypes/data.lua"})]
      (is (= 1 (count diags)))
      (is (= :misplaced-entry-point (:rule (first diags))))
      (is (= :error (:severity (first diags)))))))

(deftest validate-info-json-test
  (testing "passes with all required fields"
    (is (empty? (validate/validate-info-json
                  {"name" "test-mod" "version" "1.0.0"
                   "title" "Test" "author" "Me"
                   "factorio_version" "1.1"}))))
  (testing "errors on missing fields"
    (let [diags (validate/validate-info-json {"name" "test-mod"})]
      (is (= 4 (count diags)))
      (is (every? #(= :error (:severity %)) diags)))))

(deftest validate-load-order-test
  (testing "no issues with proper load order"
    (is (empty? (validate/validate-load-order
                  #{"data.lua" "data-updates.lua" "data-final-fixes.lua"
                    "settings.lua" "settings-updates.lua" "settings-final-fixes.lua"}))))
  (testing "warns on data-updates without data"
    (let [diags (validate/validate-load-order #{"data-updates.lua"})]
      (is (some #(= :data-updates-without-data (:rule %)) diags))))
  (testing "warns on data-final-fixes without data"
    (let [diags (validate/validate-load-order #{"data-final-fixes.lua"})]
      (is (some #(= :data-final-fixes-without-data (:rule %)) diags))))
  (testing "warns on settings-updates without settings"
    (let [diags (validate/validate-load-order #{"settings-updates.lua"})]
      (is (some #(= :settings-updates-without-settings (:rule %)) diags))))
  (testing "warns on settings-final-fixes without settings"
    (let [diags (validate/validate-load-order #{"settings-final-fixes.lua"})]
      (is (some #(= :settings-final-fixes-without-settings (:rule %)) diags)))))

(deftest validate-unexpected-top-level-test
  (testing "no issues with recognized files"
    (is (empty? (validate/validate-unexpected-top-level
                  #{"info.json" "data.lua" "control.lua" "thumbnail.png"}))))
  (testing "flags unexpected files"
    (let [diags (validate/validate-unexpected-top-level #{"info.json" "random.py"})]
      (is (= 1 (count diags)))
      (is (= :unexpected-top-level-file (:rule (first diags))))))
  (testing "ignores dotfiles"
    (is (empty? (validate/validate-unexpected-top-level #{".gitignore"}))))
  (testing "ignores files in subdirectories"
    (is (empty? (validate/validate-unexpected-top-level #{"prototypes/entity.lua"})))))

(deftest validate-dependencies-test
  (testing "passes with valid dependencies"
    (is (empty? (validate/validate-dependencies
                  {"dependencies" ["base >= 1.1" "? optional-mod"]}))))
  (testing "errors on malformed dependency"
    (let [diags (validate/validate-dependencies {"dependencies" [""]})]
      (is (= 1 (count diags)))
      (is (= :malformed-dependency (:rule (first diags)))))))

(deftest validate-require-consistency-test
  (testing "passes when all requires exist"
    (is (empty? (validate/validate-require-consistency
                  {"control.lua" ["scripts/main.lua"]}
                  #{"control.lua" "scripts/main.lua"}))))
  (testing "errors on missing require target"
    (let [diags (validate/validate-require-consistency
                  {"control.lua" ["scripts/missing.lua"]}
                  #{"control.lua"})]
      (is (= 1 (count diags)))
      (is (= :require-missing-target (:rule (first diags)))))))

(deftest validate-mod-integration-test
  (testing "valid mod produces no errors"
    (let [diags (validate/validate-mod
                  {:files #{"info.json" "data.lua" "control.lua"}
                   :info {"name" "test-mod" "version" "1.0.0"
                          "title" "Test" "author" "Me"
                          "factorio_version" "1.1"}})]
      (is (not (some #(= :error (:severity %)) diags)))))
  (testing "missing info.json and no entry points"
    (let [diags (validate/validate-mod {:files #{} :info nil})]
      (is (some #(= :missing-required-file (:rule %)) diags))
      (is (some #(= :no-entry-point (:rule %)) diags)))))
