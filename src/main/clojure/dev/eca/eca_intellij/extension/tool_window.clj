(ns dev.eca.eca-intellij.extension.tool-window
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]])
  (:import
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.wm ToolWindow ToolWindowAnchor ToolWindowFactory]
   [com.intellij.ui.content ContentFactory]
   [dev.eca.eca_intellij Icons]
   [java.awt BorderLayout]
   [javax.swing JLabel JPanel]))

(set! *warn-on-reflection* true)

(defn ^:private create-content-panel [^Project _project]
  (doto (JPanel. (BorderLayout.))
    (.add (JLabel. "ECA chat - coming soon") BorderLayout/CENTER)))

(def-extension EcaToolWindowFactory []
  ToolWindowFactory
  (createToolWindowContent [_this ^Project project ^ToolWindow tool-window]
    (let [panel (create-content-panel project)
          content (.createContent (ContentFactory/getInstance) panel "" false)]
      (.addContent (.getContentManager tool-window) content)))
  (shouldBeAvailable [_this ^Project _project] true)
  (isApplicable [_this ^Project _project] true)

  (createToolWindowContent [_this ^Project project ^ToolWindow tool-window]
    (let [panel (create-content-panel project)
          content (.createContent (ContentFactory/getInstance) panel "" false)]
      (.addContent (.getContentManager tool-window) content)))

  (init [_ ^ToolWindow _tool-window])

  (isApplicableAsync
    ([_ ^Project _project] true)
    ([_ ^Project _project __] true))

  (isApplicable [_ _project] true)

  (shouldBeAvailable [_this ^Project _project] true)

  (manager [_ _ _])
  (manage [_ _ _ _])

  (getIcon [_] Icons/ECA)

  (getAnchor [_] ToolWindowAnchor/RIGHT))
