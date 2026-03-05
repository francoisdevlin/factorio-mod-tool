(ns factorio-mod-tool.util.capabilities-test
  (:require [cljs.test :refer [deftest is async]]
            [promesa.core :as p]
            [factorio-mod-tool.util.capabilities :as caps]))

(deftest detect-capability-unknown
  (async done
    (-> (p/let [result (caps/detect-capability :nonexistent {})]
          (is (false? (:available result)))
          (done))
        (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done))))))

(deftest detect-all-returns-map
  (async done
    (-> (p/let [_ (caps/reset-cache!)
                result (caps/detect-all)]
          (is (map? result))
          (is (contains? result :luarocks))
          (is (contains? result :busted))
          (is (contains? result :lua))
          (is (contains? result :factorio))
          (is (contains? result :factorio-rcon))
          (is (contains? result :factorio-test))
          ;; Each value has :available key
          (doseq [[_k v] result]
            (is (contains? v :available)))
          (done))
        (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done))))))

(deftest detect-all-caches-results
  (async done
    (-> (p/let [_ (caps/reset-cache!)
                first-result (caps/detect-all)
                second-result (caps/detect-all)]
          ;; Should return the same cached object
          (is (= first-result second-result))
          (caps/reset-cache!)
          (done))
        (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done))))))

(deftest available?-checks-map
  (let [caps-map {:lua {:available true} :busted {:available false}}]
    (is (true? (caps/available? caps-map :lua)))
    (is (false? (caps/available? caps-map :busted)))
    (is (false? (caps/available? caps-map :missing)))))

(deftest detect-capability-map-result
  (async done
    (-> (p/let [_ (caps/reset-cache!)
                result (caps/detect-capability :factorio {})]
          ;; factorio detect returns a map — should have :available key
          (is (contains? result :available))
          ;; If available and not in PATH, should have a suggestion
          (when (and (:available result) (:suggestion result))
            (is (string? (:suggestion result))))
          (done))
        (p/catch (fn [err] (is false (str "Unexpected: " (ex-message err))) (done))))))

(deftest format-status-returns-vec
  (let [caps-map {:lua {:available true :detail "/usr/bin/lua"}
                  :busted {:available false :detail nil}}
        statuses (caps/format-status caps-map)]
    (is (vector? statuses))
    (is (= 2 (count statuses)))
    ;; Sorted by key name
    (is (= "busted" (:capability (first statuses))))
    (is (= "lua" (:capability (second statuses))))
    ;; Unavailable ones have install instructions
    (is (some? (:install (first statuses))))
    (is (nil? (:install (second statuses))))))

(deftest format-status-includes-suggestion
  (let [caps-map {:factorio {:available true
                              :detail "/opt/factorio/bin/factorio"
                              :suggestion "Add to PATH: export PATH=\"/opt/factorio/bin:$PATH\""}}
        statuses (caps/format-status caps-map)]
    (is (= 1 (count statuses)))
    (is (= "factorio" (:capability (first statuses))))
    (is (true? (:available (first statuses))))
    (is (= "Add to PATH: export PATH=\"/opt/factorio/bin:$PATH\""
           (:suggestion (first statuses))))
    ;; Available caps should NOT have install instructions
    (is (nil? (:install (first statuses))))))
