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

(def capability-defs*
  "Definitions for each detectable capability.
   Each entry: {:detect (fn [] -> promise of bool-or-path)
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
  "Detect a single capability. Returns a promise of {:available bool :detail string?}."
  [cap-key overrides]
  (let [{:keys [detect]} (get capability-defs* cap-key)]
    (if detect
      (-> (detect overrides)
          (p/then (fn [result]
                    {:available (boolean result)
                     :detail (when (string? result) result)})))
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
  (mapv (fn [[cap-key {:keys [available detail]}]]
          (let [install-msg (:install (get capability-defs* cap-key))]
            {:capability (name cap-key)
             :available available
             :detail (or detail "")
             :install (when-not available install-msg)}))
        (sort-by first capabilities)))
