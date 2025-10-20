(ns dev.eca.eca-intellij.extension.cursor-editor-listener
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [com.rpl.proxy-plus :refer [proxy+]]
   [dev.eca.eca-intellij.db :as db])
  (:import
   [com.intellij.openapi.editor Editor]
   [com.intellij.openapi.editor.event
    CaretEvent
    CaretListener
    EditorFactoryEvent
    EditorFactoryListener]
   [com.intellij.openapi.project Project]))

(set! *warn-on-reflection* true)

(defn ^:private on-caret-changed [^CaretEvent e ^Editor editor ^Project project]
  (doseq [on-focus-changed (vals (db/get-in project [:on-focus-changed-fns]))]
    (on-focus-changed editor e)))

(defn ^:private handle-new-editor-created [^Editor editor]
  (let [project (.getProject editor)]
    (.addCaretListener (.getCaretModel editor)
                       (proxy+ [] CaretListener
                         (caretPositionChanged [_this e]
                           (on-caret-changed e editor project))))))

(def-extension CursorEditorFactoryListener []
  EditorFactoryListener
  (editorCreated [_this ^EditorFactoryEvent event]
    (handle-new-editor-created (.getEditor event)))
  (editorReleased [_this _]))
