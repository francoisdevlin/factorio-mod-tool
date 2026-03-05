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

(defn- health-class [health]
  (case health
    "alive"       "health-alive"
    "unreachable" "health-unreachable"
    "timeout"     "health-timeout"
    "health-unknown"))

(defn- health-label [health]
  (case health
    "alive"       "Alive"
    "unreachable" "Unreachable"
    "timeout"     "Timeout"
    "Unknown"))

(defn connection-panel []
  (let [health-map @state/rcon-health]
    [:div.connection-panel
     [:div.panel-header "RCON Connections"]
     (if (empty? health-map)
       [:div.empty-state "No RCON connections active"]
       [:div.connection-list
        (for [[instance-name info] health-map]
          (let [health (or (:health info) "unknown")]
            ^{:key instance-name}
            [:div.connection-card
             [:div.connection-header
              [:span.connection-name instance-name]
              [:span.health-badge {:class (health-class health)}
               (health-label health)]]
             [:div.connection-details
              (when (:host info)
                [:span.connection-host (str (:host info) ":" (:port info))])
              (when (:last-heartbeat-at info)
                [:span.connection-heartbeat
                 (str "Last heartbeat: " (:last-heartbeat-at info))])
              (when (pos? (or (:failures info) 0))
                [:span.connection-failures
                 (str "Failures: " (:failures info))])]]))])]))

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
