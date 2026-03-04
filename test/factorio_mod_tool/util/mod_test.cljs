(ns factorio-mod-tool.util.mod-test
  (:require [cljs.test :refer [deftest testing is async]]
            [promesa.core :as p]
            [factorio-mod-tool.util.mod :as mod]))

(deftest read-mod-dir-missing
  (async done
    (-> (mod/read-mod-dir "/nonexistent/path")
        (p/then (fn [_]
                  (is false "Should have failed for missing directory")
                  (done)))
        (p/catch (fn [_err]
                   (is true "Expected error for missing directory")
                   (done))))))
