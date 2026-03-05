(ns factorio-mod-tool.repl-test
  (:require [cljs.test :refer [deftest is async]]
            [promesa.core :as p]
            [factorio-mod-tool.repl :as repl]))

(deftest get-history-empty
  (repl/clear-history!)
  (is (= [] (repl/get-history))))

(deftest get-history-with-limit
  (repl/clear-history!)
  (swap! repl/repl-state update :history conj
         {:input "a" :output "1" :parsed {:type :ok :value "1"} :timestamp 1}
         {:input "b" :output "2" :parsed {:type :ok :value "2"} :timestamp 2}
         {:input "c" :output "3" :parsed {:type :ok :value "3"} :timestamp 3})
  (is (= 3 (count (repl/get-history))))
  (is (= 2 (count (repl/get-history 2))))
  (is (= "b" (:input (first (repl/get-history 2))))))

(deftest clear-history
  (swap! repl/repl-state update :history conj
         {:input "x" :output "y" :parsed {:type :ok} :timestamp 1})
  (repl/clear-history!)
  (is (= [] (repl/get-history))))

(deftest eval-lua-blank-input
  (async done
    (-> (p/let [result (repl/eval-lua "test" "  ")]
          (is (= "" (:output result)))
          (is (= :empty (get-in result [:parsed :type]))))
        (p/finally done))))
