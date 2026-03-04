(ns factorio-mod-tool.testing.harness
  "Test runner for Factorio mod unit tests. Stub."
  (:require [promesa.core :as p]))

(defn run-tests
  "Execute mod unit tests in a sandboxed Lua environment.
   Returns a promise of {:passed int :failed int :results [maps]}."
  [mod-path]
  ;; TODO: implement test harness
  (p/resolved {:passed 0 :failed 0 :results []}))
