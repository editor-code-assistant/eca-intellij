(ns dev.eca.eca-intellij.api-client-test
  "Tests for the api.clj Client record driven through in-memory channels,
   so the real receive pipeline (pipeline-blocking, parallelism 1) is
   exercised without a server process."
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.editor-nav :as editor-nav]
   [dev.eca.eca-intellij.test-fixtures :as fixt]
   [dev.eca.eca-intellij.webview :as webview]))

(defn ^:private in-memory-client
  "A started Client: put server messages on :from-server (already
   kebab-cased, as io-chan would deliver them) and read what the client
   writes to the server from :to-server."
  [project]
  (let [from-server (async/chan 16)
        to-server (async/chan 16)
        client (api/map->Client {:client-id 1
                                 :input-ch from-server
                                 :output-ch to-server
                                 :join (promise)
                                 :sent-requests (atom {})
                                 :request-id (atom 0)
                                 :trace-level nil})]
    (api/start-client! client {:project project})
    {:client client :from-server from-server :to-server to-server}))

(defn ^:private take-to-server!
  "Next message the client wrote, or :timeout."
  [to-server]
  (first (async/alts!! [to-server (async/timeout 2000)])))

(defn ^:private await-webview-msg [bridge type]
  (loop [i 0]
    (when (and (< i 200)
               (empty? (fixt/webview-of-type bridge type)))
      (Thread/sleep 10)
      (recur (inc i))))
  (fixt/last-to-webview-of-type bridge type))

(deftest pending-ask-question-does-not-block-later-server-messages
  (testing "Regression seen on 0.31.2 (2624d73): receive-request answered
            server requests synchronously inside the pipeline, so while
            our chat/askQuestion handler waited for the user (up to 5
            min) no other server message was processed. With /login the
            server sends chat/askQuestion before the chat/prompt
            response, so that response, awaited by the webview handler,
            never arrived and the tool window froze until the ask timed
            out. Responses and notifications must keep flowing while a
            question is pending, and the answer must still complete the
            parked request."
    (fixt/with-test-project [project]
      ;; with-stub-bridge stubs api/request!; this test needs the real one
      ;; so the request goes through the in-memory client.
      (let [request! api/request!]
        (fixt/with-stub-bridge bridge
          (let [{:keys [client from-server to-server]} (in-memory-client project)]
            (try
              (async/>!! from-server {:jsonrpc "2.0"
                                      :id 7
                                      :method "chat/askQuestion"
                                      :params {:chat-id "c1"
                                               :question "Select the Anthropic login method:"
                                               :options [{:label "max"}]}})
              (let [asked (await-webview-msg bridge "chat/askQuestion")]
                (is (some? asked) "the question reaches the webview")

                (let [prompt-reply (request! client [:chat/prompt {:chatId "c1"}])
                      sent (take-to-server! to-server)]
                  (is (= "chat/prompt" (:method sent)))
                  (async/>!! from-server {:jsonrpc "2.0"
                                          :id (:id sent)
                                          :result {:chat-id "c1"}})
                  (is (= {:chat-id "c1"} (deref prompt-reply 2000 :timeout))
                      "a response arriving while the question is pending is delivered"))

                (async/>!! from-server {:jsonrpc "2.0"
                                        :method "chat/contentReceived"
                                        :params {:chat-id "c1" :role "system" :content {:type "text" :text "hi"}}})
                (is (some? (await-webview-msg bridge "chat/contentReceived"))
                    "notifications keep flowing while the question is pending")

                (webview/handle
                 (fixt/to-json-payload {:type "chat/answerQuestion"
                                        :data {:requestId (get-in asked [:data :requestId])
                                               :answer "max"}})
                 project)
                (let [resp (take-to-server! to-server)]
                  (is (= 7 (:id resp)))
                  (is (= {:answer "max"} (:result resp)))))
              (finally
                (async/close! from-server)))))))))

(deftest unknown-server-request-is-answered-with-method-not-found
  (testing "An unknown request used to be logged and never answered,
            leaving the server waiting forever. JSON-RPC requires an
            error response."
    (fixt/with-test-project [project]
      (let [{:keys [from-server to-server]} (in-memory-client project)]
        (try
          (async/>!! from-server {:jsonrpc "2.0"
                                  :id 9
                                  :method "editor/doesNotExist"
                                  :params {}})
          (let [resp (take-to-server! to-server)]
            (is (= 9 (:id resp)))
            (is (= -32601 (get-in resp [:error :code])))
            (is (nil? (:result resp))))
          (finally
            (async/close! from-server)))))))

(deftest throwing-request-handler-is-answered-with-internal-error
  (fixt/with-test-project [project]
    (let [{:keys [from-server to-server]} (in-memory-client project)]
      (try
        (with-redefs [editor-nav/get-definition (fn [& _] (throw (ex-info "boom" {})))
                      logger/error (fn [& _] nil)]
          (async/>!! from-server {:jsonrpc "2.0"
                                  :id 11
                                  :method "editor/getDefinition"
                                  :params {:uri "file:///a.clj" :position {:line 1 :character 1}}})
          (let [resp (take-to-server! to-server)]
            (is (= 11 (:id resp)))
            (is (= -32603 (get-in resp [:error :code])))
            (is (= "boom" (get-in resp [:error :message])))))
        (finally
          (async/close! from-server))))))
