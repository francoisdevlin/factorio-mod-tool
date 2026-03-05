(ns factorio-mod-tool.bundle.pack
  "Zip packaging for distributable Factorio mods. Stub."
  (:require [promesa.core :as p]))

(defn pack-mod
  "Bundle a mod directory into a distributable zip file.
   opts may include :exclude — a vector of glob patterns to exclude.
   Returns a promise of {:output-path <string> :size <int>}."
  ([mod-path output-path] (pack-mod mod-path output-path {}))
  ([mod-path output-path {:keys [exclude] :or {exclude []}}]
   ;; TODO: implement zip packaging using archiver
   ;; exclude patterns will be passed to archiver.glob when implemented
   (p/resolved {:output-path output-path :size 0})))
