(ns factorio-mod-tool.gui.dispatch
  "Client-side event bus with dispatch pattern.
   Local events update app-db directly. Server commands dispatch over WebSocket
   and results update :server state via broadcast handlers."
  (:require [factorio-mod-tool.gui.db :as db]
            [factorio-mod-tool.gui.ws :as ws]))

;; ---------------------------------------------------------------------------
;; Event handler multimethod
;; ---------------------------------------------------------------------------

(defmulti handle-event
  "Dispatch on the first element of the event vector.
   (dispatch! [:navigate :settings]) -> (handle-event [:navigate :settings])"
  (fn [event] (first event)))

(defmethod handle-event :default [[type & _]]
  (js/console.warn "Unhandled event:" (pr-str type)))

;; ---------------------------------------------------------------------------
;; Public dispatch
;; ---------------------------------------------------------------------------

(defn dispatch!
  "Dispatch an event through the event bus.
   Events are vectors: [:event-type & args]"
  [event]
  (handle-event event))

;; ---------------------------------------------------------------------------
;; Navigation events (local only, no server round-trip)
;; ---------------------------------------------------------------------------

(defmethod handle-event :navigate [[_ section]]
  (swap! db/app-db assoc-in [:navigation :section] section))

(defmethod handle-event :select-file [[_ path]]
  (swap! db/app-db
         (fn [db]
           (-> db
               (assoc-in [:navigation :selected-file] path)
               (assoc-in [:navigation :file-content] nil)))))

(defmethod handle-event :set-file-content [[_ content]]
  (swap! db/app-db assoc-in [:navigation :file-content] content))

(defmethod handle-event :toggle-tree-node [[_ path]]
  (swap! db/app-db update :file-tree
         (fn [tree]
           (letfn [(toggle [nodes]
                     (mapv (fn [n]
                             (if (= (:path n) path)
                               (update n :expanded? not)
                               (if (:children n)
                                 (update n :children toggle)
                                 n)))
                           nodes))]
             (toggle tree)))))

;; ---------------------------------------------------------------------------
;; UI events (local state)
;; ---------------------------------------------------------------------------

(defmethod handle-event :set-theme [[_ theme]]
  (swap! db/app-db assoc :current-theme theme)
  (.setAttribute (.-documentElement js/document) "data-theme" theme))

(defmethod handle-event :console-append [[_ line]]
  (swap! db/app-db update :console-lines conj line))

(defmethod handle-event :set-connection-status [[_ status]]
  (swap! db/app-db assoc :connection-status status))

;; ---------------------------------------------------------------------------
;; Server state events (from WS broadcasts → update :server cache)
;; ---------------------------------------------------------------------------

(defmethod handle-event :server/status [[_ data]]
  (swap! db/app-db
         (fn [db]
           (cond-> (assoc-in db [:server :status] data)
             (:rcon-connections data)
             (assoc-in [:server :rcon-connections] (vec (:rcon-connections data)))))))

(defmethod handle-event :server/capabilities [[_ caps]]
  (swap! db/app-db assoc-in [:server :capabilities] caps))

(defmethod handle-event :server/diagnostics [[_ diags]]
  (swap! db/app-db assoc-in [:server :diagnostics] (vec diags)))

(defmethod handle-event :server/pipeline-status [[_ data]]
  (swap! db/app-db assoc-in [:server :pipeline-status]
         {:target (:target data) :status (keyword (:status data))}))

(defmethod handle-event :server/pipeline-result [[_ target status]]
  (swap! db/app-db assoc-in [:server :pipeline-results target]
         {:status status :timestamp (.toISOString (js/Date.))}))

(defmethod handle-event :server/rcon-health [[_ data]]
  (swap! db/app-db assoc-in [:server :rcon-health (:instance data)]
         {:health            (:health data)
          :last-heartbeat-at (:last-heartbeat-at data)
          :failures          (or (:failures data) 0)}))

(defmethod handle-event :server/rcon-state [[_ data]]
  (swap! db/app-db update-in [:server :rcon-connections]
         (fn [conns]
           (mapv (fn [c]
                   (if (= (:instance c) (:instance data))
                     (assoc c :last-query-at (:last-query-at data))
                     c))
                 conns))))

(defmethod handle-event :server/preference-change [[_ key value]]
  (when (= key "theme")
    (dispatch! [:set-theme value])))

(defn- collect-expanded-paths
  "Walk a tree and return a set of paths that are currently expanded."
  [tree]
  (reduce (fn [acc {:keys [path type children expanded?]}]
            (let [acc (if (and (= type :dir) expanded?)
                        (conj acc path)
                        acc)]
              (if children
                (into acc (collect-expanded-paths children))
                acc)))
          #{}
          tree))

(defn- apply-expanded-state
  "Walk a new tree and restore expanded? flags from a set of expanded paths."
  [tree expanded-paths]
  (mapv (fn [node]
          (if (:children node)
            (-> node
                (assoc :expanded? (contains? expanded-paths (:path node)))
                (update :children apply-expanded-state expanded-paths))
            node))
        tree))

(defmethod handle-event :server/project [[_ data]]
  (swap! db/app-db
         (fn [db]
           (let [old-tree (:file-tree db)
                 new-tree (or (:file-tree data) [])
                 expanded-paths (collect-expanded-paths old-tree)
                 merged-tree (if (seq expanded-paths)
                               (apply-expanded-state new-tree expanded-paths)
                               new-tree)]
             (-> db
                 (assoc-in [:project :current-path] (:current-path data))
                 (assoc-in [:project :config] (:config data))
                 (assoc :file-tree merged-tree))))))

;; ---------------------------------------------------------------------------
;; Server commands (dispatch over WebSocket, results come back via broadcast)
;; ---------------------------------------------------------------------------

(defmethod handle-event :cmd/validate [[_ params]]
  (let [target (or (:target params) "check")]
    (swap! db/app-db assoc-in [:server :pipeline-status]
           {:target target :status :running})
    (-> (ws/send-command! "POST" "/api/validate" {:path (or (:path params) ".")})
        (.then (fn [res]
                 (when-let [diags (:diagnostics res)]
                   (dispatch! [:server/diagnostics diags]))
                 (dispatch! [:server/pipeline-result target :ok])))
        (.catch (fn [_]
                  (dispatch! [:server/pipeline-result target :error]))))))

(defmethod handle-event :cmd/rcon-exec [[_ params]]
  (dispatch! [:console-append {:type :command :text (str "> " (:command params))}])
  (-> (ws/send-command! "POST" "/api/rcon/exec"
                        {:instance (or (:instance params) "__gui__")
                         :command  (:command params)})
      (.then (fn [res]
               (dispatch! [:console-append
                           {:type :response
                            :text (or (:response res) "(no response)")}])))
      (.catch (fn [err]
                (dispatch! [:console-append
                            {:type :error
                             :text (str "Error: " (.-message err))}])))))

(defmethod handle-event :cmd/save-preference [[_ key value]]
  (-> (ws/send-command! "POST" "/api/preferences" {:key key :value value})
      (.catch (fn [_]))))

(defmethod handle-event :cmd/open-project [[_ path]]
  (-> (ws/send-command! "POST" "/api/project/open" {:path path})
      (.then (fn [res]
               (dispatch! [:server/project {:current-path (:path res)
                                            :config       nil}])
               ;; Re-fetch full project state to get file tree
               (dispatch! [:cmd/fetch-project])))
      (.catch (fn [err]
                (js/console.error "Failed to open project:" (.-message err))))))

(defmethod handle-event :cmd/fetch-project [_]
  (-> (ws/send-command! "GET" "/api/project")
      (.then (fn [res]
               (when (:has-project res)
                 (dispatch! [:server/project res]))))
      (.catch (fn [_]))))

(defmethod handle-event :cmd/fetch-initial-data [_]
  ;; Server status
  (-> (ws/send-command! "GET" "/api/status")
      (.then (fn [res] (dispatch! [:server/status res])))
      (.catch (fn [_])))
  ;; Capabilities
  (-> (ws/send-command! "GET" "/api/capabilities")
      (.then (fn [res] (dispatch! [:server/capabilities (:capabilities res)])))
      (.catch (fn [_])))
  ;; Diagnostics
  (-> (ws/send-command! "GET" "/api/diagnostics")
      (.then (fn [res]
               (when-let [mods (:mods res)]
                 (let [all-diags (mapcat (fn [[_path data]] (:diagnostics data)) mods)]
                   (dispatch! [:server/diagnostics all-diags])))))
      (.catch (fn [_])))
  ;; Preferences (theme)
  (-> (ws/send-command! "GET" "/api/preferences")
      (.then (fn [res]
               (when-let [theme (:theme res)]
                 (dispatch! [:set-theme theme]))))
      (.catch (fn [_])))
  ;; RCON health
  (-> (ws/send-command! "GET" "/api/rcon/health")
      (.then (fn [res]
               (when-let [conns (:connections res)]
                 (swap! db/app-db assoc-in [:server :rcon-health] conns))))
      (.catch (fn [_])))
  ;; Project state
  (dispatch! [:cmd/fetch-project]))
