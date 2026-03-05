(ns factorio-mod-tool.util.mod
  "Read and parse a Factorio mod directory into a data map."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.fs :as fs]))

(defn read-info-json
  "Read and parse info.json from a mod directory.
   Returns a promise of the parsed map."
  [mod-path]
  (p/let [filepath (fs/join mod-path "info.json")
          content  (fs/read-file filepath)]
    (-> content js/JSON.parse (js->clj :keywordize-keys true))))

(defn list-lua-files
  "List all .lua files in a mod directory (non-recursive).
   Returns a promise of a vector of filenames."
  [mod-path]
  (p/let [entries (fs/read-dir mod-path)]
    (->> (js->clj entries)
         (filterv #(re-find #"\.lua$" %)))))

(defn list-all-files
  "List all files in a mod directory recursively.
   Returns a promise of a vector of relative file paths."
  [mod-path]
  (fs/list-files-recursive mod-path))

(defn read-mod-dir
  "Read a mod directory and return a promise of a mod info map.
   The map contains:
     :path  - absolute path to the mod
     :info  - parsed info.json contents
     :files - vector of all relative file paths"
  [mod-path]
  (p/let [abs-path  (fs/resolve-path mod-path)
          info      (-> (read-info-json abs-path)
                        (p/catch (fn [_] nil)))
          all-files (list-all-files abs-path)]
    {:path  abs-path
     :info  info
     :files all-files}))
