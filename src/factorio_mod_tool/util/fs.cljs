(ns factorio-mod-tool.util.fs
  "Async Node.js filesystem wrappers using promises."
  (:require [promesa.core :as p]))

(def ^:private fs (js/require "fs"))
(def ^:private fsp (.-promises fs))
(def ^:private path-mod (js/require "path"))

(defn read-file
  "Read a file as UTF-8 string. Returns a promise."
  [filepath]
  (.readFile fsp filepath "utf8"))

(defn read-dir
  "List entries in a directory. Returns a promise of a JS array of strings."
  [dirpath]
  (.readdir fsp dirpath))

(defn exists?
  "Check if a path exists. Returns a promise of boolean."
  [filepath]
  (-> (.access fsp filepath)
      (p/then (fn [_] true))
      (p/catch (fn [_] false))))

(defn stat
  "Get file stats. Returns a promise of a Stats object."
  [filepath]
  (.stat fsp filepath))

(defn join
  "Join path segments."
  [& segments]
  (apply (.-join path-mod) segments))

(defn resolve-path
  "Resolve a path to absolute."
  [& segments]
  (apply (.-resolve path-mod) segments))
