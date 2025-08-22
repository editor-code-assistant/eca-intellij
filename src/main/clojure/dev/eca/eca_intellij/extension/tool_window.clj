(ns dev.eca.eca-intellij.extension.tool-window
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db])
  (:import
   [com.intellij.openapi.actionSystem ActionManager]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.util Disposer]
   [com.intellij.openapi.wm ToolWindow ToolWindowAnchor ToolWindowFactory]
   [com.intellij.ui.content ContentFactory]
   [com.intellij.ui.jcef JBCefBrowser JBCefJSQuery]
   [dev.eca.eca_intellij Icons]
   [dev.eca.eca_intellij EcaSchemeHandlerFactory]
   [org.cef CefApp]))

(set! *warn-on-reflection* true)

(defn ^:private create-webview ^JBCefBrowser [^Project project]
  (let [browser (-> (JBCefBrowser/createBuilder)
                    (.setOffScreenRendering true) ;; TODO move to config
                    (.build))
        webview (JBCefJSQuery/create browser)]
    (db/assoc-in project [:webview-browser] browser)
    (Disposer/register project browser)
    (.addHandler webview (fn [msg]
                           (logger/info "----->" msg)))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance)
     "http"
     "eca"
     (EcaSchemeHandlerFactory.))
    browser))

(defn ^:private create-tool-window-content
  [^Project project ^ToolWindow tool-window]
  (System/setProperty "ide.browser.jcef.jsQueryPoolSize" "200")
  (System/setProperty "ide.browser.jcef.contextMenu.devTools.enabled" "true")
  (let [browser (create-webview project)
        url (if (config/dev?)
              "http://localhost:5173/intellij_index.html"
              "http://eca/index.html")
        content (.createContent (ContentFactory/getInstance) (.getComponent browser) nil false)
        actions (->> [(.getAction (ActionManager/getInstance) "MaximizeToolWindow")]
                     (remove nil?))]
    (.addContent (.getContentManager tool-window) content)
    (.setTitleActions tool-window actions)
    (logger/info "creating tool window")
    (.loadURL browser url)))

(def-extension EcaToolWindowFactory []
  ToolWindowFactory
  (createToolWindowContent [_this project tool-window]
    (create-tool-window-content project tool-window))
  (shouldBeAvailable [_this _project] true)
  (isApplicable [_this _project] true)

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

(comment
  (.loadURL (db/get-in (first (db/all-projects)) [:webview-browser]) "http://eca/index.html")
  (.loadURL (db/get-in (first (db/all-projects)) [:webview-browser]) "http://localhost:5173")
  ;; open devtools
  (.openDevtools (db/get-in (first (db/all-projects)) [:webview-browser])))
