(ns factorio-mod-tool.scaffold
  "Scaffold a new Factorio mod project with opinionated structure."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.fs :as fs]))

(def ^:private child-process (js/require "child_process"))
(def ^:private path-mod (js/require "path"))

(defn- git-init
  "Run git init in the given directory. Returns a promise."
  [dir]
  (js/Promise.
   (fn [resolve reject]
     (.exec child-process "git init"
            #js {:cwd dir}
            (fn [err _stdout _stderr]
              (if err (reject err) (resolve nil)))))))

(defn- info-json [mod-name]
  (js/JSON.stringify
   (clj->js {:name         mod-name
              :version      "0.1.0"
              :title        mod-name
              :author       ""
              :contact      ""
              :homepage     ""
              :description  ""
              :factorio_version "1.1"})
   nil 2))

(defn- fmod-json [mod-name]
  (js/JSON.stringify
   (clj->js {:name    mod-name
              :version "0.1.0"
              :rcon    {:host "localhost" :port 27015}})
   nil 2))

(def ^:private data-lua
  "-- data.lua: Define prototypes in the data stage
-- See: https://wiki.factorio.com/Tutorial:Modding_tutorial/Gangsir#The_data_stage
")

(def ^:private control-lua
  "-- control.lua: Runtime event handlers
-- See: https://wiki.factorio.com/Tutorial:Modding_tutorial/Gangsir#The_control_stage

script.on_init(function()
  -- Called once when a new game is started or mod is added to a save
end)
")

(def ^:private locale-cfg
  "[mod-name]

[mod-description]
")

(def ^:private control-test-lua
  "-- Example test stub for control.lua
-- Run with: fmod run-tests (not yet implemented)
")

(def ^:private gitignore-content
  "dist/
.shadow-cljs/
node_modules/
")

(defn new-project
  "Create a new Factorio mod project. Returns a promise."
  [project-name]
  (let [root (fs/resolve-path project-name)
        mod-name (.basename path-mod root)
        src  (fs/join root "src")
        locale (fs/join src "locale" "en")
        test-dir (fs/join root "test")
        dist (fs/join root "dist")]
    (p/let [;; Create directories
            _ (p/all [(fs/mkdir src)
                      (fs/mkdir locale)
                      (fs/mkdir test-dir)
                      (fs/mkdir dist)])
            ;; Write files
            _ (p/all [(fs/write-file (fs/join src "info.json") (info-json mod-name))
                      (fs/write-file (fs/join src "data.lua") data-lua)
                      (fs/write-file (fs/join src "control.lua") control-lua)
                      (fs/write-file (fs/join locale "locale.cfg") locale-cfg)
                      (fs/write-file (fs/join test-dir "control_test.lua") control-test-lua)
                      (fs/write-file (fs/join root ".fmod.json") (fmod-json mod-name))
                      (fs/write-file (fs/join root ".gitignore") gitignore-content)])
            ;; Git init
            _ (git-init root)]
      {:path root :name mod-name})))
