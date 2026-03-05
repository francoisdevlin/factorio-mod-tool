(ns factorio-mod-tool.http.routes
  "HTTP route handlers for the REST API.
   Each handler takes a parsed request body and returns a promise of a response map."
  (:require [promesa.core :as p]
            [factorio-mod-tool.util.mod :as mod]
            [factorio-mod-tool.util.lua :as lua]
            [factorio-mod-tool.util.capabilities :as caps]
            [factorio-mod-tool.analysis.validate :as validate]
            [factorio-mod-tool.analysis.lint :as lint]
            [factorio-mod-tool.analysis.diagnostic :as diag]
            [factorio-mod-tool.rcon.client :as rcon]
            [factorio-mod-tool.repl :as repl]
            [factorio-mod-tool.state :as state]))

(defn- ok [body]
  {:status 200 :body body})

(defn- error-response [status message]
  {:status status :body {:error message}})

(defn handle-status
  "GET /api/status — server health + connected Factorio instances."
  [_req]
  (p/resolved
   (ok {:server "factorio-mod-tool"
        :version "0.1.0"
        :status "running"
        :rcon-connections (mapv (fn [[k v]]
                                 {:instance k
                                  :host (:host v)
                                  :port (:port v)})
                               @state/rcon-connections)})))

(defn handle-capabilities
  "GET /api/capabilities — detected capabilities."
  [_req]
  (-> (p/let [capabilities (caps/detect-all)]
        (ok {:capabilities
             (into {}
                   (map (fn [[k v]] [(name k) v]))
                   capabilities)}))
      (p/catch (fn [err]
                 (error-response 500 (ex-message err))))))

(defn handle-validate
  "POST /api/validate — validate mod directory."
  [body]
  (let [path (:path body)]
    (if-not path
      (p/resolved (error-response 400 "Missing required field: path"))
      (-> (p/let [mod-data (mod/read-mod-dir path)
                  diagnostics (validate/validate-mod mod-data)]
            (ok {:valid? (not (diag/has-errors? diagnostics))
                 :diagnostics diagnostics
                 :counts {:errors (count (diag/errors diagnostics))
                          :warnings (count (diag/warnings diagnostics))
                          :total (count diagnostics)}}))
          (p/catch (fn [err]
                     (error-response 500 (ex-message err))))))))

(defn handle-check
  "POST /api/check — check Lua files (offline or live)."
  [body]
  (let [{:keys [files source live instance]} body]
    (cond
      source
      (-> (p/let [_ast (lua/parse source)]
            (ok {:status :ok}))
          (p/catch (fn [err]
                     (ok {:status :error :message (ex-message err)}))))

      (seq files)
      (let [check-fn (if live
                       (fn [f]
                         (-> (p/let [src (.. (js/require "fs") -promises (readFile f "utf8"))
                                     cmd (str "/silent-command local ok, err = load("
                                              (pr-str src) ") if not ok then rcon.print(err) else rcon.print('OK') end")
                                     response (rcon/exec (or instance "__check__") cmd)
                                     trimmed (.trim response)]
                               {:file f :status (if (= trimmed "OK") :ok :error)
                                :message (when (not= trimmed "OK") trimmed)})
                             (p/catch (fn [err]
                                        {:file f :status :error :message (ex-message err)}))))
                       (fn [f]
                         (-> (p/let [src (.. (js/require "fs") -promises (readFile f "utf8"))
                                     _ast (lua/parse src)]
                               {:file f :status :ok})
                             (p/catch (fn [err]
                                        {:file f :status :error :message (ex-message err)})))))]
        (-> (p/let [results (p/all (mapv check-fn files))]
              (ok {:results results}))
            (p/catch (fn [err]
                       (error-response 500 (ex-message err))))))

      :else
      (p/resolved (error-response 400 "Missing required field: files or source")))))

(defn handle-lint
  "POST /api/lint — run lint rules on a mod."
  [body]
  (let [path (:path body)]
    (if-not path
      (p/resolved (error-response 400 "Missing required field: path"))
      (-> (p/let [mod-data (mod/read-mod-dir path)
                  diagnostics (lint/lint-mod mod-data)]
            (ok {:diagnostics diagnostics
                 :count (count diagnostics)}))
          (p/catch (fn [err]
                     (error-response 500 (ex-message err))))))))

(defn handle-parse
  "POST /api/parse — parse Lua source and return AST."
  [body]
  (let [source (:source body)]
    (if-not source
      (p/resolved (error-response 400 "Missing required field: source"))
      (-> (p/let [ast (lua/parse source)]
            (ok {:ast ast}))
          (p/catch (fn [err]
                     (error-response 500 (ex-message err))))))))

(defn handle-rcon-exec
  "POST /api/rcon/exec — execute RCON command."
  [body]
  (let [{:keys [instance command]} body]
    (cond
      (not instance)
      (p/resolved (error-response 400 "Missing required field: instance"))

      (not command)
      (p/resolved (error-response 400 "Missing required field: command"))

      :else
      (-> (p/let [response (rcon/exec instance command)]
            (ok {:response response}))
          (p/catch (fn [err]
                     (error-response 500 (ex-message err))))))))

(defn handle-diagnostics
  "GET /api/diagnostics — current diagnostics for loaded projects."
  [_req]
  (p/resolved
   (ok {:mods (into {}
                    (map (fn [[path data]]
                           [path {:diagnostics (:diagnostics data)}]))
                    @state/mod-state)})))

(defn handle-repl-eval
  "POST /api/repl/eval — evaluate Lua code via REPL."
  [body]
  (let [{:keys [instance code]} body]
    (cond
      (not instance)
      (p/resolved (error-response 400 "Missing required field: instance"))

      (not code)
      (p/resolved (error-response 400 "Missing required field: code"))

      :else
      (-> (p/let [result (repl/eval-lua instance code)]
            (ok result))
          (p/catch (fn [err]
                     (error-response 500 (ex-message err))))))))

(defn handle-repl-history
  "GET /api/repl/history — return REPL command history."
  [_req]
  (p/resolved
   (ok {:count   (count (repl/get-history))
        :entries (repl/get-history)})))

(defn handle-repl-inspect
  "POST /api/repl/inspect — structured game state inspection."
  [body]
  (let [{:keys [instance category filter]} body]
    (cond
      (not instance)
      (p/resolved (error-response 400 "Missing required field: instance"))

      (not category)
      (p/resolved (error-response 400 "Missing required field: category"))

      :else
      (-> (p/let [result (repl/inspect instance category filter)]
            (ok result))
          (p/catch (fn [err]
                     (error-response 500 (ex-message err))))))))

(def route-table
  "Map of [method path] -> handler function."
  {[:get "/api/status"]        handle-status
   [:get "/api/capabilities"]  handle-capabilities
   [:get "/api/diagnostics"]   handle-diagnostics
   [:post "/api/validate"]     handle-validate
   [:post "/api/check"]        handle-check
   [:post "/api/lint"]         handle-lint
   [:post "/api/parse"]        handle-parse
   [:post "/api/rcon/exec"]    handle-rcon-exec
   [:post "/api/repl/eval"]    handle-repl-eval
   [:get "/api/repl/history"]  handle-repl-history
   [:post "/api/repl/inspect"] handle-repl-inspect})
