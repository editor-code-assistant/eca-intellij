(ns dev.eca.eca-intellij.listener.file
  (:require
   [com.rpl.proxy-plus :refer [proxy+]]
   [dev.eca.eca-intellij.db :as db])
  (:import
   [com.intellij.openapi.editor EditorFactory]
   [com.intellij.openapi.editor.ex EditorEventMulticasterEx FocusChangeListener]
   [com.intellij.openapi.project Project]))

(set! *warn-on-reflection* true)

(defn ^:private on-focus-gained [project editor event]
  (doseq [on-focus-changed (vals (db/get-in project [:on-focus-changed-fns]))]
    (on-focus-changed editor event)))

(defn track-files! [^Project project]
  (let [^EditorEventMulticasterEx multi-caster (.getEventMulticaster (EditorFactory/getInstance))]
    (.addFocusChangeListener
     multi-caster
     (proxy+ [] FocusChangeListener
       (focusGained [_ editor] (on-focus-gained project editor nil))
       (focusLost [_ _])

       (focusGained [_ editor event] (on-focus-gained project editor event))
       (focusLost [_ _ _]))
     project)))
