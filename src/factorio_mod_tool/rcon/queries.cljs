(ns factorio-mod-tool.rcon.queries
  "RCON query protocol for live Factorio server state.

   Each query category defines:
   - A Lua snippet that runs via /silent-command
   - The expected JSON-serializable response shape
   - Optional parameters (filters, pagination)

   All queries use rcon.print() with manual JSON construction for reliable
   parsing (serpent.line output is Lua-table syntax, not JSON). Where the
   result set can be large, queries support offset/limit pagination.

   Response shapes are documented inline as Clojure specs-like maps."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [factorio-mod-tool.rcon.client :as rcon]))

;; ---------------------------------------------------------------------------
;; Lua helpers — reusable fragments embedded in queries
;; ---------------------------------------------------------------------------

(def ^:private lua-json-array
  "Lua helper: build a JSON array string from a table of strings."
  "local function json_array(t)
     local parts = {}
     for i, v in ipairs(t) do parts[i] = '\"' .. v .. '\"' end
     return '[' .. table.concat(parts, ',') .. ']'
   end")

(def ^:private lua-json-obj
  "Lua helper: build a JSON object string from key-value pairs."
  "local function json_obj(t)
     local parts = {}
     for k, v in pairs(t) do
       local val
       if type(v) == 'string' then val = '\"' .. v .. '\"'
       elseif type(v) == 'boolean' then val = tostring(v)
       elseif type(v) == 'number' then val = tostring(v)
       elseif v == nil then val = 'null'
       else val = tostring(v) end
       parts[#parts+1] = '\"' .. k .. '\":' .. val
     end
     return '{' .. table.concat(parts, ',') .. '}'
   end")

(def ^:private lua-json-obj-array
  "Lua helper: build a JSON array of objects."
  "local function json_obj_array(items)
     local parts = {}
     for i, item in ipairs(items) do parts[i] = json_obj(item) end
     return '[' .. table.concat(parts, ',') .. ']'
   end")

(def ^:private lua-preamble
  "Common Lua preamble included in all queries."
  (str lua-json-array "\n" lua-json-obj "\n" lua-json-obj-array))

;; ---------------------------------------------------------------------------
;; Query definitions
;; ---------------------------------------------------------------------------
;; Each query is a map:
;;   :lua-fn    — (fn [params] lua-string)  builds the Lua snippet
;;   :response  — documentation of the expected response shape

(def queries
  "Registry of all RCON query protocols keyed by category."

  {:prototypes
   {:description "Enumerate available prototypes by type (entity, item, recipe, technology, fluid, tile)."
    :params      {:type   {:type "string" :enum ["entity" "item" "recipe" "technology" "fluid" "tile"]
                           :description "Prototype type to enumerate"}
                  :filter {:type "string" :description "Substring filter on prototype names (optional)"}
                  :offset {:type "number" :description "Pagination offset (default 0)"}
                  :limit  {:type "number" :description "Max results to return (default 100)"}}
    :required    ["type"]
    :response    "{:type string, :count number, :total number, :offset number, :names [string]}"
    :lua-fn
    (fn [{:keys [type filter offset limit]
          :or   {offset 0 limit 100}}]
      (let [proto-table (case type
                          "entity"     "game.entity_prototypes"
                          "item"       "game.item_prototypes"
                          "recipe"     "game.recipe_prototypes"
                          "technology" "game.technology_prototypes"
                          "fluid"      "game.fluid_prototypes"
                          "tile"       "game.tile_prototypes"
                          "game.entity_prototypes")
            filter-clause (if (and filter (not (str/blank? filter)))
                            (str "if string.find(name, " (pr-str filter) ", 1, true) then filtered[#filtered+1] = name end")
                            "filtered[#filtered+1] = name")]
        (str lua-preamble "
          local all = {}
          for name, _ in pairs(" proto-table ") do all[#all+1] = name end
          table.sort(all)
          local filtered = {}
          for _, name in ipairs(all) do " filter-clause " end
          local total = #filtered
          local offset = " offset "
          local limit = " limit "
          local result = {}
          for i = offset + 1, math.min(offset + limit, total) do
            result[#result+1] = filtered[i]
          end
          rcon.print('{\"type\":\"" type "\",\"total\":' .. total .. ',\"offset\":' .. offset .. ',\"count\":' .. #result .. ',\"names\":' .. json_array(result) .. '}')")))}

   :entities
   {:description "Query placed entities on a surface. Returns entity name, position, and health."
    :params      {:surface {:type "string" :description "Surface name (default: 'nauvis')"}
                  :area    {:type "object"
                            :properties {:x1 {:type "number"} :y1 {:type "number"}
                                         :x2 {:type "number"} :y2 {:type "number"}}
                            :description "Bounding box to search (default: 200x200 around origin)"}
                  :name    {:type "string" :description "Filter by entity prototype name (optional)"}
                  :limit   {:type "number" :description "Max entities to return (default 50)"}}
    :required    []
    :response    "{:surface string, :count number, :entities [{:name string, :position {:x number, :y number}, :health number, :type string}]}"
    :lua-fn
    (fn [{:keys [surface area name limit]
          :or   {surface "nauvis" limit 50}}]
      (let [x1 (or (:x1 area) -100)
            y1 (or (:y1 area) -100)
            x2 (or (:x2 area) 100)
            y2 (or (:y2 area) 100)
            name-filter (if (and name (not (str/blank? name)))
                          (str "{name = " (pr-str name) "}")
                          "nil")]
        (str lua-preamble "
          local s = game.get_surface(" (pr-str surface) ")
          if not s then rcon.print('{\"error\":\"Surface not found\"}') return end
          local area = {{" x1 "," y1 "},{" x2 "," y2 "}}
          local filter = " name-filter "
          local ents
          if filter then
            ents = s.find_entities_filtered{area = area, name = filter.name, limit = " limit "}
          else
            ents = s.find_entities_filtered{area = area, limit = " limit "}
          end
          local result = {}
          for i, e in ipairs(ents) do
            result[i] = json_obj({
              name = e.name,
              type = e.type,
              x = tostring(e.position.x),
              y = tostring(e.position.y),
              health = e.health and tostring(e.health) or 'null'
            })
          end
          rcon.print('{\"surface\":\"" surface "\",\"count\":' .. #result .. ',\"entities\":[' .. table.concat(result, ',') .. ']}')")))}

   :recipes
   {:description "List recipes with ingredients, products, and enabled state for a given force."
    :params      {:force  {:type "string" :description "Force name (default: 'player')"}
                  :filter {:type "string" :description "Substring filter on recipe names (optional)"}
                  :offset {:type "number" :description "Pagination offset (default 0)"}
                  :limit  {:type "number" :description "Max results (default 50)"}}
    :required    []
    :response    "{:force string, :total number, :count number, :recipes [{:name string, :category string, :enabled boolean, :ingredients [{:name string, :amount number, :type string}], :products [{:name string, :amount number, :type string}]}]}"
    :lua-fn
    (fn [{:keys [force filter offset limit]
          :or   {force "player" offset 0 limit 50}}]
      (let [filter-clause (if (and filter (not (str/blank? filter)))
                            (str "if string.find(name, " (pr-str filter) ", 1, true) then names[#names+1] = name end")
                            "names[#names+1] = name")]
        (str lua-preamble "
          local f = game.forces[" (pr-str force) "]
          if not f then rcon.print('{\"error\":\"Force not found\"}') return end
          local names = {}
          for name, _ in pairs(game.recipe_prototypes) do " filter-clause " end
          table.sort(names)
          local total = #names
          local offset = " offset "
          local limit = " limit "
          local result = {}
          for i = offset + 1, math.min(offset + limit, total) do
            local name = names[i]
            local proto = game.recipe_prototypes[name]
            local recipe = f.recipes[name]
            local ings = {}
            for j, ing in ipairs(proto.ingredients) do
              ings[j] = '{\"name\":\"' .. ing.name .. '\",\"amount\":' .. ing.amount .. ',\"type\":\"' .. ing.type .. '\"}'
            end
            local prods = {}
            for j, prod in ipairs(proto.products) do
              prods[j] = '{\"name\":\"' .. prod.name .. '\",\"amount\":' .. (prod.amount or prod.amount_min or 0) .. ',\"type\":\"' .. prod.type .. '\"}'
            end
            result[#result+1] = '{\"name\":\"' .. name .. '\",\"category\":\"' .. proto.category .. '\",\"enabled\":' .. tostring(recipe.enabled) .. ',\"ingredients\":[' .. table.concat(ings, ',') .. '],\"products\":[' .. table.concat(prods, ',') .. ']}'
          end
          rcon.print('{\"force\":\"" force "\",\"total\":' .. total .. ',\"offset\":' .. offset .. ',\"count\":' .. #result .. ',\"recipes\":[' .. table.concat(result, ',') .. ']}')")))}

   :technology
   {:description "Query technology tree state: researched, available, and locked technologies."
    :params      {:force  {:type "string" :description "Force name (default: 'player')"}
                  :state  {:type "string" :enum ["all" "researched" "available" "locked"]
                           :description "Filter by research state (default: 'all')"}
                  :filter {:type "string" :description "Substring filter on tech names (optional)"}
                  :offset {:type "number" :description "Pagination offset (default 0)"}
                  :limit  {:type "number" :description "Max results (default 50)"}}
    :required    []
    :response    "{:force string, :total number, :count number, :technologies [{:name string, :researched boolean, :enabled boolean, :level number, :prerequisites [string]}]}"
    :lua-fn
    (fn [{:keys [force state filter offset limit]
          :or   {force "player" state "all" offset 0 limit 50}}]
      (let [filter-clause (if (and filter (not (str/blank? filter)))
                            (str "if string.find(name, " (pr-str filter) ", 1, true) then")
                            "do")
            state-clause (case state
                           "researched" "if tech.researched then"
                           "available"  "if not tech.researched and tech.enabled then"
                           "locked"     "if not tech.researched and not tech.enabled then"
                           "do")]
        (str lua-preamble "
          local f = game.forces[" (pr-str force) "]
          if not f then rcon.print('{\"error\":\"Force not found\"}') return end
          local names = {}
          for name, tech in pairs(f.technologies) do
            " filter-clause "
              " state-clause "
                names[#names+1] = name
              end
            end
          end
          table.sort(names)
          local total = #names
          local offset = " offset "
          local limit = " limit "
          local result = {}
          for i = offset + 1, math.min(offset + limit, total) do
            local name = names[i]
            local tech = f.technologies[name]
            local prereqs = {}
            for pname, _ in pairs(tech.prerequisites) do prereqs[#prereqs+1] = pname end
            table.sort(prereqs)
            result[#result+1] = '{\"name\":\"' .. name .. '\",\"researched\":' .. tostring(tech.researched) .. ',\"enabled\":' .. tostring(tech.enabled) .. ',\"level\":' .. tech.level .. ',\"prerequisites\":' .. json_array(prereqs) .. '}'
          end
          rcon.print('{\"force\":\"" force "\",\"state\":\"" state "\",\"total\":' .. total .. ',\"offset\":' .. offset .. ',\"count\":' .. #result .. ',\"technologies\":[' .. table.concat(result, ',') .. ']}')")))}

   :forces
   {:description "List all forces with player counts and diplomatic relationships."
    :params      {}
    :required    []
    :response    "{:forces [{:name string, :player_count number, :rockets_launched number, :evolution_factor number, :research {:current string, :progress number}}]}"
    :lua-fn
    (fn [_params]
      (str lua-preamble "
        local result = {}
        for _, force in pairs(game.forces) do
          local current_research = 'null'
          local progress = 0
          if force.current_research then
            current_research = '\"' .. force.current_research.name .. '\"'
            progress = force.research_progress
          end
          result[#result+1] = '{\"name\":\"' .. force.name .. '\",\"player_count\":' .. #force.players .. ',\"rockets_launched\":' .. (force.rockets_launched or 0) .. ',\"evolution_factor\":' .. string.format('%.4f', force.evolution_factor) .. ',\"research\":{\"current\":' .. current_research .. ',\"progress\":' .. string.format('%.4f', progress) .. '}}'
        end
        rcon.print('{\"forces\":[' .. table.concat(result, ',') .. ']}')
        "))}

   :surfaces
   {:description "List all surfaces with basic map info."
    :params      {}
    :required    []
    :response    "{:surfaces [{:name string, :index number, :daytime number, :wind_speed number, :ticks_per_day number, :darkness number}]}"
    :lua-fn
    (fn [_params]
      (str lua-preamble "
        local result = {}
        for _, s in pairs(game.surfaces) do
          result[#result+1] = '{\"name\":\"' .. s.name .. '\",\"index\":' .. s.index .. ',\"daytime\":' .. string.format('%.4f', s.daytime) .. ',\"wind_speed\":' .. string.format('%.4f', s.wind_speed) .. ',\"ticks_per_day\":' .. s.ticks_per_day .. ',\"darkness\":' .. string.format('%.4f', s.darkness) .. '}'
        end
        rcon.print('{\"surfaces\":[' .. table.concat(result, ',') .. ']}')
        "))}

   :blueprints
   {:description "Access the player blueprint library. Lists blueprint items in the player's inventory (requires an online player)."
    :params      {:player {:type "string" :description "Player name (default: first connected player)"}}
    :required    []
    :response    "{:player string, :count number, :blueprints [{:name string, :type string, :index number}]}"
    :lua-fn
    (fn [{:keys [player]}]
      (let [player-lookup (if (and player (not (str/blank? player)))
                            (str "game.get_player(" (pr-str player) ")")
                            "game.connected_players[1]")]
        (str lua-preamble "
          local p = " player-lookup "
          if not p then rcon.print('{\"error\":\"No player found\"}') return end
          local inv = p.get_inventory(defines.inventory.character_main)
          if not inv then rcon.print('{\"error\":\"No inventory found\"}') return end
          local result = {}
          local count = 0
          for i = 1, #inv do
            local stack = inv[i]
            if stack.valid_for_read then
              local item_type = stack.prototype.type
              if stack.is_blueprint or stack.is_blueprint_book then
                count = count + 1
                local label = ''
                if stack.label then label = stack.label end
                result[#result+1] = '{\"name\":\"' .. label .. '\",\"type\":\"' .. (stack.is_blueprint and 'blueprint' or 'blueprint_book') .. '\",\"index\":' .. i .. '}'
              end
            end
          end
          rcon.print('{\"player\":\"' .. p.name .. '\",\"count\":' .. count .. ',\"blueprints\":[' .. table.concat(result, ',') .. ']}')
          ")))}})

;; ---------------------------------------------------------------------------
;; Query execution
;; ---------------------------------------------------------------------------

(defn execute-query
  "Execute an RCON query by category. Returns a promise of parsed JSON response.
   category — keyword from the queries registry (e.g. :prototypes, :entities)
   instance-name — RCON connection name
   params — map of query parameters"
  [instance-name category params]
  (if-let [query (get queries category)]
    (let [lua-code ((:lua-fn query) (or params {}))
          command (str "/silent-command " lua-code)]
      (-> (p/let [response (rcon/exec instance-name command)
                  trimmed (str/trim response)]
            (if (str/blank? trimmed)
              {:error "Empty response from server" :category (name category)}
              (js->clj (js/JSON.parse trimmed) :keywordize-keys true)))
          (p/catch (fn [err]
                     {:error (ex-message err) :category (name category)}))))
    (p/resolved {:error (str "Unknown query category: " (name category))
                 :available (mapv name (keys queries))})))

(defn list-categories
  "Return metadata about all available query categories."
  []
  (mapv (fn [[k v]]
          {:category    (name k)
           :description (:description v)
           :params      (:params v)
           :required    (:required v)
           :response    (:response v)})
        queries))
