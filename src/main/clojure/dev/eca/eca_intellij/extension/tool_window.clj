(ns dev.eca.eca-intellij.extension.tool-window
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [com.rpl.proxy-plus :refer [proxy+]]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.webview :as webview])
  (:import
   [com.intellij.openapi.actionSystem ActionManager]
   [com.intellij.openapi.project DumbAwareAction Project]
   [com.intellij.openapi.util Disposer]
   [com.intellij.openapi.wm ToolWindow ToolWindowAnchor ToolWindowFactory]
   [com.intellij.ui.content ContentFactory]
   [com.intellij.ui.jcef JBCefBrowser JBCefJSQuery]
   [dev.eca.eca_intellij Icons]
   [dev.eca.eca_intellij EcaSchemeHandlerFactory]
   [org.cef CefApp]
   [org.cef.callback CefCallback CefSchemeHandlerFactory]
   [org.cef.handler CefLoadHandlerAdapter CefResourceHandler]
   [org.cef.misc IntRef]
   [org.cef.network CefResponse]))

(set! *warn-on-reflection* true)

(defn ^:private read-response
  "Stream the response in chunks honoring bytes-to-read. Returns true while there is
   data remaining and false when fully sent. Uses a per-request offset atom."
  [^bytes data-out bytes-to-read ^IntRef bytes-read ^bytes bytes offset-atom]
  (let [len (alength bytes)
        offset ^long @offset-atom
        remaining (- len offset)]
    (if (pos? remaining)
      (let [to-copy (int (min bytes-to-read remaining))]
        (System/arraycopy bytes offset data-out 0 to-copy)
        (.set bytes-read to-copy)
        (swap! offset-atom + to-copy)
        ;; More data may remain; signal to call again if needed
        true)
      (do
        ;; No more data: signal EOF
        (.set bytes-read 0)
        false))))

(defn ^:private eca-theme-scheme-handler []
  (proxy+ [] CefSchemeHandlerFactory
    (create [_ _browser _frame _scheme _req]
      (let [offset* (atom 0)
            bytes* (atom nil)]
        (proxy+ [] CefResourceHandler
          (processRequest [_ _req ^CefCallback callback]
            (reset! offset* 0)
            (reset! bytes* (.getBytes (webview/theme-css) "UTF-8"))
            (.Continue callback)
            true)
          (getResponseHeaders [_ ^CefResponse resp ^IntRef length _redirect-url]
            (.setMimeType resp "text/css")
            (.setStatus resp 200)
            (.set length (alength ^bytes @bytes*)))
          (readResponse [_ data-out bytes-to-read bytes-read _callback]
            (read-response data-out bytes-to-read bytes-read @bytes* offset*))
          (cancel [_]))))))

(defn ^:private browser-javascript ^String [^JBCefJSQuery js-query]
  (format (str "window.postMessageToEditor = function(message) {"
               "  const msg = JSON.stringify(message);"
               "  %s"
               "}")
          (.inject js-query "msg")))

(defn ^:private create-webview ^JBCefBrowser [^Project project]
  (let [browser (-> (JBCefBrowser/createBuilder)
                    (.setOffScreenRendering true) ;; TODO move to config
                    (.build))
        js-query (JBCefJSQuery/create browser)
        cef-browser (.getCefBrowser browser)]
    (db/assoc-in project [:webview-browser] browser)
    (Disposer/register project browser)
    (.addHandler js-query (fn [msg] (webview/handle msg project)))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "http" "eca" (EcaSchemeHandlerFactory.))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "http" "eca-theme" (eca-theme-scheme-handler))
    (.addLoadHandler
     (.getJBCefClient browser)
     (proxy+ [] CefLoadHandlerAdapter
       (onLoadingStateChange [_ _ loading? _ _]
         (when-not loading?
           (let [javascript (browser-javascript js-query)]
             (.executeJavaScript cef-browser javascript (.getURL cef-browser) 0)
             (webview/handle-server-status-changed (db/get-in project [:status]) project)
             (db/assoc-in project [:on-status-changed-fns :webview] (fn [project status]
                                                                      (webview/handle-server-status-changed status project)))))))
     (.getCefBrowser browser))
    browser))

(defn ^:private reload-webview []
  (proxy+
   ["Reload ECA webview" "Reload ECA webview" Icons/ECA]
   DumbAwareAction
    (actionPerformed [_ _event]
      (let [browser ^JBCefBrowser (db/get-in (first (db/all-projects)) [:webview-browser])]
        (.loadURL browser "http://eca/not-found.html")
        (.loadURL browser "http://localhost:5173/intellij_index.html")))))

(defn ^:private open-devtools []
  (proxy+
   ["Open ECA webview devtools" "Open ECA webview devtools" Icons/ECA]
   DumbAwareAction
    (actionPerformed [_ _event]
      (let [browser ^JBCefBrowser (db/get-in (first (db/all-projects)) [:webview-browser])]
        (.openDevtools browser)))))

(defn ^:private create-tool-window-content
  [^Project project ^ToolWindow tool-window]
  (System/setProperty "ide.browser.jcef.jsQueryPoolSize" "200")
  (System/setProperty "ide.browser.jcef.contextMenu.devTools.enabled" "true")
  (let [browser (create-webview project)
        url (if (config/dev?)
              "http://localhost:5173/intellij_index.html"
              "http://eca/index.html")
        content (.createContent (ContentFactory/getInstance) (.getComponent browser) nil false)
        actions (->> [(.getAction (ActionManager/getInstance) "MaximizeToolWindow")
                      (when (config/dev?) (reload-webview))
                      (when (config/dev?) (open-devtools))]

                     (remove nil?))]
    (.addContent (.getContentManager tool-window) content)
    (.setTitleActions tool-window actions)
    (.loadURL browser url)))

(def-extension EcaToolWindowFactory []
  ToolWindowFactory
  (createToolWindowContent [_this project tool-window]
    (create-tool-window-content project tool-window))
  (shouldBeAvailable [_this _project] true)

  (init [_ ^ToolWindow _tool-window])

  (isApplicableAsync
    ([_ _] true)
    ([_ _ _] true))
  (isApplicable [_ _project] true)
  (isDoNotActivateOnStart [_] false)

  (manager [_ _ _])
  (manage [_ _ _ _])

  (getIcon [_] Icons/ECA)

  (getAnchor [_] ToolWindowAnchor/RIGHT))

(comment
  (db/get-in (first (db/all-projects)) [:webview-browser]))
