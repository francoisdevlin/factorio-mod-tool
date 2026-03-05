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

(deftest parse-with-comments-disabled
  (async done
    (-> (lua/parse "-- a comment\nlocal x = 1" {:comments false})
        (p/then (fn [ast]
                  (is (= "Chunk" (:type ast)))
                  (is (nil? (:comments ast)))
                  (done)))
        (p/catch (fn [err]
                   (is false (str "Parse failed: " (.-message err)))
                   (done))))))

(deftest parse-with-locations-disabled
  (async done
    (-> (lua/parse "local x = 1" {:locations false})
        (p/then (fn [ast]
                  (is (= "Chunk" (:type ast)))
                  (let [stmt (first (:body ast))]
                    (is (nil? (:loc stmt))))
                  (done)))
        (p/catch (fn [err]
                   (is false (str "Parse failed: " (.-message err)))
                   (done))))))

(deftest parse-with-ranges-disabled
  (async done
    (-> (lua/parse "local x = 1" {:ranges false})
        (p/then (fn [ast]
                  (is (= "Chunk" (:type ast)))
                  (let [stmt (first (:body ast))]
                    (is (nil? (:range stmt))))
                  (done)))
        (p/catch (fn [err]
                   (is false (str "Parse failed: " (.-message err)))
                   (done))))))

(deftest parse-table-constructor
  (async done
    (-> (lua/parse "local t = {a = 1, b = 2}")
        (p/then (fn [ast]
                  (is (= "Chunk" (:type ast)))
                  (is (= 1 (count (:body ast))))
                  (done)))
        (p/catch (fn [err]
                   (is false (str "Parse failed: " (.-message err)))
                   (done))))))

(deftest parse-empty-string
  (async done
    (-> (lua/parse "")
        (p/then (fn [ast]
                  (is (= "Chunk" (:type ast)))
                  (is (empty? (:body ast)))
                  (done)))
        (p/catch (fn [err]
                   (is false (str "Parse failed: " (.-message err)))
                   (done))))))
