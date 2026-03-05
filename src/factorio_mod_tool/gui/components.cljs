(ns factorio-mod-tool.gui.components
  "UI components for the Factorio Mod Tool GUI."
  (:require [reagent.core :as r]
            [factorio-mod-tool.gui.state :as state]
            [factorio-mod-tool.gui.ws :as ws]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/lua" :as lua-lang]))

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
                                 (reset! state/pipeline-status {:target target :status :ok})
                                 (swap! state/pipeline-results assoc target
                                        {:status :ok :timestamp (.toISOString (js/Date.))})))
                        (.catch (fn [_err]
                                  (reset! state/pipeline-status {:target target :status :error})
                                  (swap! state/pipeline-results assoc target
                                         {:status :error :timestamp (.toISOString (js/Date.))})))))}
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
;; Lua code preview (highlight.js)
;; ---------------------------------------------------------------------------

(.registerLanguage hljs "lua" lua-lang)

(def sample-lua
  "-- Factorio mod: data stage
local util = require(\"util\")

data:extend({
  {
    type = \"recipe\",
    name = \"advanced-circuit\",
    enabled = false,
    energy_required = 6,
    ingredients = {
      {\"electronic-circuit\", 2},
      {\"copper-cable\", 4}
    },
    result = \"advanced-circuit\"
  }
})

-- Runtime event handler
script.on_event(defines.events.on_player_created, function(event)
  local player = game.players[event.player_index]
  player.print(\"Welcome to the factory!\")
end)")

(defn code-preview []
  (let [code-ref (atom nil)
        highlight! (fn []
                     (when-let [el @code-ref]
                       (set! (.-className el) "language-lua")
                       (set! (.-textContent el) sample-lua)
                       (.removeAttribute el "data-highlighted")
                       (.highlightElement hljs el)))]
    (r/create-class
     {:component-did-mount  (fn [_] (highlight!))
      :component-did-update (fn [_] (highlight!))
      :reagent-render
      (fn []
        @state/current-theme ;; deref to re-render on theme change
        [:div.code-preview
         [:div.code-preview-header "Code Preview"]
         [:pre.code-preview-block
          [:code {:ref #(reset! code-ref %)}]]])})))

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
           [:p.theme-option-desc desc]])]]
      [:div.settings-group
       [:label.settings-label "Preview"]
       [code-preview]]]]))

;; ---------------------------------------------------------------------------
;; Connection dashboard
;; ---------------------------------------------------------------------------

(defn- relative-time
  "Returns a human-readable relative time string for an ISO timestamp."
  [iso-str]
  (when iso-str
    (let [then (.getTime (js/Date. iso-str))
          now  (.getTime (js/Date.))
          diff (- now then)
          secs (Math/floor (/ diff 1000))
          mins (Math/floor (/ secs 60))
          hrs  (Math/floor (/ mins 60))]
      (cond
        (< secs 5)  "just now"
        (< secs 60) (str secs "s ago")
        (< mins 60) (str mins "m ago")
        :else       (str hrs "h " (mod mins 60) "m ago")))))

(defn- uptime-str
  "Returns a human-readable uptime from an ISO start time."
  [iso-str]
  (when iso-str
    (let [then (.getTime (js/Date. iso-str))
          now  (.getTime (js/Date.))
          secs (Math/floor (/ (- now then) 1000))
          mins (Math/floor (/ secs 60))
          hrs  (Math/floor (/ mins 60))]
      (cond
        (< secs 60) (str secs "s")
        (< mins 60) (str mins "m " (mod secs 60) "s")
        :else       (str hrs "h " (mod mins 60) "m")))))

(defn- status-dot-class [status]
  (case status
    (:ok :alive :connected :running :available) "ok"
    (:error :unreachable :disconnected)          "error"
    (:warning :timeout :stale)                   "warning"
    "unknown"))

(defn- dashboard-card
  "Generic dashboard card with status dot, title, and child content."
  [status title & children]
  [:div.dashboard-card
   [:div.dashboard-card-header
    [:span.dashboard-dot {:class (status-dot-class status)}]
    [:span.dashboard-card-title title]]
   (into [:div.dashboard-card-body] children)])

(defn- http-server-card []
  (let [server @state/server-status]
    [dashboard-card
     (if server :ok :disconnected)
     "HTTP Server"
     (if server
       [:<>
        [:div.dashboard-detail
         [:span.detail-label "Status"]
         [:span.detail-value "Running"]]
        (when (:port server)
          [:div.dashboard-detail
           [:span.detail-label "Port"]
           [:span.detail-value (str (:port server))]])
        (when (:started-at server)
          [:div.dashboard-detail
           [:span.detail-label "Uptime"]
           [:span.detail-value (uptime-str (:started-at server))]])
        [:div.dashboard-detail
         [:span.detail-label "Version"]
         [:span.detail-value (or (:version server) "?")]]]
       [:div.dashboard-empty "Not connected"])]))

(defn- websocket-card []
  (let [ws-status @state/connection-status
        server    @state/server-status
        clients   (or (:ws-client-count server) 0)]
    [dashboard-card
     ws-status
     "WebSocket"
     [:div.dashboard-detail
      [:span.detail-label "Status"]
      [:span.detail-value {:class (name ws-status)}
       (case ws-status
         :connected    "Connected"
         :disconnected "Disconnected"
         :error        "Error"
         "Unknown")]]
     [:div.dashboard-detail
      [:span.detail-label "Clients"]
      [:span.detail-value (str clients)]]]))

(defn- health-class [health]
  (case health
    "alive"       "ok"
    "unreachable" "error"
    "timeout"     "warning"
    "unknown"))

(defn- rcon-card [{:keys [instance host port last-query-at]} health-info]
  (let [health (or (:health health-info) "unknown")]
    [dashboard-card
     (keyword (health-class health))
     (str "RCON: " instance)
     [:div.dashboard-detail
      [:span.detail-label "Host"]
      [:span.detail-value (str host ":" port)]]
     [:div.dashboard-detail
      [:span.detail-label "Health"]
      [:span.detail-value {:class (health-class health)}
       (case health
         "alive"       "Alive"
         "unreachable" "Unreachable"
         "timeout"     "Timeout"
         "Unknown")]]
     [:div.dashboard-detail
      [:span.detail-label "Last Query"]
      [:span.detail-value
       (if last-query-at (relative-time last-query-at) "Never")]]
     (when (:last-heartbeat-at health-info)
       [:div.dashboard-detail
        [:span.detail-label "Heartbeat"]
        [:span.detail-value (relative-time (:last-heartbeat-at health-info))]])
     (when (pos? (or (:failures health-info) 0))
       [:div.dashboard-detail
        [:span.detail-label "Failures"]
        [:span.detail-value.error (str (:failures health-info))]])]))

(defn- capabilities-card []
  (let [caps @state/capabilities]
    [dashboard-card
     (if (and caps (some (fn [[_ v]] (:available v)) caps)) :ok :warning)
     "Capabilities"
     (if (seq caps)
       (for [[k v] caps]
         ^{:key k}
         [:div.dashboard-detail
          [:span.detail-label k]
          [:span.detail-value {:class (if (:available v) "ok" "error")}
           (if (:available v) "Available" "Missing")]])
       [:div.dashboard-empty "Detecting..."])]))

(defn- pipeline-card []
  (let [current @state/pipeline-status
        results @state/pipeline-results
        targets ["check" "lint" "test" "pack"]]
    [dashboard-card
     (cond
       (and current (= :running (:status current))) :warning
       (some #(= :error (:status (get results %))) targets) :error
       (some #(get results %) targets) :ok
       :else :unknown)
     "Pipeline"
     (for [target targets]
       (let [result (get results target)]
         ^{:key target}
         [:div.dashboard-detail
          [:span.detail-label target]
          [:span.detail-value
           {:class (cond
                     (and current (= target (:target current)) (= :running (:status current))) "warning"
                     (= :ok (:status result)) "ok"
                     (= :error (:status result)) "error"
                     :else "")}
           (cond
             (and current (= target (:target current)) (= :running (:status current))) "Running..."
             (= :ok (:status result)) "Passed"
             (= :error (:status result)) "Failed"
             :else "Not run")]]))]))

(defn connection-panel []
  (let [tick (r/atom 0)
        interval-id (atom nil)]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (reset! interval-id
                (js/setInterval #(swap! tick inc) 5000)))
      :component-will-unmount
      (fn [_]
        (when @interval-id
          (js/clearInterval @interval-id)))
      :reagent-render
      (fn []
        (let [_          @tick
              conns      @state/rcon-connections
              health-map @state/rcon-health]
          [:div.connection-dashboard
           [:div.panel-header "Connection Status"]
           [:div.dashboard-grid
            [http-server-card]
            [websocket-card]
            (for [conn conns]
              ^{:key (:instance conn)}
              [rcon-card conn (get health-map (:instance conn))])
            [capabilities-card]
            [pipeline-card]]
           (when (empty? conns)
             [:div.dashboard-hint "No RCON connections configured"])]))})))

;; ---------------------------------------------------------------------------
;; Section routing
;; ---------------------------------------------------------------------------

(defn section-content []
  (case @state/active-section
    :projects   [:<>
                 [file-tree-panel]
                 [center-panel]
                 [diagnostics-panel]]
    :connection  [connection-panel]
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
