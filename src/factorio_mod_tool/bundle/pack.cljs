(ns factorio-mod-tool.bundle.pack
  "Zip packaging for distributable Factorio mods using archiver."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.util.mod :as mod]))

(def ^:private archiver (js/require "archiver"))
(def ^:private node-fs (js/require "fs"))
(def ^:private path-mod (js/require "path"))

(defn pack-mod
  "Bundle a mod directory into a distributable zip file.
   Factorio expects modname_version.zip containing a top-level
   directory modname_version/ with all mod files inside.

   mod-path    - path to the mod directory (must contain info.json)
   output-path - full path of the zip to write (optional; defaults to
                 ../modname_version.zip relative to mod-path)

   Returns a promise of {:output-path <string> :size <int>}."
  ([mod-path]
   (p/let [info     (mod/read-info-json mod-path)
           mod-name (:name info)
           version  (:version info)
           dirname  (str mod-name "_" version)
           parent   (fs/resolve-path mod-path "..")
           out-path (fs/join parent (str dirname ".zip"))]
     (pack-mod mod-path out-path)))
  ([mod-path output-path]
   (p/let [info     (mod/read-info-json mod-path)
           mod-name (:name info)
           version  (:version info)
           dirname  (str mod-name "_" version)
           out-path (fs/resolve-path output-path)
           out-dir  (.dirname path-mod out-path)
           _        (fs/mkdir out-dir)]
     (js/Promise.
       (fn [resolve reject]
         (let [output   (.createWriteStream node-fs out-path)
               ^js archive  (archiver "zip" #js {:zlib #js {:level 9}})]
           (.on output "close"
                (fn []
                  (resolve {:output-path out-path
                            :size        (.pointer archive)})))
           (.on archive "error" (fn [err] (reject err)))
           (.pipe archive output)
           (.directory archive (fs/resolve-path mod-path) dirname)
           (.finalize archive)))))))
