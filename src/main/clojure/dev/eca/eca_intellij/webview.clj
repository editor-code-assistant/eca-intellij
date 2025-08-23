(ns dev.eca.eca-intellij.webview
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [dev.eca.eca-intellij.db :as db])
  (:import
   [com.intellij.openapi.project Project]
   [com.intellij.ui.jcef JBCefBrowser]
   [org.cef.browser CefBrowser]))

(set! *warn-on-reflection* true)

(defn ^:private send-msg! [^CefBrowser cef-browser msg]
  (logger/info "Sending webview msg: " msg)
  (.executeJavaScript cef-browser
                      (format "window.postMessage(%s, \"*\");"
                              (json/generate-string msg))
                      (.getURL cef-browser)
                      0))

(defn handle-server-status-changed [status ^Project project]
  (let [browser ^JBCefBrowser (db/get-in project [:webview-browser])
        cef-browser (.getCefBrowser browser)]
    (when (= :running status)
      (let [session (db/get-in project [:session])]
        (send-msg! cef-browser {:type "chat/setModels"
                                :data {:models (:models session)
                                       :selectedModel (:chat-selected-model session)}})
        (send-msg! cef-browser {:type "chat/setBehaviors"
                                :data {:behaviors (:chat-behaviors session)
                                       :selectedBehavior (:chat-selected-behavior session)}})
        (send-msg! cef-browser {:type "server/setWorkspaceFolders"
                                :data [{:name (.getName project)
                                        :uri (str (.toURI (io/file (.getBasePath project))))}]})
        (send-msg! cef-browser {:type "chat/setWelcomeMessage"
                                :data {:message (:welcome-message session)}})))
    (send-msg! cef-browser {:type "server/statusChanged"
                            :data (string/capitalize (name status))})))

(defn handle [msg project]
  (let [msg (json/parse-string msg keyword)]
    (case (:type msg)
      "webview/ready" (do
                        (handle-server-status-changed (db/get-in project [:status])
                                                      project)
                        ;; send config/updated
                        )
      "chat/queryContext" 2)))

(defn send! [msg]
  (when-let [browser ^JBCefBrowser (:browser @db/db*)]
    (send-msg! (.getCefBrowser browser)
               msg)))
