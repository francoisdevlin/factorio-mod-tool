(ns factorio-mod-tool.bundle.pack
  "Zip packaging for distributable Factorio mods. Stub."
  (:require [promesa.core :as p]))

(defn pack-mod
  "Bundle a mod directory into a distributable zip file.
   Returns a promise of {:output-path <string> :size <int>}."
  [mod-path output-path]
  ;; TODO: implement zip packaging using archiver
  (p/resolved {:output-path output-path :size 0}))
