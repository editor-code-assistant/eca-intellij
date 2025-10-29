(ns dev.eca.eca-intellij.extension.register-actions-startup
  (:require
   [com.github.ericdallo.clj4intellij.action :as action]
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [dev.eca.eca-intellij.extension.server-logs :as server-logs]
   [dev.eca.eca-intellij.shared :as shared])
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

(def-extension RegisterActionsStartup []
  ProjectActivity
  (execute [_this ^Project _project ^CoroutineScope _]
    (action/register-action! :id "Eca.ShowServerLogs"
                             :title "ECA: show server Logs"
                             :description "Show ECA Server Logs"
                             :icon (shared/logo-icon)
                             :on-performed #'open-server-logs-action)
    (action/register-group! :id "Eca.Actions"
                            :popup true
                            :text "ECA Actions"
                            :icon (shared/logo-icon)
                            :children [{:type :add-to-group :group-id "ToolsMenu" :anchor :first}
                                       {:type :reference :ref "Eca.ShowServerLogs"}
                                       {:type :separator}])))
