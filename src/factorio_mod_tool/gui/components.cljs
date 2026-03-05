(ns factorio-mod-tool.gui.components
  "UI components for the Factorio Mod Tool GUI."
  (:require [reagent.core :as r]
            [factorio-mod-tool.gui.state :as state]
            [factorio-mod-tool.gui.dispatch :as dispatch]
            ["highlight.js/lib/core" :as hljs]
            ["highlight.js/lib/languages/lua" :as lua-lang]
            ["highlight.js/lib/languages/json" :as json-lang]
            ["highlight.js/lib/languages/ini" :as ini-lang]
            ["highlight.js/lib/languages/markdown" :as markdown-lang]
            ["highlight.js/lib/languages/xml" :as xml-lang]
            ["highlight.js/lib/languages/javascript" :as js-lang]
            ["highlight.js/lib/languages/plaintext" :as plaintext-lang]))

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
        :on-click #(dispatch/dispatch! [:cmd/validate {:target target :path "."}])}
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
       :on-click #(dispatch/dispatch! [:navigate id])}
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
;; Shared utilities
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

;; ---------------------------------------------------------------------------
;; File tree
;; ---------------------------------------------------------------------------

(defn- node-name
  "Derive display name from a path (last segment)."
  [path]
  (let [parts (.split (str path) "/")]
    (aget parts (dec (.-length parts)))))

(defn- tree-node [{:keys [path type children expanded? mtime]} depth]
  (let [indent (* depth 16)
        display-name (node-name path)]
    [:<>
     [:div.tree-item
      {:class (cond-> ""
                (= type :dir) (str " directory")
                (= path @state/selected-file) (str " selected"))
       :style {:padding-left (str (+ 12 indent) "px")}
       :on-click (fn []
                   (if (= type :dir)
                     (dispatch/dispatch! [:toggle-tree-node path])
                     (dispatch/dispatch! [:select-file path])))}
      [:span.tree-icon (if (= type :dir) (if expanded? "\u25BE" "\u25B8") "\u25CB")]
      [:span.tree-name display-name]
      (when mtime
        [:span.tree-mtime (relative-time mtime)])]
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
;; Highlight.js language registration
;; ---------------------------------------------------------------------------

(.registerLanguage hljs "lua" lua-lang)
(.registerLanguage hljs "json" json-lang)
(.registerLanguage hljs "ini" ini-lang)
(.registerLanguage hljs "markdown" markdown-lang)
(.registerLanguage hljs "xml" xml-lang)
(.registerLanguage hljs "javascript" js-lang)
(.registerLanguage hljs "plaintext" plaintext-lang)

(def ^:private ext->language
  "Map file extensions to highlight.js language names."
  {".lua"  "lua"
   ".json" "json"
   ".cfg"  "ini"
   ".ini"  "ini"
   ".md"   "markdown"
   ".xml"  "xml"
   ".html" "xml"
   ".js"   "javascript"
   ".cljs" "javascript"
   ".clj"  "javascript"
   ".edn"  "javascript"
   ".txt"  "plaintext"})

(defn- detect-language
  "Detect highlight.js language from a file path extension."
  [path]
  (when path
    (let [dot-idx (.lastIndexOf path ".")]
      (when (pos? dot-idx)
        (let [ext (.substring path dot-idx)]
          (get ext->language (.toLowerCase ext) "plaintext"))))))

;; ---------------------------------------------------------------------------
;; Center panel (file viewer with syntax highlighting)
;; ---------------------------------------------------------------------------

(defn- highlighted-code
  "Renders file content with syntax highlighting using highlight.js.
   Re-highlights when content or selected file changes."
  []
  (let [code-ref (atom nil)
        highlight! (fn []
                     (when-let [el @code-ref]
                       (let [content @state/file-content
                             lang    (detect-language @state/selected-file)]
                         (when content
                           (set! (.-className el) (str "language-" (or lang "plaintext")))
                           (set! (.-textContent el) content)
                           (.removeAttribute el "data-highlighted")
                           (.highlightElement hljs el)))))]
    (r/create-class
     {:component-did-mount  (fn [_] (highlight!))
      :component-did-update (fn [_] (highlight!))
      :reagent-render
      (fn []
        @state/file-content
        @state/selected-file
        [:pre.file-code-block
         [:code {:ref #(reset! code-ref %)}]])})))

(defn- image-viewer
  "Renders a base64-encoded image with its MIME type."
  []
  (let [content   @state/file-content
        mime-type (or @state/file-mime-type "image/png")]
    [:div.image-viewer
     [:img {:src (str "data:" mime-type ";base64," content)
            :alt @state/selected-file
            :style {:max-width "100%" :max-height "100%"}}]]))

(defn- binary-placeholder
  "Placeholder for non-viewable binary files."
  []
  [:div.binary-placeholder
   [:div.binary-icon "\uD83D\uDCC4"]
   [:div.binary-message "Binary file"]
   [:div.binary-detail (str (when-let [meta @state/file-meta]
                              (let [size (:size meta)]
                                (cond
                                  (nil? size)       ""
                                  (< size 1024)     (str size " bytes")
                                  (< size 1048576)  (str (.toFixed (/ size 1024) 1) " KB")
                                  :else             (str (.toFixed (/ size 1048576) 1) " MB")))))]])

(defn- lua-file? [path]
  (and path (.endsWith (.toLowerCase (str path)) ".lua")))

(defn- check-lua-live-indicator []
  (let [result @state/check-lua-live-result]
    (when result
      [:div.check-lua-live-result
       {:class (name (or (:status result) :unknown))}
       (case (:status result)
         :checking [:span.check-status "\u23F3 Checking..."]
         :ok       [:span.check-status "\u2713 OK"]
         :error    [:span.check-status "\u2717 " (:result result)]
         nil)])))

(defn center-panel []
  [:div.center-panel
   (if @state/selected-file
     (let [meta      @state/file-meta
           loading?  @state/file-loading?
           file-type (or @state/file-type :text)
           file      @state/selected-file]
       [:<>
        [:div.file-tab-bar
         [:div.file-tab.active
          file
          (when (:mtime meta)
            [:span.file-tab-mtime (relative-time (:mtime meta))])]
         (when (lua-file? file)
           [:button.check-lua-live-btn
            {:on-click #(dispatch/dispatch! [:cmd/check-lua-live file])
             :title    "Send to Factorio and check if it loads"}
            "\u25B6 Check Live"])]
        [check-lua-live-indicator]
        [:div.file-content
         (cond
           loading?                [:div.file-loading "Loading..."]
           (= file-type :image)   [image-viewer]
           (= file-type :binary)  [binary-placeholder]
           @state/file-content     [highlighted-code]
           :else                   [:div.empty-state "Select a file to view its contents."])]])
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
                         (dispatch/dispatch! [:select-file (:file d)])))}
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
                        (dispatch/dispatch! [:set-theme id])
                        (dispatch/dispatch! [:cmd/save-preference "theme" id]))}
           [:div.theme-option-header
            [:span.theme-radio (if (= id current) "\u25C9" "\u25CB")]
            [:span.theme-option-label label]]
           [:p.theme-option-desc desc]])]]
      [:div.settings-group
       [:label.settings-label "Preview"]
       [code-preview]]]]))

;; ---------------------------------------------------------------------------
;; Connection dashboard — hero/secondary layout
;; ---------------------------------------------------------------------------

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

(defn- health-class [health]
  (case health
    "alive"       "ok"
    "unreachable" "error"
    "timeout"     "warning"
    "unknown"))

(defn- hero-status-class [health]
  (case health
    "alive"       "hero-connected"
    "unreachable" "hero-disconnected"
    "timeout"     "hero-stale"
    "hero-unknown"))

;; --- Hero Section: Primary RCON Connection ---

(defn- hero-rcon-panel
  "Large, prominent display for the primary Factorio RCON connection."
  [conn health-info]
  (let [health  (or (:health health-info) "unknown")
        {:keys [instance host port last-query-at]} conn]
    [:div.hero-connection {:class (hero-status-class health)}
     [:div.hero-status-row
      [:div.hero-status-indicator {:class (health-class health)}]
      [:span.hero-status-label
       (case health
         "alive"       "Connected"
         "unreachable" "Disconnected"
         "timeout"     "Stale"
         "Unknown")]]
     [:div.hero-instance-name (or instance "Factorio")]
     [:div.hero-host (str host ":" port)]
     [:div.hero-details
      (when (:last-heartbeat-at health-info)
        [:div.hero-detail
         [:span.hero-detail-label "Last Heartbeat"]
         [:span.hero-detail-value (relative-time (:last-heartbeat-at health-info))]])
      [:div.hero-detail
       [:span.hero-detail-label "Last Query"]
       [:span.hero-detail-value
        (if last-query-at (relative-time last-query-at) "Never")]]
      (when (pos? (or (:failures health-info) 0))
        [:div.hero-detail.hero-failures
         [:span.hero-detail-label "Failures"]
         [:span.hero-detail-value (str (:failures health-info))]])]]))

(defn- hero-no-connection []
  [:div.hero-connection.hero-unknown
   [:div.hero-status-row
    [:div.hero-status-indicator.unknown]
    [:span.hero-status-label "No Connection"]]
   [:div.hero-instance-name "Factorio"]
   [:div.hero-host "Not configured"]
   [:div.hero-hint "Configure an RCON connection to connect to Factorio"]])

;; --- RCON Instances List (multi-instance) ---

(defn- rcon-instance-row
  "Compact row for a secondary RCON instance."
  [conn health-info]
  (let [health (or (:health health-info) "unknown")
        {:keys [instance host port last-query-at]} conn]
    [:div.rcon-row
     [:span.rcon-row-dot {:class (health-class health)}]
     [:span.rcon-row-name instance]
     [:span.rcon-row-host (str host ":" port)]
     [:span.rcon-row-activity
      (if last-query-at (relative-time last-query-at) "No activity")]]))

;; --- Collapsible Section ---

(defn- collapsible-section
  "A section with a clickable header that toggles visibility."
  [title expanded? on-toggle & children]
  [:div.collapsible-section
   [:div.collapsible-header {:on-click on-toggle}
    [:span.collapsible-chevron (if expanded? "\u25BE" "\u25B8")]
    [:span.collapsible-title title]]
   (when expanded?
     (into [:div.collapsible-body] children))])

;; --- Toolchain Health ---

(defn- toolchain-health []
  (let [caps @state/capabilities]
    [:div.toolchain-checklist
     (if (seq caps)
       (for [[k v] caps]
         ^{:key k}
         [:div.toolchain-item
          [:span.toolchain-icon {:class (if (:available v) "ok" "error")}
           (if (:available v) "\u2713" "\u2717")]
          [:span.toolchain-label k]])
       [:div.toolchain-detecting "Detecting capabilities..."])]))

;; --- Server Internals ---

(defn- server-internals []
  (let [server    @state/server-status
        ws-status @state/connection-status
        ws-clients (or (:ws-client-count @state/server-status) 0)
        current   @state/pipeline-status
        results   @state/pipeline-results
        targets   ["check" "lint" "test" "pack"]]
    [:div.server-internals
     ;; HTTP
     [:div.internals-group
      [:div.internals-group-title "HTTP Server"]
      (if server
        [:div.internals-details
         [:span.internals-badge.ok "Running"]
         (when (:port server)
           [:span.internals-meta (str ":" (:port server))])
         (when (:started-at server)
           [:span.internals-meta (str "up " (uptime-str (:started-at server)))])]
        [:div.internals-details
         [:span.internals-badge.error "Stopped"]])]
     ;; WebSocket
     [:div.internals-group
      [:div.internals-group-title "WebSocket"]
      [:div.internals-details
       [:span.internals-badge {:class (name ws-status)}
        (case ws-status
          :connected    "Connected"
          :disconnected "Disconnected"
          :error        "Error"
          "Unknown")]
       [:span.internals-meta (str ws-clients " client" (when (not= ws-clients 1) "s"))]]]
     ;; Pipeline
     [:div.internals-group
      [:div.internals-group-title "Pipeline"]
      [:div.pipeline-summary
       (for [target targets]
         (let [result (get results target)]
           ^{:key target}
           [:span.pipeline-target
            {:class (cond
                      (and current (= target (:target current)) (= :running (:status current))) "warning"
                      (= :ok (:status result)) "ok"
                      (= :error (:status result)) "error"
                      :else "idle")}
            target]))]]]))

;; --- Main Connection Panel ---

(defn connection-panel []
  (let [tick              (r/atom 0)
        interval-id       (atom nil)
        toolchain-open?   (r/atom false)
        internals-open?   (r/atom false)]
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
              health-map @state/rcon-health
              primary    (first conns)
              secondary  (rest conns)]
          [:div.connection-dashboard
           [:div.panel-header "Connection Health"]
           ;; Hero section
           [:div.hero-section
            (if primary
              [hero-rcon-panel primary (get health-map (:instance primary))]
              [hero-no-connection])]
           ;; Secondary RCON instances (if any)
           (when (seq secondary)
             [:div.rcon-instances
              [:div.rcon-instances-header "Other Instances"]
              (for [conn secondary]
                ^{:key (:instance conn)}
                [rcon-instance-row conn (get health-map (:instance conn))])])
           ;; Collapsible diagnostics sections
           [collapsible-section "Toolchain Health" @toolchain-open?
            #(swap! toolchain-open? not)
            [toolchain-health]]
           [collapsible-section "Server Internals" @internals-open?
            #(swap! internals-open? not)
            [server-internals]]]))})))

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
                  (dispatch/dispatch! [:cmd/rcon-exec {:command cmd}])))))}]]])))
