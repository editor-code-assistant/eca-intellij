(ns dev.eca.eca-intellij.extension.init-server-startup
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [dev.eca.eca-intellij.listener.file :as listener.file]
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
    (listener.file/track-files! project)))
