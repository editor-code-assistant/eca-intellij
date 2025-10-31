(ns dev.eca.eca-intellij.extension.register-actions-startup
  (:require
   [com.github.ericdallo.clj4intellij.action :as action]
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [dev.eca.eca-intellij.extension.server-logs :as server-logs]
   [dev.eca.eca-intellij.shared :as shared]
   [dev.eca.eca-intellij.webview :as webview])
  (:import
   [com.intellij.openapi.actionSystem AnActionEvent CommonDataKeys]
   [com.intellij.openapi.editor Editor]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.startup ProjectActivity]
   [kotlinx.coroutines CoroutineScope]))

(defn ^:private action-event->project ^Project [^AnActionEvent event]
  (let [editor ^Editor (.getData event CommonDataKeys/EDITOR_EVEN_IF_INACTIVE)
        project ^Project (or (.getData event CommonDataKeys/PROJECT)
                             (.getProject editor))]
    project))

(defn ^:private open-server-logs-action [^AnActionEvent event]
  (server-logs/open-server-logs! (action-event->project event)))

(defn ^:private get-context-at-cursor [^Editor editor]
  (let [doc (.getDocument editor)
        vfile (some-> (com.intellij.openapi.fileEditor.FileDocumentManager/getInstance)
                      (.getFile doc))
        path (or (some-> vfile .getPath) "")
        sel (.getSelectionModel editor)]
    (cond-> {:type "file"
             :path path}
      (.hasSelection sel)
      (assoc :linesRange {:start (inc (.getLineNumber doc (.getSelectionStart sel)))
                          :end (inc (.getLineNumber doc (.getSelectionEnd sel)))}))))

(defn ^:private add-context-to-system-prompt-action [^AnActionEvent event]
  (when-let [editor (.getData event CommonDataKeys/EDITOR_EVEN_IF_INACTIVE)]
    (let [project (action-event->project event)
          context (get-context-at-cursor editor)]
      (webview/add-context-to-system-prompt context project))))

(def-extension RegisterActionsStartup []
  ProjectActivity
  (execute [_this ^Project _project ^CoroutineScope _]
    (action/register-action! :id "Eca.ShowServerLogs"
                             :title "Show ECA server Logs"
                             :description "Show ECA Server Logs"
                             :icon (shared/logo-icon)
                             :on-performed #'open-server-logs-action)
    (action/register-action! :id "Eca.AddContextToSystemPrompt"
                             :title "Add context to system prompt"
                             :description "Add context at cursor to system prompt in chat"
                             :on-performed #'add-context-to-system-prompt-action)
    (action/register-group! :id "Eca.Actions"
                            :popup true
                            :text "ECA"
                            :icon (shared/logo-icon)
                            :children [{:type :add-to-group :group-id "ToolsMenu" :anchor :first}
                                       {:type :add-to-group :group-id "EditorPopupMenu" :anchor :before :relative-to "RefactoringMenu"}
                                       {:type :reference :ref "Eca.AddContextToSystemPrompt"}
                                       {:type :separator}
                                       {:type :reference :ref "Eca.ShowServerLogs"}
                                       {:type :separator}])))
