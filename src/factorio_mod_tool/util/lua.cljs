(ns factorio-mod-tool.util.lua
  "Lua source code parsing via luaparse."
  (:require [promesa.core :as p]))

(def ^:private luaparse (js/require "luaparse"))

(defn parse
  "Parse Lua source code and return a promise of the AST as a CLJS map.
   Options:
     :comments  - include comments in AST (default true)
     :locations - include location info (default true)
     :ranges    - include byte ranges (default true)"
  ([source] (parse source {}))
  ([source {:keys [comments locations ranges]
            :or   {comments true locations true ranges true}}]
   (p/create
     (fn [resolve reject]
       (try
         (let [ast (.parse luaparse source
                     (clj->js {:comments  comments
                               :locations locations
                               :ranges    ranges
                               :luaVersion "5.2"}))]
           (resolve (js->clj ast :keywordize-keys true)))
         (catch js/Error e
           (reject e)))))))
