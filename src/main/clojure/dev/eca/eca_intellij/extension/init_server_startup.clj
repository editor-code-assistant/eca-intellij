(ns dev.eca.eca-intellij.extension.init-server-startup
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.extension.server-logs :as server-logs]
   [dev.eca.eca-intellij.server :as server])
  (:import
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.startup ProjectActivity]
   [kotlinx.coroutines CoroutineScope]))

(set! *warn-on-reflection* true)

(def-extension InitServerStartup []
  ProjectActivity
  (execute [_this ^Project project ^CoroutineScope _]
    (when-not (contains? #{:connected :connecting}
                         (server/status project))
      (server/start! project))
    (db/assoc-in project [:on-stderr-log-updated-fns :editor-log] (fn [] (server-logs/update-logs! project)))))
