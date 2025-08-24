(ns dev.eca.eca-intellij.webview
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.github.ericdallo.clj4intellij.app-manager :as app-manager]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.shared :as shared])
  (:import
   [com.intellij.openapi.editor.colors EditorColorsManager]
   [com.intellij.openapi.fileEditor FileEditorManager]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.util.io FileUtil]
   [com.intellij.openapi.vfs LocalFileSystem]
   [com.intellij.ui ColorUtil JBColor]
   [com.intellij.ui.jcef JBCefBrowser]
   [com.intellij.util.ui JBUI$CurrentTheme$ToolWindow]
   [org.cef.browser CefBrowser]))

(set! *warn-on-reflection* true)

(defn ^:private hex [jb-color]
  (str "#" (ColorUtil/toHex jb-color)))

(def ^:private theme-css-map
  {"editor-bg" (hex (.getDefaultBackground (.getGlobalScheme (EditorColorsManager/getInstance))))
   "editor-fg" (hex (JBColor/namedColor "Editor.foreground"))
   "panel-bg" (hex (JBColor/namedColor "Editor.background"))
   "panel-border" (hex (JBUI$CurrentTheme$ToolWindow/borderColor))
   "input-bg" (hex (JBColor/namedColor "OptionPane.background"))
   "input-fg" (hex (JBColor/namedColor "TextField.caretForeground"))
   "input-placeholder-fg" (hex (JBColor/namedColor "Editor.foreground"))
   "toolbar-hover-bg" (hex (JBColor/namedColor "ActionButton.hoverBorderColor"))

   "item-selectable-fg" (hex (JBColor/namedColor "Editor.foreground"))
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
   })

(defn theme-css ^String []
  (str ":root {\n"
       (reduce
        (fn [s [name value]]
          (str s (format "--intellij-%s: %s;\n" name value)))
        ""
        theme-css-map)
       "}"))

(defn ^:private send-msg! [^CefBrowser cef-browser msg]
  (.executeJavaScript cef-browser
                      (format "window.postMessage(%s, \"*\");"
                              (json/generate-string (shared/map->camel-cased-map msg)))
                      (.getURL cef-browser)
                      0))

(defn handle-server-status-changed [status ^Project project]
  (let [browser ^JBCefBrowser (db/get-in project [:webview-browser])
        cef-browser (.getCefBrowser browser)]
    (when (= :running status)
      (let [session (db/get-in project [:session])]
        (send-msg! cef-browser {:type "chat/setModels"
                                :data {:models (:models session)
                                       :selected-model (:chat-selected-model session)}})
        (send-msg! cef-browser {:type "chat/setBehaviors"
                                :data {:behaviors (:chat-behaviors session)
                                       :selected-behavior (:chat-selected-behavior session)}})
        (send-msg! cef-browser {:type "server/setWorkspaceFolders"
                                :data [{:name (.getName project)
                                        :uri (str (.toURI (io/file (.getBasePath project))))}]})
        (send-msg! cef-browser {:type "chat/setWelcomeMessage"
                                :data {:message (:welcome-message session)}})))
    (send-msg! cef-browser {:type "server/statusChanged"
                            :data (string/capitalize (name status))})))

(defn handle [msg project]
  (let [{:keys [type data]} (json/parse-string msg keyword)
        cef-browser (.getCefBrowser ^JBCefBrowser (db/get-in project [:webview-browser]))]
    (when-let [client (api/connected-client project)]
      (case type
        "webview/ready" (do
                          (handle-server-status-changed (db/get-in project [:status])
                                                        project)
                          ;; TODO send config/updated
                          )
        "chat/userPrompt" (let [result @(api/request! client [:chat/prompt {:chatId (data :chatId)
                                                                            :message (data :prompt)
                                                                            :model (db/get-in project [:session :chat-selected-model])
                                                                            :behavior (db/get-in project [:session :chat-selected-behavior])
                                                                            :requestId (str (data :requestId))
                                                                            :contexts (data :contexts)}])]
                            (send-msg! cef-browser
                                       {:type "chat/newChat"
                                        :data {:id (:chat-id result)}}))
        "chat/queryContext" (let [result @(api/request! client [:chat/queryContext data])]
                              (send-msg! cef-browser {:type "chat/queryContext"
                                                      :data result}))
        "chat/queryCommands" (let [result @(api/request! client [:chat/queryCommands data])]
                               (send-msg! cef-browser {:type "chat/queryCommands"
                                                       :data result}))
        "chat/toolCallApprove" (api/notify! client [:chat/toolCallApprove data])
        "chat/toolCallReject" (api/notify! client [:chat/toolCallReject data])
        "chat/promptStop" (api/notify! client [:chat/promptStop data])
        "chat/delete" @(api/request! client [:chat/delete data])
        "mcp/startServer" (api/notify! client [:mcp/startServer data])
        "mcp/stopServer" (api/notify! client [:mcp/stopServer data])
        "editor/openFile" (let [path (:path data)
                                sys-ind-path (FileUtil/toSystemIndependentName path)
                                vfile (.refreshAndFindFileByPath (LocalFileSystem/getInstance) sys-ind-path)]
                            (app-manager/invoke-later! {:invoke-fn
                                                        (fn []
                                                          (when vfile
                                                            (.openFile (FileEditorManager/getInstance project) vfile true)))}))
        (logger/warn "Unkown webview message type:" type))))
  nil)

(defmethod api/chat-content-received :default
  [{:keys [project]} params]
  (when-let [browser ^JBCefBrowser (db/get-in project [:webview-browser])]
    (let [cef-browser (.getCefBrowser browser)]
      (send-msg! cef-browser {:type "chat/contentReceived"
                              :data params}))))

(defmethod api/tool-server-updated  :default
  [{:keys [project]} params]
  (when-let [browser ^JBCefBrowser (db/get-in project [:webview-browser])]
    (let [cef-browser (.getCefBrowser browser)]
      (db/assoc-in project [:session :mcp-servers (:name params)] params)
      (send-msg! cef-browser {:type "tool/serversUpdated"
                              :data (vals (db/get-in project [:session :mcp-servers]))}))))
