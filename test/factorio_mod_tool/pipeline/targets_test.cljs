(ns factorio-mod-tool.pipeline.targets-test
  (:require [cljs.test :refer [deftest is async]]
            [promesa.core :as p]
            [factorio-mod-tool.pipeline.targets :as targets]))

(deftest make-run-fn-returns-function
  (let [ctx {:mod-path "/tmp/test-mod" :config {:name "test" :version "0.1.0"}}
        run-fn (targets/make-run-fn ctx)]
    (is (fn? run-fn))))

(deftest make-run-fn-unknown-target
  (async done
    (let [ctx {:mod-path "/tmp/test-mod" :config {:name "test" :version "0.1.0"}}
          run-fn (targets/make-run-fn ctx)]
      (-> (p/let [result (run-fn :nonexistent-target)]
            (is (= 1 (:exit result)))
            (is (string? (:error result)))
            (done))
          (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done)))))))
