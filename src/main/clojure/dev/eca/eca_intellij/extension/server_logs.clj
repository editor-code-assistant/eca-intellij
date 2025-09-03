(ns dev.eca.eca-intellij.extension.server-logs
  (:require
   [com.github.ericdallo.clj4intellij.app-manager :as app-manager]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.shared :as shared])
  (:import
   [com.intellij.openapi.fileEditor FileDocumentManager FileEditorManager]
   [com.intellij.openapi.fileTypes PlainTextFileType]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.vfs VirtualFile]
   [com.intellij.testFramework LightVirtualFile]))

(set! *warn-on-reflection* true)

(defn open-server-logs! [^Project project]
  (let [log-content (db/get-in project [:server-stderr-string])
        virtual-file (LightVirtualFile. "eca-stderr.txt" log-content)
        file-editor-manager (FileEditorManager/getInstance project)]
    (app-manager/invoke-later!
     {:invoke-fn (fn []
                   (.setFileType virtual-file PlainTextFileType/INSTANCE)
                   (.openFile file-editor-manager virtual-file true))})))

(defn update-logs-in-editor! [^Project project]
  (let [log-content ^String (db/get-in project [:server-stderr-string])
        file-editor-manager (FileEditorManager/getInstance project)
        open-files (.getOpenFiles file-editor-manager)]
    (doseq [^VirtualFile vf open-files]
      (when (= "eca-stderr.txt" (.getName vf))
        (when-let [document  (.getDocument (FileDocumentManager/getInstance) vf)]
          (app-manager/invoke-later! {:invoke-fn
                                      (fn []
                                        (app-manager/write-action!
                                         {:run-fn (fn []
                                                    (.setText document log-content))}))}))))))

(def update-logs! (shared/throttle update-logs-in-editor! 1000))
