(ns factorio-mod-tool.util.config
  "Read and validate .fmod.json project configuration.
   Walks up from a starting directory to find the nearest .fmod.json."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.fs :as fs]))

(def ^:private config-filename ".fmod.json")

(def ^:private default-structure
  {:src "src" :test "test" :dist "dist"})

(def ^:private default-rcon
  {:host "localhost" :port 27015})

(def ^:private default-pack
  {:exclude []})

(defn- find-config-file
  "Walk up from start-dir looking for .fmod.json.
   Returns a promise of the absolute path to the config file, or nil if not found."
  [start-dir]
  (let [path-mod (js/require "path")]
    (p/loop [dir (fs/resolve-path start-dir)]
      (let [candidate (fs/join dir config-filename)]
        (p/let [found (fs/exists? candidate)]
          (if found
            candidate
            (let [parent (.dirname path-mod dir)]
              (if (= parent dir)
                nil
                (p/recur parent)))))))))

(defn- validate-config
  "Validate required fields in a parsed config map.
   Returns a vector of error strings (empty if valid)."
  [config]
  (cond-> []
    (not (string? (:name config)))
    (conj "Missing required field: name (must be a string)")

    (not (string? (:version config)))
    (conj "Missing required field: version (must be a string)")))

(defn- apply-defaults
  "Apply default values for optional fields."
  [config]
  (-> config
      (update :structure #(merge default-structure %))
      (update :rcon #(merge default-rcon %))
      (update :pack #(merge default-pack %))))

(defn- inject-rcon-password
  "Inject RCON password from FMOD_RCON_PASSWORD env var.
   Never reads password from the config file."
  [config]
  (let [password (.. js/process -env -FMOD_RCON_PASSWORD)]
    (cond-> config
      (not-empty password)
      (assoc-in [:rcon :password] password))))

(defn read-config
  "Find and parse .fmod.json, walking up from start-dir (defaults to cwd).
   Returns a promise of {:config <map> :config-path <string>}.
   Rejects if no config found or validation fails."
  ([] (read-config (.cwd js/process)))
  ([start-dir]
   (p/let [config-path (find-config-file start-dir)]
     (when-not config-path
       (throw (js/Error. (str "No " config-filename " found (searched up from " start-dir ")"))))
     (p/let [content (fs/read-file config-path)
             raw (-> content js/JSON.parse (js->clj :keywordize-keys true))
             errors (validate-config raw)]
       (when (seq errors)
         (throw (js/Error. (str "Invalid " config-filename ": " (first errors)))))
       (let [config (-> raw apply-defaults inject-rcon-password)]
         {:config config :config-path config-path})))))
