(ns factorio-mod-tool.util.capabilities
  "Detect availability of external tools needed by pipeline targets.
   Results are cached for the duration of a run."
  (:require [promesa.core :as p]))

(def ^:private child-process (js/require "child_process"))

(defn- which
  "Check if a command exists via 'which'. Returns a promise of the trimmed
   path string, or nil if not found."
  [cmd]
  (js/Promise.
   (fn [resolve _reject]
     (.exec child-process (str "which " cmd)
            #js {:shell true}
            (fn [err stdout _stderr]
              (if err
                (resolve nil)
                (resolve (.trim (or stdout "")))))))))

(defn- shell-succeeds?
  "Run a shell command and return a promise of boolean (true if exit 0)."
  [cmd]
  (js/Promise.
   (fn [resolve _reject]
     (.exec child-process cmd
            #js {:shell true}
            (fn [err _stdout _stderr]
              (resolve (nil? err)))))))

(defn- file-exists?
  "Check if a file exists at the given path. Returns a promise of boolean."
  [path]
  (let [fsp (.-promises (js/require "fs"))]
    (-> (.access fsp path)
        (p/then (fn [_] true))
        (p/catch (fn [_] false)))))

(defn- first-existing-path
  "Given a vector of file paths, return a promise of the first one that exists,
   or nil if none exist."
  [paths]
  (p/loop [remaining paths]
    (if (empty? remaining)
      nil
      (p/let [exists (file-exists? (first remaining))]
        (if exists
          (first remaining)
          (p/recur (rest remaining)))))))

(defn- detect-factorio
  "Multi-step detection for the Factorio binary.
   1. Check PATH via 'which factorio'
   2. Check overrides (from .fmod.json factorio.path)
   3. Check common install locations
   Returns a map with :available, :detail, and optionally :suggestion."
  [overrides]
  (let [home (.. js/process -env -HOME)
        common-paths
        [(str home "/Library/Application Support/Steam/steamapps/common/Factorio/factorio.app/Contents/MacOS/factorio")
         "/Applications/factorio.app/Contents/MacOS/factorio"
         (str home "/.steam/steam/steamapps/common/Factorio/bin/x64/factorio")
         (str home "/factorio/bin/x64/factorio")
         "C:/Program Files (x86)/Steam/steamapps/common/Factorio/bin/x64/factorio.exe"]]
    ;; Step 1: Check PATH
    (p/let [path-result (which "factorio")]
      (if (not-empty path-result)
        ;; Found in PATH — fully satisfied
        {:available true :detail path-result}
        ;; Step 2: Check overrides (.fmod.json factorio.path)
        (p/let [override-path (get overrides :factorio)
                override-exists (if override-path (file-exists? override-path) (p/resolved false))]
          (if override-exists
            {:available true
             :detail override-path
             :suggestion (str "Add to PATH: export PATH=\""
                              (.dirname (js/require "path") override-path)
                              ":$PATH\"")}
            ;; Step 3: Check common install locations
            (p/let [found (first-existing-path common-paths)]
              (if found
                {:available true
                 :detail found
                 :suggestion (str "Add to PATH: export PATH=\""
                                  (.dirname (js/require "path") found)
                                  ":$PATH\"")}
                {:available false}))))))))

(def capability-defs*
  "Definitions for each detectable capability.
   Each entry: {:detect (fn [] -> promise of bool-or-path-or-map)
                :install \"Human-readable install instructions\"}"
  {:luarocks {:detect  (fn [_overrides] (which "luarocks"))
              :install "Install LuaRocks: https://luarocks.org/"}
   :busted   {:detect  (fn [_overrides]
                          (shell-succeeds? "luarocks show busted 2>/dev/null"))
              :install "Install busted: luarocks install busted"}
   :lua      {:detect  (fn [overrides]
                          (if-let [path (get overrides :lua)]
                            (which path)
                            (which "lua")))
              :install "Install Lua: https://www.lua.org/download.html"}
   :factorio {:detect  detect-factorio
              :install "Install Factorio: https://www.factorio.com/download"}
   :factorio-rcon
              {:detect  (fn [_overrides]
                          ;; Just check if RCON env var is set (actual connection tested at runtime)
                          (p/resolved (some? (not-empty (.. js/process -env -FMOD_RCON_PASSWORD)))))
               :install "Set FMOD_RCON_PASSWORD env var and ensure Factorio RCON is enabled"}
   :factorio-test
              {:detect  (fn [_overrides]
                          ;; Check common Factorio mod paths for factorio-test mod
                          (let [fsp (.-promises (js/require "fs"))
                                home (.. js/process -env -HOME)
                                candidates [(str home "/.factorio/mods/factorio-test")
                                            (str home "/Library/Application Support/factorio/mods/factorio-test")
                                            (str home "/.local/share/factorio/mods/factorio-test")]]
                            (-> (p/all (mapv (fn [path]
                                              (-> (.access fsp path)
                                                  (p/then (fn [_] true))
                                                  (p/catch (fn [_] false))))
                                            candidates))
                                (p/then (fn [results] (some true? results))))))
               :install "Install factorio-test mod into your Factorio mods folder"}})

;; Runtime cache (reset per process)
(def ^:private cache (atom nil))

(defn detect-capability
  "Detect a single capability. Returns a promise of {:available bool :detail string? :suggestion string?}.
   Detect functions may return:
   - nil/false: not available
   - string: available, string is the path/detail
   - map with :available, :detail, :suggestion: passed through directly"
  [cap-key overrides]
  (let [{:keys [detect]} (get capability-defs* cap-key)]
    (if detect
      (-> (detect overrides)
          (p/then (fn [result]
                    (cond
                      (map? result) result
                      (string? result) {:available true :detail result}
                      :else {:available (boolean result)}))))
      (p/resolved {:available false :detail "Unknown capability"}))))

(defn detect-all
  "Detect all capabilities. Returns a promise of a map
   {:luarocks {:available bool} :busted {:available bool} ...}.
   Results are cached for the process lifetime."
  ([] (detect-all {}))
  ([overrides]
   (if-let [cached @cache]
     (p/resolved cached)
     (p/let [results
             (p/all
              (mapv (fn [cap-key]
                      (p/let [result (detect-capability cap-key overrides)]
                        [cap-key result]))
                    (keys capability-defs*)))]
       (let [result-map (into {} results)]
         (reset! cache result-map)
         result-map)))))

(defn available?
  "Check if a specific capability is available from a detect-all result map."
  [capabilities cap-key]
  (get-in capabilities [cap-key :available] false))

(defn reset-cache!
  "Clear the capabilities cache. Useful for testing."
  []
  (reset! cache nil))

(defn format-status
  "Format capabilities map for human display (used by fmod doctor)."
  [capabilities]
  (mapv (fn [[cap-key {:keys [available detail suggestion]}]]
          (let [install-msg (:install (get capability-defs* cap-key))]
            (cond-> {:capability (name cap-key)
                     :available available
                     :detail (or detail "")}
              (not available) (assoc :install install-msg)
              suggestion (assoc :suggestion suggestion))))
        (sort-by first capabilities)))
