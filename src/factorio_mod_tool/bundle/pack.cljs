(ns factorio-mod-tool.bundle.pack
  "Zip packaging for distributable Factorio mods using archiver."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.fs :as fs]
            [factorio-mod-tool.util.mod :as mod]))

(def ^:private archiver (js/require "archiver"))

(defn pack-mod
  "Bundle a mod directory into a distributable zip file.
   The zip contains a top-level directory named `name_version` as Factorio expects.
   opts may include :exclude — a vector of glob patterns to exclude.
   Returns a promise of {:output-path <string> :size <int>}."
  ([mod-path output-dir] (pack-mod mod-path output-dir {}))
  ([mod-path output-dir {:keys [exclude] :or {exclude []}}]
   (p/create
     (fn [resolve reject]
       (p/let [abs-mod-path (fs/resolve-path mod-path)
               info         (mod/read-info-json abs-mod-path)
               mod-name     (:name info)
               mod-version  (:version info)
               zip-name     (str mod-name "_" mod-version ".zip")
               abs-output   (fs/resolve-path output-dir)
               _            (fs/mkdir abs-output)
               output-path  (fs/join abs-output zip-name)]
         (let [fs-node   (js/require "fs")
               output    (.createWriteStream fs-node output-path)
               ^js archive (archiver "zip" #js {:zlib #js {:level 9}})
               prefix    (str mod-name "_" mod-version "/")]
           (.on output "close"
                (fn []
                  (resolve {:output-path output-path
                            :size        (.pointer archive)})))
           (.on archive "error"
                (fn [err] (reject err)))
           (.pipe archive output)
           (.directory archive abs-mod-path prefix
                       (when (seq exclude)
                         #js {:ignore exclude}))
           (.finalize archive)))))))
