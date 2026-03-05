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
