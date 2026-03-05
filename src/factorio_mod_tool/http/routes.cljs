(ns factorio-mod-tool.http.routes
  "HTTP route handlers for the REST API.
   Thin adapter: generates a route-table from the command catalog and
   dispatches each request through queue/submit! instead of calling
   domain functions directly."
  (:require [promesa.core :as p]
            [factorio-mod-tool.commands :as commands]
            [factorio-mod-tool.queue :as queue]))

;; ---------------------------------------------------------------------------
;; HTTP route metadata — maps command names to REST endpoints
;; ---------------------------------------------------------------------------

(def ^:private route-specs
  "Maps each catalog command to its HTTP method and path."
  [;; Queries (GET)
   {:command "status"       :method :get  :path "/api/status"}
   {:command "capabilities" :method :get  :path "/api/capabilities"}
   {:command "diagnostics"  :method :get  :path "/api/diagnostics"}
   {:command "repl-history"    :method :get  :path "/api/repl/history"}
   {:command "get-preferences" :method :get  :path "/api/preferences"}
   ;; Mutations (POST)
   {:command "validate-mod" :method :post :path "/api/validate"}
   {:command "check"        :method :post :path "/api/check"}
   {:command "lint-mod"     :method :post :path "/api/lint"}
   {:command "parse-lua"    :method :post :path "/api/parse"}
   {:command "rcon-exec"    :method :post :path "/api/rcon/exec"}
   {:command "rcon-inspect" :method :post :path "/api/rcon/inspect"}
   {:command "repl-eval"    :method :post :path "/api/repl/eval"}
   {:command "repl-inspect"    :method :post :path "/api/repl/inspect"}
   {:command "set-preference"  :method :post :path "/api/preferences"}
   ;; RCON heartbeat
   {:command "rcon-health"          :method :get  :path "/api/rcon/health"}
   {:command "rcon-heartbeat"       :method :post :path "/api/rcon/heartbeat"}
   {:command "rcon-start-heartbeat" :method :post :path "/api/rcon/heartbeat/start"}
   {:command "rcon-stop-heartbeat"  :method :post :path "/api/rcon/heartbeat/stop"}
   ;; RCON query protocol
   {:command "rcon-query"         :method :post :path "/api/rcon/query"}
   {:command "rcon-query-catalog" :method :get  :path "/api/rcon/query/catalog"}
   ;; Project
   {:command "open-project"      :method :post :path "/api/project/open"}
   {:command "get-project"       :method :get  :path "/api/project"}])

;; ---------------------------------------------------------------------------
;; Response helpers
;; ---------------------------------------------------------------------------

(defn- ok [body]
  {:status 200 :body body})

(defn- error-response [status message]
  {:status status :body {:error message}})

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- validate-required
  "Check that all required fields (from the command's input-schema) are present
   in params. Returns nil if valid, or a string describing the first missing field."
  [cmd params]
  (let [required (get-in cmd [:input-schema :required])]
    (some (fn [field-name]
            (let [k (keyword field-name)]
              (when-not (contains? params k)
                (str "Missing required field: " field-name))))
          required)))

;; ---------------------------------------------------------------------------
;; Route handler generation
;; ---------------------------------------------------------------------------

(defn- make-handler
  "Create an HTTP handler for a catalog command. Validates required fields,
   submits through the queue, and wraps the result in an HTTP response map."
  [command-name]
  (fn [body]
    (let [cmd    (commands/find-command command-name)
          params (or body {})]
      (if-let [err (validate-required cmd params)]
        (p/resolved (error-response 400 err))
        (-> (queue/submit! command-name params)
            (p/then ok)
            (p/catch (fn [err]
                       (error-response 500 (ex-message err)))))))))

(def route-table
  "Map of [method path] -> handler function.
   Generated from the command catalog and route-specs."
  (into {}
        (map (fn [{:keys [command method path]}]
               [[method path] (make-handler command)]))
        route-specs))
