(ns dev.eca.ecaintellij.hello-action
  (:require [com.github.ericdallo.clj4intellij.action :as action])
  (:import
     [com.intellij.openapi.actionSystem CommonDataKeys]
     [com.intellij.openapi.actionSystem AnActionEvent]
     [com.intellij.openapi.ui Messages]
     [com.intellij.openapi.editor Editor]))

(defn on-performed [^AnActionEvent event]
  (.showInfoMessage Messages "Hello from ECA with clojure!" "ECA Plugin"))

(defn hello-action
  []
  (action/register-action!
    :on-performed on-performed))