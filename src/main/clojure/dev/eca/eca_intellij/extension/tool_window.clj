(ns dev.eca.eca-intellij.extension.tool-window
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [com.rpl.proxy-plus :refer [proxy+]]
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
   [java.io IOException InputStream]
   [java.net URLConnection]
   [org.cef CefApp]
   [org.cef.callback CefCallback CefSchemeHandlerFactory]
   [org.cef.handler CefLoadHandler$ErrorCode CefResourceHandler]
   [org.cef.misc IntRef]
   [org.cef.network CefRequest CefResponse]))

(set! *warn-on-reflection* true)

(defn ^:private read-response [data-out bytes-to-read ^IntRef bytes-read state*]
  (boolean
   (when-let [^InputStream input-stream (:input-stream @state*)]
     (let [available (.available input-stream)
           max-bytes (min available bytes-to-read)]
       (if (> available 0)
         (let [byte (.read input-stream data-out 0 max-bytes)]
           (.set bytes-read byte)
           true)
         (do
           (.close input-stream)
           false))))))

(defn ^:private response-headers [^CefResponse resp ^IntRef resp-length current-url* state*]
  (try
    (when-let [conn ^URLConnection (:conn @state*)]
      (let [url (str (.getURL conn))]
        (cond
          (string/includes? url "css")
          (.setMimeType resp "text/css")

          (string/includes? url "js")
          (.setMimeType resp "text/javascript")

          (string/includes? url "html")
          (.setMimeType resp "text/html")

          :else
          (.setMimeType resp (.getContentType conn))))

      (.set resp-length (.available ^InputStream (:input-stream @state*)))
      (.setStatus resp 200))
    (catch IOException e
      (.setStatus resp 404)
      (.setError resp CefLoadHandler$ErrorCode/ERR_FILE_NOT_FOUND)
      (.setStatusText resp (.getLocalizedMessage e)))))

(defn ^:private process-request [^CefRequest req ^CefCallback callback state* current-url*]
  (boolean
   (when-let [url (.getURL req)]
     (let [resource-path (string/replace url "http://eca" "webview")
           new-url (io/resource resource-path (.getClassLoader clojure.lang.Symbol))
           conn (when new-url (.openConnection new-url))
           is (when conn (.getInputStream conn))]
       (swap! state* assoc
              :input-stream is
              :conn conn)
       (reset! current-url* new-url)
       (.Continue callback)
       true))))

(defn ^:private create-webview ^JBCefBrowser [^Project project]
  (let [browser (-> (JBCefBrowser/createBuilder)
                    (.setOffScreenRendering true) ;; TODO move to config
                    (.build))
        webview (JBCefJSQuery/create browser)
        state* (atom {})
        current-url* (atom nil)]
    (db/assoc-in project [:webview-browser] browser)
    (Disposer/register project browser)
    (.addHandler webview (fn [msg]
                           (logger/info "----->" msg)))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance)
     "http"
     "eca"
     (proxy+
      []
      CefSchemeHandlerFactory
       (create [_ _browser _frame _scheme _req]
         (proxy+ [] CefResourceHandler
           (processRequest [_ req callback]
             (process-request req callback state* current-url*))
           (getResponseHeaders [_ resp length _redirect-url]
             (response-headers resp length current-url* state*))
           (readResponse [_ data-out bytes-to-read bytes-read _callback]
             (read-response data-out bytes-to-read bytes-read state*))
           (cancel [_]
             (when-let [^InputStream is (:input-stream @state*)]
               (.close is))
             (swap! state* dissoc :input-stream))))))
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
  (.loadURL (db/get-in (first (db/all-projects)) [:webview-browser]) "http://eca/foo")
  (.loadURL (db/get-in (first (db/all-projects)) [:webview-browser]) "http://eca/index.html")
  (.loadURL (db/get-in (first (db/all-projects)) [:webview-browser]) "http://localhost:5173")
  ;; open devtools
  (.openDevtools (db/get-in (first (db/all-projects)) [:webview-browser])))
