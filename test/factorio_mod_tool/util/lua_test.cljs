(ns factorio-mod-tool.util.lua-test
  (:require [cljs.test :refer [deftest testing is async]]
            [promesa.core :as p]
            [factorio-mod-tool.util.lua :as lua]))

(deftest parse-simple-assignment
  (async done
    (-> (lua/parse "local x = 42")
        (p/then (fn [ast]
                  (is (= "Chunk" (:type ast)))
                  (is (seq (:body ast)))
                  (done)))
        (p/catch (fn [err]
                   (is false (str "Parse failed: " (.-message err)))
                   (done))))))

(deftest parse-function-def
  (async done
    (-> (lua/parse "function hello(name)\n  return 'hi ' .. name\nend")
        (p/then (fn [ast]
                  (is (= "Chunk" (:type ast)))
                  (done)))
        (p/catch (fn [err]
                   (is false (str "Parse failed: " (.-message err)))
                   (done))))))

(deftest parse-invalid-syntax
  (async done
    (-> (lua/parse "function (")
        (p/then (fn [_ast]
                  (is false "Should have failed to parse")
                  (done)))
        (p/catch (fn [_err]
                   (is true "Expected parse error")
                   (done))))))
