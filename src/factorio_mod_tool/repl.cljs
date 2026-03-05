(ns factorio-mod-tool.repl
  "REPL engine for iterative Lua execution against a running Factorio instance.
   Tracks command history and provides structured game state inspection helpers."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [factorio-mod-tool.rcon.client :as rcon]))

;; ---------------------------------------------------------------------------
;; REPL state
;; ---------------------------------------------------------------------------

(defonce repl-state
  (atom {:history []
         :instance nil}))

(defn- add-to-history! [entry]
  (swap! repl-state update :history conj entry))

(defn get-history
  "Return the REPL command history. Optionally limited to last n entries."
  ([] (:history @repl-state))
  ([n] (vec (take-last n (:history @repl-state)))))

(defn clear-history! []
  (swap! repl-state assoc :history []))

;; ---------------------------------------------------------------------------
;; Inspection helpers — dot-commands
;; ---------------------------------------------------------------------------

(def ^:private inspect-queries
  {".entities" "local result = {} for name, _ in pairs(game.entity_prototypes) do table.insert(result, name) end table.sort(result) rcon.print(serpent.line(result))"
   ".recipes"  "local result = {} for name, _ in pairs(game.recipe_prototypes) do table.insert(result, name) end table.sort(result) rcon.print(serpent.line(result))"
   ".forces"   "local result = {} for _, force in pairs(game.forces) do table.insert(result, {name=force.name, players=#force.players, technologies=force.technologies.length}) end rcon.print(serpent.line(result))"
   ".surface"  "local s = game.surfaces[1] rcon.print(serpent.line({name=s.name, index=s.index, daytime=s.daytime, wind_speed=s.wind_speed, ticks_per_day=s.ticks_per_day}))"})

(defn- dot-command? [input]
  (str/starts-with? (str/trim input) "."))

(defn- resolve-dot-command [input]
  (let [cmd (str/trim input)]
    (get inspect-queries cmd)))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn- parse-factorio-error [response]
  (let [trimmed (str/trim response)]
    (cond
      (str/blank? trimmed)
      {:type :empty}

      (or (str/includes? trimmed "Error")
          (str/includes? trimmed "error")
          (str/starts-with? trimmed "Cannot ")
          (str/starts-with? trimmed "attempt to "))
      (let [line-match (re-find #":(\d+):" trimmed)]
        {:type :error
         :message trimmed
         :line (when line-match (js/parseInt (second line-match)))})

      :else
      {:type :ok
       :value trimmed})))

;; ---------------------------------------------------------------------------
;; Eval
;; ---------------------------------------------------------------------------

(defn eval-lua
  "Evaluate a Lua expression/statement against a running Factorio instance.
   Uses /silent-command for execution.
   Dot-commands (.entities, .recipes, .forces, .surface) are expanded to
   built-in inspection queries.
   Returns a promise of {:input :output :parsed :timestamp}."
  [instance-name input]
  (let [trimmed (str/trim input)]
    (if (str/blank? trimmed)
      (p/resolved {:input input :output "" :parsed {:type :empty} :timestamp (js/Date.now)})
      (let [lua-code (if (dot-command? trimmed)
                       (or (resolve-dot-command trimmed)
                           (str "rcon.print('Unknown dot-command: " trimmed "')"))
                       ;; Wrap user code: if it doesn't contain rcon.print, wrap it
                       ;; to capture the return value
                       (if (str/includes? trimmed "rcon.print")
                         trimmed
                         (str "local __result = (function() " trimmed " end)() "
                              "if __result ~= nil then rcon.print(serpent.line(__result)) end")))
            command (str "/silent-command " lua-code)]
        (-> (p/let [response (rcon/exec instance-name command)
                    parsed (parse-factorio-error response)
                    entry {:input   trimmed
                           :output  response
                           :parsed  parsed
                           :timestamp (js/Date.now)}]
              (add-to-history! entry)
              entry)
            (p/catch (fn [err]
                       (let [entry {:input     trimmed
                                    :output    (ex-message err)
                                    :parsed    {:type :error :message (ex-message err)}
                                    :timestamp (js/Date.now)}]
                         (add-to-history! entry)
                         entry))))))))

;; ---------------------------------------------------------------------------
;; Structured inspect
;; ---------------------------------------------------------------------------

(defn inspect
  "Run a structured game state inspection query.
   category is one of: \"entities\", \"recipes\", \"forces\", \"surface\".
   Optional filter-str to filter results (substring match on entity/recipe names)."
  [instance-name category & [filter-str]]
  (let [dot-cmd (str "." category)
        lua-code (get inspect-queries dot-cmd)]
    (if-not lua-code
      (p/resolved {:error (str "Unknown inspect category: " category)
                   :available (keys inspect-queries)})
      (-> (p/let [response (rcon/exec instance-name (str "/silent-command " lua-code))]
            (cond-> {:category category
                     :result   (str/trim response)}
              filter-str (assoc :filter filter-str)))
          (p/catch (fn [err]
                     {:category category
                      :error    (ex-message err)}))))))
