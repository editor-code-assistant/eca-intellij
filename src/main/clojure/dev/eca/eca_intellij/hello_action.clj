(ns dev.eca.eca-intellij.hello-action
  (:require
   [com.github.ericdallo.clj4intellij.action :as action])
  (:import
   [com.intellij.openapi.actionSystem AnActionEvent]))

(defn on-performed [^AnActionEvent _event]
  )

(defn hello-action
  []
  (action/register-action!
    :on-performed on-performed))
