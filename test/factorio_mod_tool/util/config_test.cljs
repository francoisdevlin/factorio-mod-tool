(ns factorio-mod-tool.util.config-test
  (:require [cljs.test :refer [deftest testing is async]]
            [promesa.core :as p]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.util.config :as config]))

(def ^:private os (js/require "os"))
(def ^:private path-mod (js/require "path"))

(defn- tmp-dir
  "Create a unique temp directory. Returns a promise of the path."
  []
  (let [fsp (.-promises (js/require "fs"))]
    (.mkdtemp fsp (.join path-mod (.tmpdir os) "fmod-test-"))))

(defn- write-fmod-json [dir config-map]
  (fs/write-file (fs/join dir ".fmod.json")
                 (js/JSON.stringify (clj->js config-map) nil 2)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest read-config-basic
  (async done
    (-> (p/let [dir (tmp-dir)
                _ (write-fmod-json dir {:name "test-mod" :version "1.0.0"})
                {:keys [config config-path]} (config/read-config dir)]
          (is (= "test-mod" (:name config)))
          (is (= "1.0.0" (:version config)))
          (is (= (fs/join dir ".fmod.json") config-path))
          ;; Defaults applied
          (is (= "src" (get-in config [:structure :src])))
          (is (= "test" (get-in config [:structure :test])))
          (is (= "dist" (get-in config [:structure :dist])))
          (is (= "localhost" (get-in config [:rcon :host])))
          (is (= 27015 (get-in config [:rcon :port])))
          (is (= [] (get-in config [:pack :exclude])))
          (done))
        (p/catch (fn [err] (is false (str "Unexpected error: " (ex-message err))) (done))))))

(deftest read-config-walks-up
  (async done
    (-> (p/let [dir (tmp-dir)
                sub (fs/join dir "a" "b" "c")
                _ (fs/mkdir sub)
                _ (write-fmod-json dir {:name "walk-mod" :version "0.2.0"})
                {:keys [config config-path]} (config/read-config sub)]
          (is (= "walk-mod" (:name config)))
          (is (= (fs/join dir ".fmod.json") config-path))
          (done))
        (p/catch (fn [err] (is false (str "Unexpected error: " (ex-message err))) (done))))))

(deftest read-config-missing
  (async done
    (-> (p/let [dir (tmp-dir)
                _ (config/read-config dir)]
          (is false "Should have rejected for missing config")
          (done))
        (p/catch (fn [err]
                   (is (re-find #"No \.fmod\.json found" (ex-message err)))
                   (done))))))

(deftest read-config-missing-name
  (async done
    (-> (p/let [dir (tmp-dir)
                _ (write-fmod-json dir {:version "1.0.0"})
                _ (config/read-config dir)]
          (is false "Should have rejected for missing name")
          (done))
        (p/catch (fn [err]
                   (is (re-find #"Missing required field: name" (ex-message err)))
                   (done))))))

(deftest read-config-missing-version
  (async done
    (-> (p/let [dir (tmp-dir)
                _ (write-fmod-json dir {:name "no-version"})
                _ (config/read-config dir)]
          (is false "Should have rejected for missing version")
          (done))
        (p/catch (fn [err]
                   (is (re-find #"Missing required field: version" (ex-message err)))
                   (done))))))

(deftest read-config-custom-structure
  (async done
    (-> (p/let [dir (tmp-dir)
                _ (write-fmod-json dir {:name "custom"
                                        :version "1.0.0"
                                        :structure {:src "lib" :dist "build"}})
                {:keys [config]} (config/read-config dir)]
          (is (= "lib" (get-in config [:structure :src])))
          (is (= "build" (get-in config [:structure :dist])))
          ;; Default for :test still applied
          (is (= "test" (get-in config [:structure :test])))
          (done))
        (p/catch (fn [err] (is false (str "Unexpected error: " (ex-message err))) (done))))))

(deftest read-config-rcon-password-from-env
  (async done
    (let [original (.. js/process -env -FMOD_RCON_PASSWORD)]
      (set! (.. js/process -env -FMOD_RCON_PASSWORD) "secret123")
      (-> (p/let [dir (tmp-dir)
                  _ (write-fmod-json dir {:name "env-mod" :version "1.0.0"})
                  {:keys [config]} (config/read-config dir)]
            (is (= "secret123" (get-in config [:rcon :password])))
            ;; Restore env
            (if original
              (set! (.. js/process -env -FMOD_RCON_PASSWORD) original)
              (js-delete (.-env js/process) "FMOD_RCON_PASSWORD"))
            (done))
          (p/catch (fn [err]
                     (if original
                       (set! (.. js/process -env -FMOD_RCON_PASSWORD) original)
                       (js-delete (.-env js/process) "FMOD_RCON_PASSWORD"))
                     (is false (str "Unexpected error: " (ex-message err)))
                     (done)))))))

(deftest read-config-full-schema
  (async done
    (-> (p/let [dir (tmp-dir)
                _ (write-fmod-json dir {:name "full-mod"
                                        :version "2.0.0"
                                        :factorio_version "2.0"
                                        :structure {:src "src" :test "test" :dist "dist"}
                                        :rcon {:host "192.168.1.1" :port 28015}
                                        :pack {:exclude ["*.test.lua" "docs/"]}})
                {:keys [config]} (config/read-config dir)]
          (is (= "full-mod" (:name config)))
          (is (= "2.0.0" (:version config)))
          (is (= "2.0" (:factorio_version config)))
          (is (= "192.168.1.1" (get-in config [:rcon :host])))
          (is (= 28015 (get-in config [:rcon :port])))
          (is (= ["*.test.lua" "docs/"] (get-in config [:pack :exclude])))
          (done))
        (p/catch (fn [err] (is false (str "Unexpected error: " (ex-message err))) (done))))))
