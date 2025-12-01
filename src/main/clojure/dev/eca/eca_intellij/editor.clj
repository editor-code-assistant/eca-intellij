(ns dev.eca.eca-intellij.editor
  (:require
   [com.github.ericdallo.clj4intellij.app-manager :as app-manager]
   [com.github.ericdallo.clj4intellij.logger :as logger])
  (:import
   [com.intellij.openapi.project ProjectManager]
   [com.intellij.openapi.ui.popup JBPopupFactory]
   [com.intellij.openapi.util Computable]
   [com.intellij.ui.components JBList]
   [javax.swing DefaultListModel]))

(set! *warn-on-reflection* true)

(defn quick-pick
  "Show the list of options labels to user and require to select one.
   Returns a promise with the chosen option."
  [options & {:keys [title]
              :or {title "Select an option"}}]
  (let [p (promise)]
    (app-manager/invoke-later!
     {:invoke-fn
      (fn []
        (try
          (let [project (first (.getOpenProjects (ProjectManager/getInstance)))
                list-model (DefaultListModel.)
                ;; Track whether we've already delivered a value
                delivered? (atom false)
                ;; Build a map from label to full option for lookup
                label->option (into {} (map (fn [opt] [(:label opt) opt]) options))
                _ (doseq [opt options]
                    (.addElement list-model (:label opt)))
                jb-list (JBList. list-model)
                popup (-> (.createListPopupBuilder (JBPopupFactory/getInstance) jb-list)
                          (.setTitle ^String (str "ECA: " title))
                          (.setItemChoosenCallback
                           (reify Runnable
                             (run [_]
                               (when-let [selected-label (.getSelectedValue jb-list)]
                                 ;; Look up the full option by label
                                 (deliver p (get label->option selected-label))))))
                          (.setCancelCallback
                           (reify Computable
                             (compute [_]
                               ;; Delay to allow item callback to execute first
                               (future
                                 (Thread/sleep 100)
                                 (when-not @delivered?
                                   (deliver p nil)))
                               true)))
                          (.createPopup))]
            (.showCenteredInCurrentWindow popup project))
          (catch Exception e
            (deliver p nil)
            (logger/error e))))})
    p))
