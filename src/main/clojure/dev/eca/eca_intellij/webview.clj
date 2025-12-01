(ns dev.eca.eca-intellij.webview
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.github.ericdallo.clj4intellij.app-manager :as app-manager]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.editor :as editor]
   [dev.eca.eca-intellij.extension.server-logs :as server-logs]
   [dev.eca.eca-intellij.shared :as shared])
  (:import
   [com.intellij.openapi.editor Editor]
   [com.intellij.openapi.editor.colors EditorColorsManager]
   [com.intellij.openapi.fileEditor FileEditorManager]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.ui Messages]
   [com.intellij.openapi.util.io FileUtil]
   [com.intellij.openapi.vfs LocalFileSystem]
   [com.intellij.ui ColorUtil JBColor]
   [com.intellij.ui.jcef JBCefBrowser]
   [com.intellij.util.ui JBUI$CurrentTheme$ToolWindow]))

(set! *warn-on-reflection* true)

(defn ^:private hex [jb-color]
  (str "#" (ColorUtil/toHex jb-color)))

(defn ^:private theme-css-map []
  (let [global-scheme (.getGlobalScheme (EditorColorsManager/getInstance))]
    ;; Most come from https://github.com/JetBrains/intellij-community/blob/master/platform/platform-resources/src/themes/metadata/IntelliJPlatform.themeMetadata.json
    {"editor-bg" (hex (.getDefaultBackground global-scheme))
     "editor-fg" (hex (JBColor/namedColor "Editor.foreground"))
     "panel-bg" (hex (JBColor/namedColor "Editor.background"))
     "panel-border" (hex (JBUI$CurrentTheme$ToolWindow/borderColor))
     "input-bg" (hex (JBColor/namedColor "OptionPane.background"))
     "input-fg" (hex (JBColor/namedColor "TextField.caretForeground"))
     "input-placeholder-fg" (hex (JBColor/namedColor "Editor.foreground"))
     "base-border" (if (= (hex (.getDefaultBackground global-scheme)) (hex (JBColor/namedColor "Borders.ContrastBorderColor")))
                     (hex (JBColor/namedColor "Editor.background"))
                     (hex (JBColor/namedColor "Borders.ContrastBorderColor")))
     "base-hover" (hex (JBColor/namedColor "Borders.ContrastBorderColor"))

     "item-selectable-fg" (hex (JBColor/namedColor "Editor.foreground"))
     "link-fg" (hex (JBColor/namedColor "Hyperlink.linkColor"))

     "button-primary-fg" (hex (JBColor/namedColor "Button.default.foreground"))
     "button-primary-bg" (hex (JBColor/namedColor "Button.default.startBackground"))
     "button-primary-border" (hex (JBColor/namedColor "Button.default.borderColor"))
     "button-primary-hover-bg" (hex (JBColor/namedColor "Button.default.focusColor"))
     "button-primary-active-bg" (hex (JBColor/namedColor "Button.default.focusColor"))

     "button-secondary-fg" (hex (JBColor/namedColor "Button.foreground"))
     "button-secondary-bg" (hex (JBColor/namedColor "Button.background"))
     "button-secondary-border" (hex (JBColor/namedColor "Button.default.borderColor"))
     "button-secondary-hover-bg" (hex (JBColor/namedColor "Button.default.focusColor"))
     "button-secondary-active-bg" (hex (JBColor/namedColor "Button.default.startBackground"))

     ;; TODO finish colors map with JBColor
     ;; "success-fg"
     ;; "warning-fg"
     ;; "error-fg"
     ;; "warning-message-fg"
     ;; "confirm-action-bg"
     ;; "confirm-action-fg"
     ;; "diff-unchanged-bg"
     ;; "diff-insert-bg"
     ;; "delete-bg"
     ;; "tooltip-bg"
     ;; "tooltip-fg"
     ;; "toggle-slider-bg"
     ;; "toggle-icon-bg"
     ;; "toggle-bg"
     ;; "context-file-fg"
     ;; "context-directory-fg"
     ;; "context-web-fg"
     ;; "context-repo-map-fg"
     ;; "context-mcp-resource-fg"
     }))

(defn theme-css ^String []
  (str ":root {\n"
       (reduce
        (fn [s [name value]]
          (str s (format "--intellij-%s: %s;\n" name value)))
        ""
        (theme-css-map))
       "}"))

(defn ^:private send-msg! [^Project project msg]
  (when-let [browser ^JBCefBrowser (db/get-in project [:webview-browser])]
    (let [cef-browser (.getCefBrowser browser)]
      (.executeJavaScript (.getCefBrowser browser)
                          (format "window.postMessage(%s, \"*\");"
                                  (json/generate-string (shared/map->camel-cased-map msg)))
                          (.getURL cef-browser)
                          0))))

(defn handle-config-changed [^Project project config]
  (when-let [settings (db/get-in project [:settings])]
    (send-msg! project
               {:type "config/updated"
                :data (merge config settings)})))

(defn handle-server-status-changed [status ^Project project]
  (when (= :running status)
    (send-msg! project {:type "server/setWorkspaceFolders"
                        :data [{:name (.getName project)
                                :uri (str (.toURI (io/file (.getBasePath project))))}]}))
  (when status
    (send-msg! project {:type "server/statusChanged"
                        :data (string/capitalize (name status))})))

(defn add-context-to-system-prompt [context ^Project project]
  (send-msg! project {:type "chat/addContextToSystemPrompt"
                      :data context}))

(defn ^:private on-focus-changed [^Editor editor _]
  (when-let [project (some-> editor .getProject)]
    (app-manager/read-action!
     {:run-fn
      (fn []
        (when-let [vfile (.getVirtualFile editor)]
          (let [caret-model (.getCaretModel editor)
                primary-caret (.getPrimaryCaret caret-model)
                selection-start (.getSelectionStart primary-caret)
                selection-end (.getSelectionEnd primary-caret)
                document (.getDocument editor)
                start-line (.getLineNumber document selection-start)
                start-char (- selection-start (.getLineStartOffset document start-line))
                end-line (.getLineNumber document selection-end)
                end-char (- selection-end (.getLineStartOffset document end-line))]
            (send-msg! project {:type "editor/focusChanged"
                                :data {:type :fileFocused
                                       :path (.getPath vfile)
                                       :position {:start {:line (inc start-line) :character (inc start-char)}
                                                  :end {:line (inc end-line) :character (inc end-char)}}}}))))})))

(defn handle [msg ^Project project]
  (let [{:keys [type data]} (json/parse-string msg keyword)]
    (if (= "webview/ready" type)
      (do
        (handle-server-status-changed (db/get-in project [:status])
                                      project)
        (handle-config-changed project (db/get-in project [:server-config]))
        ;; send current opened editor if any
        (when-let [editor (.getSelectedTextEditor (FileEditorManager/getInstance project))]
          (on-focus-changed editor nil))
        (db/assoc-in project [:on-focus-changed-fns :webview] #'on-focus-changed))
      (when-let [client (api/connected-client project)]
        (case type
          "chat/userPrompt" (let [result @(api/request! client [:chat/prompt {:chatId (:chatId data)
                                                                              :message (:prompt data)
                                                                              :model (:model data)
                                                                              :behavior (:behavior data)
                                                                              :requestId (str (data :requestId))
                                                                              :contexts (:contexts data)}])]
                              (send-msg! project
                                         {:type "chat/newChat"
                                          :data {:id (:chat-id result)}}))
          "chat/queryContext" (let [result @(api/request! client [:chat/queryContext data])]
                                (send-msg! project {:type "chat/queryContext"
                                                    :data result}))
          "chat/queryCommands" (let [result @(api/request! client [:chat/queryCommands data])]
                                 (send-msg! project {:type "chat/queryCommands"
                                                     :data result}))
          "editor/refresh"
          (.refreshFiles (LocalFileSystem/getInstance) [(.findFileByIoFile (LocalFileSystem/getInstance) (io/file (.getBasePath project)))] true true nil)
          "chat/toolCallApprove" (api/notify! client [:chat/toolCallApprove data])
          "chat/toolCallReject" (api/notify! client [:chat/toolCallReject data])
          "chat/promptStop" (api/notify! client [:chat/promptStop data])
          "chat/delete" @(api/request! client [:chat/delete data])
          "chat/rollback" (let [option @(editor/quick-pick [{:id :rollback-messages-and-tools :label "Rollback messages and changes done by tool calls"}
                                                            {:id :rollback-only-messages :label "Rollback only messages"}
                                                            {:id :rollback-only-tools :label "Rollback only changes done by tool calls"}]
                                                           {:title "Select which rollback type"})
                                includes (case (:id option)
                                           :rollback-messages-and-tools ["messages" "tools"]
                                           :rollback-only-messages ["messages"]
                                           :rollback-only-tools ["tools"]
                                           nil)]
                            (when includes
                              @(api/request! client [:chat/rollback (assoc data :includes includes)])))
          "mcp/startServer" (api/notify! client [:mcp/startServer data])
          "mcp/stopServer" (api/notify! client [:mcp/stopServer data])
          "editor/readInput" (app-manager/invoke-later!
                              {:invoke-fn (fn []
                                            (let [user-input (Messages/showInputDialog
                                                              project
                                                              ^String (:message data)
                                                              "Input Required"
                                                              (Messages/getQuestionIcon))]
                                              (send-msg! project {:type "editor/readInput"
                                                                  :data {:requestId (:requestId data)
                                                                         :value user-input}})))})
          "editor/openFile" (let [path (:path data)
                                  sys-ind-path (FileUtil/toSystemIndependentName path)
                                  vfile (.refreshAndFindFileByPath (LocalFileSystem/getInstance) sys-ind-path)]
                              (app-manager/invoke-later! {:invoke-fn
                                                          (fn []
                                                            (when vfile
                                                              (.openFile (FileEditorManager/getInstance project) vfile true)))}))
          "editor/openGlobalConfig"
          (app-manager/invoke-later!
           {:invoke-fn
            (fn []
              (let [home (System/getProperty "user.home")
                    config-home (or (System/getenv "XDG_CONFIG_HOME")
                                    (.getAbsolutePath (io/file home ".config")))
                    config-file (io/file config-home "eca" "config.json")]
                (try
                  (when-let [config-dir (.getParentFile config-file)]
                    (when-not (.exists config-dir)
                      (.mkdirs config-dir)))
                  (when-not (.exists config-file)
                    (spit config-file "{}"))
                  (app-manager/invoke-later!
                   {:invoke-fn (fn []
                                 (if-let [vfile (.refreshAndFindFileByPath (LocalFileSystem/getInstance)
                                                                           (FileUtil/toSystemIndependentName
                                                                            (.getAbsolutePath config-file)))]
                                   (.openFile (FileEditorManager/getInstance project) vfile true)
                                   (Messages/showErrorDialog project
                                                             "Failed to open global config file."
                                                             "ECA Global Config")))})
                  (catch Exception e
                    (Messages/showErrorDialog project
                                              (str "Failed to prepare global config: "
                                                   (or (.getMessage e) (str e)))
                                              "ECA Global Config")))))})
          "editor/openServerLogs" (server-logs/open-server-logs! project)
          (logger/warn "Unkown webview message type:" type)))))
  nil)

(defmethod api/config-updated :default
  [{:keys [project]} params]
  (db/update-in project [:server-config] #(merge % params))
  (handle-config-changed project params))

(defmethod api/chat-content-received :default
  [{:keys [project]} params]
  (send-msg! project {:type "chat/contentReceived"
                      :data params}))

(defmethod api/chat-cleared :default
  [{:keys [project]} params]
  (send-msg! project {:type "chat/cleared"
                      :data params}))

(defmethod api/tool-server-updated  :default
  [{:keys [project]} params]
  (db/assoc-in project [:session :mcp-servers (:name params)] params)
  (send-msg! project {:type "tool/serversUpdated"
                      :data (vals (db/get-in project [:session :mcp-servers]))}))
