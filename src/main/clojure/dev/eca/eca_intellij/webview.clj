(ns dev.eca.eca-intellij.webview
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.shared :as shared])
  (:import
   [com.intellij.openapi.project Project]
   [com.intellij.ui.jcef JBCefBrowser]
   [org.cef.browser CefBrowser]))

(set! *warn-on-reflection* true)

(defn ^:private send-msg! [^CefBrowser cef-browser msg]
  (.executeJavaScript cef-browser
                      (format "window.postMessage(%s, \"*\");"
                              (json/generate-string (shared/map->camel-cased-map msg)))
                      (.getURL cef-browser)
                      0))

(defn handle-server-status-changed [status ^Project project]
  (let [browser ^JBCefBrowser (db/get-in project [:webview-browser])
        cef-browser (.getCefBrowser browser)]
    (when (= :running status)
      (let [session (db/get-in project [:session])]
        (send-msg! cef-browser {:type "chat/setModels"
                                :data {:models (:models session)
                                       :selected-model (:chat-selected-model session)}})
        (send-msg! cef-browser {:type "chat/setBehaviors"
                                :data {:behaviors (:chat-behaviors session)
                                       :selected-behavior (:chat-selected-behavior session)}})
        (send-msg! cef-browser {:type "server/setWorkspaceFolders"
                                :data [{:name (.getName project)
                                        :uri (str (.toURI (io/file (.getBasePath project))))}]})
        (send-msg! cef-browser {:type "chat/setWelcomeMessage"
                                :data {:message (:welcome-message session)}})))
    (send-msg! cef-browser {:type "server/statusChanged"
                            :data (string/capitalize (name status))})))

(defn handle [msg project]
  (let [{:keys [type data]} (json/parse-string msg keyword)
        cef-browser (.getCefBrowser ^JBCefBrowser (db/get-in project [:webview-browser]))]
    (when-let [client (api/connected-client project)]
      (case type
        "webview/ready" (do
                          (handle-server-status-changed (db/get-in project [:status])
                                                        project)
                          ;; TODO send config/updated
                          )
        "chat/userPrompt" (let [result @(api/request! client [:chat/prompt {:chatId (data :chatId)
                                                                            :message (data :prompt)
                                                                            :model (db/get-in project [:session :chat-selected-model])
                                                                            :behavior (db/get-in project [:session :chat-selected-behavior])
                                                                            :requestId (str (data :requestId))
                                                                            :contexts (data :contexts)}])]
                            (send-msg! cef-browser
                                       {:type "chat/newChat"
                                        :data {:id (:chat-id result)}}))
        "chat/queryContext" (let [result @(api/request! client [:chat/queryContext data])]
                              (send-msg! cef-browser {:type "chat/queryContext"
                                                      :data result}))
        "chat/queryCommands" (let [result @(api/request! client [:chat/queryCommands data])]
                               (send-msg! cef-browser {:type "chat/queryCommands"
                                                       :data result}))))))

(defmethod api/chat-content-received :default
  [{:keys [project]} params]
  (when-let [browser ^JBCefBrowser (db/get-in project [:webview-browser])]
    (let [cef-browser (.getCefBrowser browser)]
      (send-msg! cef-browser {:type "chat/contentReceived"
                              :data params}))))

(defmethod api/tool-server-updated  :default
  [{:keys [project]} params]
  (when-let [browser ^JBCefBrowser (db/get-in project [:webview-browser])]
    (let [cef-browser (.getCefBrowser browser)]
      (db/assoc-in project [:session :mcp-servers (:name params)] params)
      (send-msg! cef-browser {:type "tool/serversUpdated"
                              :data (vals (db/get-in project [:session :mcp-servers]))}))))
