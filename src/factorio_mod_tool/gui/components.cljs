(ns factorio-mod-tool.gui.components
  "UI components for the Factorio Mod Tool GUI."
  (:require [reagent.core :as r]
            [factorio-mod-tool.gui.state :as state]
            [factorio-mod-tool.gui.ws :as ws]))

;; ---------------------------------------------------------------------------
;; Top bar
;; ---------------------------------------------------------------------------

(defn status-indicator [label status]
  [:span
   [:span.status-dot {:class (name status)}]
   label])

(defn top-bar []
  [:div.top-bar
   [:h1 "fmod"]
   [:div.pipeline-toolbar
    (for [target ["check" "lint" "test" "pack"]]
      ^{:key target}
      [:button.pipeline-btn
       {:class (when (= target (:target @state/pipeline-status)) "running")
        :on-click (fn []
                    (reset! state/pipeline-status {:target target :status :running})
                    (-> (ws/send-command! "POST" (str "/api/validate")
                                          {:path "."})
                        (.then (fn [res]
                                 (when-let [diags (:diagnostics res)]
                                   (reset! state/diagnostics diags))
                                 (reset! state/pipeline-status {:target target :status :ok})))
                        (.catch (fn [_err]
                                  (reset! state/pipeline-status {:target target :status :error})))))}
       target])]
   [:div.status-indicators
    [status-indicator "Server" @state/connection-status]
    (when-let [caps @state/capabilities]
      (for [[k v] caps]
        ^{:key k}
        [:span.capability-badge {:class (if (:available v) "available" "missing")}
         k]))]])

;; ---------------------------------------------------------------------------
;; Navigation bar
;; ---------------------------------------------------------------------------

(def nav-sections
  [{:id :projects   :label "Projects"}
   {:id :connection :label "Connection"}
   {:id :prototypes :label "Prototypes"}
   {:id :blueprints :label "Blueprints"}
   {:id :tech-tree  :label "Tech Tree"}
   {:id :settings   :label "Settings"}])

(defn nav-bar []
  [:nav.nav-bar
   (for [{:keys [id label]} nav-sections]
     ^{:key id}
     [:button.nav-item
      {:class (when (= id @state/active-section) "active")
       :on-click #(reset! state/active-section id)}
      label])])

;; ---------------------------------------------------------------------------
;; Placeholder panels for future sections
;; ---------------------------------------------------------------------------

(defn placeholder-panel [title description]
  [:div.placeholder-panel
   [:div.placeholder-content
    [:h2 title]
    [:p description]]])

;; ---------------------------------------------------------------------------
;; File tree
;; ---------------------------------------------------------------------------

(defn- tree-node [{:keys [name path type children expanded?]} depth]
  (let [indent (* depth 16)]
    [:<>
     [:div.tree-item
      {:class (cond-> ""
                (= type :dir) (str " directory")
                (= path @state/selected-file) (str " selected"))
       :style {:padding-left (str (+ 12 indent) "px")}
       :on-click (fn []
                   (if (= type :dir)
                     ;; Toggle expansion
                     (swap! state/file-tree
                            (fn [tree]
                              (letfn [(toggle [nodes]
                                        (mapv (fn [n]
                                                (if (= (:path n) path)
                                                  (update n :expanded? not)
                                                  (if (:children n)
                                                    (update n :children toggle)
                                                    n)))
                                              nodes))]
                                (toggle tree))))
                     ;; Select file
                     (do
                       (reset! state/selected-file path)
                       (reset! state/file-content nil)
                       (-> (ws/send-command! "POST" "/api/parse"
                                              {:source ""})
                           (.catch (fn [_]))))))}
      [:span.tree-icon (if (= type :dir) (if expanded? "\u25BE" "\u25B8") "\u25CB")]
      name]
     (when (and (= type :dir) expanded? children)
       (for [child children]
         ^{:key (:path child)}
         [tree-node child (inc depth)]))]))

(defn file-tree-panel []
  [:div.file-tree
   [:div.panel-header "Files"]
   (if (empty? @state/file-tree)
     [:div.empty-state "No project loaded"]
     (for [node @state/file-tree]
       ^{:key (:path node)}
       [tree-node node 0]))])

;; ---------------------------------------------------------------------------
;; Center panel (file viewer)
;; ---------------------------------------------------------------------------

(defn center-panel []
  [:div.center-panel
   (if @state/selected-file
     [:<>
      [:div.file-tab-bar
       [:div.file-tab.active @state/selected-file]]
      [:div.file-content
       (or @state/file-content "Select a file to view its contents.")]]
     [:div.empty-state "Select a file from the tree to view"])])

;; ---------------------------------------------------------------------------
;; Diagnostics panel
;; ---------------------------------------------------------------------------

(defn diagnostics-panel []
  (let [diags @state/diagnostics
        error-count (count (filter #(= :error (:severity %)) diags))
        warn-count (count (filter #(= :warning (:severity %)) diags))]
    [:div.diagnostics-panel
     [:div.panel-header
      "Diagnostics"
      (when (pos? (count diags))
        [:span.count (str (count diags))])]
     (if (empty? diags)
       [:div.empty-state "No diagnostics"]
       (for [[idx d] (map-indexed vector diags)]
         ^{:key idx}
         [:div.diagnostic-item
          {:on-click (fn []
                       (when (:file d)
                         (reset! state/selected-file (:file d))))}
          [:span.diagnostic-severity
           {:class (name (or (:severity d) :info))}
           (case (:severity d)
             :error "\u2717"
             :warning "\u26A0"
             "\u2139")]
          (:message d)
          (when (:file d)
            [:div.diagnostic-file (:file d)])]))]))

;; ---------------------------------------------------------------------------
;; Settings panel
;; ---------------------------------------------------------------------------

(def theme-options
  [{:id "dark"     :label "Dark"     :desc "Default dark color scheme"}
   {:id "light"    :label "Light"    :desc "Light backgrounds, dark text"}
   {:id "factorio" :label "Factorio" :desc "Industrial orange/amber, inspired by the Factorio forums"}])

(defn- apply-theme! [theme]
  (.setAttribute (.-documentElement js/document) "data-theme" theme))

(defn settings-panel []
  (let [current @state/current-theme]
    (apply-theme! current)
    [:div.settings-panel
     [:div.settings-section
      [:h2.settings-heading "Appearance"]
      [:div.settings-group
       [:label.settings-label "Theme"]
       [:div.theme-options
        (for [{:keys [id label desc]} theme-options]
          ^{:key id}
          [:div.theme-option
           {:class    (when (= id current) "selected")
            :on-click (fn []
                        (reset! state/current-theme id)
                        (apply-theme! id)
                        (-> (ws/send-command! "POST" "/api/preferences"
                                              {:key "theme" :value id})
                            (.catch (fn [_]))))}
           [:div.theme-option-header
            [:span.theme-radio (if (= id current) "\u25C9" "\u25CB")]
            [:span.theme-option-label label]]
           [:p.theme-option-desc desc]])]]]]))

;; ---------------------------------------------------------------------------
;; Connection dashboard
;; ---------------------------------------------------------------------------

(defn- status-dot-cls [status]
  (case status
    (:ok :connected :running :available :alive) "dot-ok"
    (:disconnected :unreachable)                "dot-error"
    :error                                      "dot-error"
    :warning                                    "dot-warning"
    (:missing :timeout)                         "dot-warn"
    "dot-unknown"))

(defn- conn-card [title status-kw detail & children]
  [:div.conn-card
   [:div.conn-card-header
    [:span.conn-dot {:class (status-dot-cls status-kw)}]
    [:span.conn-card-title title]
    [:span.conn-card-status (name status-kw)]]
   [:div.conn-card-body
    (when detail [:div.conn-card-detail detail])
    (into [:<>] children)]])

(defn- http-card []
  (let [status @state/server-status
        ws-ok? (= @state/connection-status :connected)]
    [conn-card
     "HTTP Server"
     (if ws-ok? :running :disconnected)
     (if ws-ok?
       (str "Port " (or (:port status) "—")
            (when-let [v (:version status)] (str " · v" v)))
       "Not reachable")]))

(defn- ws-card []
  (let [status @state/connection-status]
    [conn-card
     "WebSocket"
     status
     (case status
       :connected    "Live connection active"
       :disconnected "No connection"
       :error        "Connection error"
       "Unknown")
     [:div.conn-card-actions
      (if (= status :connected)
        [:button.conn-btn.conn-btn-danger
         {:on-click #(ws/disconnect!)} "Disconnect"]
        [:button.conn-btn
         {:on-click #(ws/connect!
                      (let [loc (.-location js/window)
                            protocol (if (= "https:" (.-protocol loc)) "wss:" "ws:")
                            host (.-host loc)]
                        (str protocol "//" host "/ws")))}
         "Connect"])]]))

(defn- rcon-card []
  (let [conns      @state/rcon-connections
        health-map @state/rcon-health]
    [conn-card
     "Factorio RCON"
     (cond
       (empty? conns)                                          :disconnected
       (every? #(= "alive" (:health (val %))) health-map)     :connected
       (some #(not= "alive" (:health (val %))) health-map)    :warning
       :else                                                   :connected)
     (if (seq conns)
       (str (count conns) " instance" (when (> (count conns) 1) "s"))
       "No instances connected")
     (when (seq conns)
       [:div.conn-card-list
        (for [{:keys [instance host port]} conns]
          (let [health (get-in health-map [instance :health] "unknown")
                info   (get health-map instance)]
            ^{:key instance}
            [:div.conn-card-list-item
             [:span.conn-dot.conn-dot-sm
              {:class (status-dot-cls (keyword health))}]
             [:span.conn-instance instance]
             [:span.conn-endpoint (str host ":" port)]
             (when-let [ts (:last-heartbeat-at info)]
               [:span.conn-meta ts])]))])]))

(defn- capabilities-card []
  (let [caps @state/capabilities]
    [conn-card
     "Capabilities"
     (cond
       (nil? caps)                                :unknown
       (every? #(:available (val %)) caps)        :ok
       (some #(not (:available (val %))) caps)    :warning
       :else                                      :ok)
     (if (nil? caps)
       "Detecting..."
       (let [avail (count (filter #(:available (val %)) caps))
             total (count caps)]
         (str avail "/" total " available")))
     (when caps
       [:div.conn-card-list
        (for [[k v] (sort-by key caps)]
          ^{:key k}
          [:div.conn-card-list-item
           [:span.conn-dot.conn-dot-sm
            {:class (if (:available v) "dot-ok" "dot-error")}]
           [:span (if (keyword? k) (name k) (str k))]
           (when-let [d (:detail v)]
             [:span.conn-cap-detail (str d)])])])]))

(defn- pipeline-card []
  (let [ps @state/pipeline-status]
    [conn-card
     "Pipeline"
     (cond
       (nil? ps)                  :unknown
       (= :running (:status ps)) :running
       (= :ok (:status ps))      :ok
       (= :error (:status ps))   :error
       :else                      :unknown)
     (if ps
       (str (:target ps) " — " (name (:status ps)))
       "No runs yet")]))

(defn- fetch-dashboard-data! []
  (-> (ws/send-command! "GET" "/api/status")
      (.then (fn [res]
               (reset! state/server-status res)
               (when-let [conns (:rcon-connections res)]
                 (reset! state/rcon-connections (vec conns)))))
      (.catch (fn [_])))
  (-> (ws/send-command! "GET" "/api/capabilities")
      (.then (fn [res]
               (when-let [caps (:capabilities res)]
                 (reset! state/capabilities caps))))
      (.catch (fn [_])))
  (-> (ws/send-command! "GET" "/api/rcon/health")
      (.then (fn [res]
               (when-let [conns (:connections res)]
                 (reset! state/rcon-health conns))))
      (.catch (fn [_]))))

(defn connection-panel []
  (r/create-class
   {:component-did-mount
    (fn [_] (fetch-dashboard-data!))
    :reagent-render
    (fn []
      [:div.conn-dashboard
       [:div.conn-grid
        [http-card]
        [ws-card]
        [rcon-card]
        [capabilities-card]
        [pipeline-card]]
       [:div.conn-toolbar
        [:button.conn-btn {:on-click fetch-dashboard-data!}
         "Refresh"]]])}))

;; ---------------------------------------------------------------------------
;; Section routing
;; ---------------------------------------------------------------------------

(defn section-content []
  (case @state/active-section
    :projects   [:<>
                 [file-tree-panel]
                 [center-panel]
                 [diagnostics-panel]]
    :connection [connection-panel]
    :prototypes [placeholder-panel "Prototypes" "Browse and inspect all prototypes"]
    :blueprints [placeholder-panel "Blueprints" "Blueprint lab viewer"]
    :tech-tree  [placeholder-panel "Tech Tree" "Technology tree viewer"]
    :settings   [settings-panel]
    [placeholder-panel "Unknown" "Section not found"]))

;; ---------------------------------------------------------------------------
;; Bottom panel (RCON console)
;; ---------------------------------------------------------------------------

(defn console-panel []
  (let [input-val (r/atom "")]
    (fn []
      [:div.bottom-panel
       [:div.panel-header "Console"]
       [:div.console-output
        (for [[idx line] (map-indexed vector @state/console-lines)]
          ^{:key idx}
          [:div.console-line {:class (name (:type line))}
           (:text line)])]
       [:div.console-input-row
        [:span.console-prompt ">"]
        [:input.console-input
         {:type "text"
          :placeholder "RCON command..."
          :value @input-val
          :on-change #(reset! input-val (.. % -target -value))
          :on-key-down
          (fn [e]
            (when (= (.-key e) "Enter")
              (let [cmd @input-val]
                (when (not-empty cmd)
                  (reset! input-val "")
                  (swap! state/console-lines conj {:type :command :text (str "> " cmd)})
                  (-> (ws/send-command! "POST" "/api/rcon/exec"
                                         {:instance "__gui__" :command cmd})
                      (.then (fn [res]
                               (swap! state/console-lines conj
                                      {:type :response
                                       :text (or (:response res) "(no response)")})))
                      (.catch (fn [err]
                                (swap! state/console-lines conj
                                       {:type :error
                                        :text (str "Error: " (.-message err))}))))))))}]]])))
