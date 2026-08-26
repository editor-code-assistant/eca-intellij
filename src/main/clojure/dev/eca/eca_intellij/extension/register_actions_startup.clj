(ns dev.eca.eca-intellij.extension.register-actions-startup
  (:require
   [com.github.ericdallo.clj4intellij.action :as action]
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [dev.eca.eca-intellij.extension.server-logs :as server-logs]
   [dev.eca.eca-intellij.inline-chat :as inline-chat]
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

(defn ^:private inline-prompt-action [^AnActionEvent event]
  (when-let [editor (.getData event CommonDataKeys/EDITOR_EVEN_IF_INACTIVE)]
    (inline-chat/inline-prompt! (action-event->project event) editor {})))

(defn ^:private inline-prompt-selecting-action [^AnActionEvent event]
  (when-let [editor (.getData event CommonDataKeys/EDITOR_EVEN_IF_INACTIVE)]
    (inline-chat/inline-prompt! (action-event->project event) editor {:force-select? true})))

(defn ^:private inline-chat-actions-action [^AnActionEvent event]
  (when-let [editor (.getData event CommonDataKeys/EDITOR_EVEN_IF_INACTIVE)]
    (inline-chat/show-actions! (action-event->project event) editor)))

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
    (action/register-action! :id "Eca.InlinePrompt"
                             :title "Inline prompt"
                             :description "Ask ECA, streaming the answer inline below the cursor"
                             :on-performed #'inline-prompt-action)
    (action/register-action! :id "Eca.InlinePromptSelecting"
                             :title "Inline prompt (pick chat)"
                             :description "Ask ECA inline, re-picking which chat to use"
                             :on-performed #'inline-prompt-selecting-action)
    (action/register-action! :id "Eca.InlineChatActions"
                             :title "Inline chat actions"
                             :description "Actions for the inline chat of this file (follow-up, stop, approvals...)"
                             :on-performed #'inline-chat-actions-action)
    (action/register-group! :id "Eca.Actions"
                            :popup true
                            :text "ECA"
                            :icon (shared/logo-icon)
                            :children [{:type :add-to-group :group-id "ToolsMenu" :anchor :first}
                                       {:type :add-to-group :group-id "EditorPopupMenu" :anchor :before :relative-to "RefactoringMenu"}
                                       {:type :reference :ref "Eca.InlinePrompt"}
                                       {:type :reference :ref "Eca.InlineChatActions"}
                                       {:type :separator}
                                       {:type :reference :ref "Eca.AddContextToSystemPrompt"}
                                       {:type :separator}
                                       {:type :reference :ref "Eca.ShowServerLogs"}
                                       {:type :separator}])))
