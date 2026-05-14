(ns dev.eca.eca-intellij.webview-chat-test
  "Tests for the chat/* branches of webview/handle and the server-side
   multimethods that forward chat notifications back to the webview.
   Recent regressions have a named test pinned to their commit hash."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.test-fixtures :as fixt]
   [dev.eca.eca-intellij.webview :as webview]))

(deftest user-prompt-forwards-to-server-with-full-body
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :chat/prompt {:chat-id "new-chat-1"})
      (webview/handle
        (fixt/to-json-payload
          {:type "chat/userPrompt"
           :data {:chatId "old-chat"
                  :prompt "what is 2+2"
                  :model "claude"
                  :variant "sonnet"
                  :trust "ask"
                  :agent "researcher"
                  :requestId "req-1"
                  :contexts [{:type "file" :path "/a"}]}})
        project)
      (let [[kind method body] (fixt/last-to-server-of bridge :chat/prompt)]
        (is (= :request kind))
        (is (= "old-chat" (:chatId body)))
        (is (= "what is 2+2" (:message body)))
        (is (= "claude" (:model body)))
        (is (= "sonnet" (:variant body)))
        (is (= "ask" (:trust body)))
        (is (= "researcher" (:agent body)))
        (is (= "req-1" (:requestId body)))
        (is (= [{:type "file" :path "/a"}] (:contexts body)))))))

(deftest user-prompt-emits-new-chat-with-id-from-server-result
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :chat/prompt {:chat-id "fresh-chat-id"})
      (webview/handle
        (fixt/to-json-payload {:type "chat/userPrompt"
                               :data {:prompt "hi"}})
        project)
      (let [reply (fixt/last-to-webview-of-type bridge "chat/newChat")]
        (is (some? reply))
        (is (= "fresh-chat-id" (get-in reply [:data :id])))))))

(deftest selected-model-changed-carries-chat-id
  (testing "Regression a7221cb"
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle
          (fixt/to-json-payload {:type "chat/selectedModelChanged"
                                 :data {:chatId "chat-7"
                                        :model "claude"
                                        :variant "sonnet"}})
          project)
        (let [[kind method body] (fixt/last-to-server-of
                                  bridge :chat/selectedModelChanged)]
          (is (= :notify kind))
          (is (= "chat-7" (:chatId body))
              "chatId MUST be present on selectedModelChanged or the
               cached :server-config replay leaks across chats")
          (is (= "claude" (:model body)))
          (is (= "sonnet" (:variant body))))))))

(deftest selected-agent-changed-carries-chat-id
  (testing "Regression a7221cb"
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle
          (fixt/to-json-payload {:type "chat/selectedAgentChanged"
                                 :data {:chatId "chat-9"
                                        :agent "code-reviewer"}})
          project)
        (let [[kind _method body] (fixt/last-to-server-of
                                   bridge :chat/selectedAgentChanged)]
          (is (= :notify kind))
          (is (= "chat-9" (:chatId body)))
          (is (= "code-reviewer" (:agent body))))))))

(deftest chat-content-received-forwards-verbatim-to-webview
  (testing "Server emits per-token streaming via chat/contentReceived;
            the host MUST forward verbatim (no aggregation) so the
            React app renders one token at a time."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (api/chat-content-received {:project project}
                                   {:chatId "c1" :content "hello"})
        (api/chat-content-received {:project project}
                                   {:chatId "c1" :content " world"})
        (let [msgs (fixt/webview-of-type bridge "chat/contentReceived")]
          (is (= 2 (count msgs)))
          (is (= "hello" (get-in (first msgs) [:data :content])))
          (is (= " world" (get-in (second msgs) [:data :content]))))))))

(deftest chat-content-received-preserves-server-emission-order
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (doseq [i (range 5)]
        (api/chat-content-received {:project project}
                                   {:chatId "c1" :content (str "tok-" i)}))
      (let [msgs (fixt/webview-of-type bridge "chat/contentReceived")]
        (is (= ["tok-0" "tok-1" "tok-2" "tok-3" "tok-4"]
               (mapv #(get-in % [:data :content]) msgs))
            "ordering relies on lsp4clj pipeline-blocking parallelism=1
             -- verify the host forward path itself does not reorder")))))

(deftest query-context-round-trips-result
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :chat/queryContext
                        {:items [{:label "foo.clj"}]})
      (webview/handle
        (fixt/to-json-payload {:type "chat/queryContext"
                               :data {:query "fo"}})
        project)
      (let [reply (fixt/last-to-webview-of-type bridge "chat/queryContext")]
        (is (= [{:label "foo.clj"}] (get-in reply [:data :items])))))))

(deftest query-commands-tolerates-missing-arguments-field
  (testing "Regression f3c251f: ChatCommand.arguments arriving nil
            used to crash the command picker. The host-side forward
            must NOT reshape that nil into something that breaks the
            React side; just pass it through unchanged."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (fixt/stub-reply! bridge :chat/queryCommands
                          {:items [{:name "review" :arguments nil}]})
        (webview/handle
          (fixt/to-json-payload {:type "chat/queryCommands"
                                 :data {:query ""}})
          project)
        (let [reply (fixt/last-to-webview-of-type bridge "chat/queryCommands")
              first-item (-> reply :data :items first)]
          (is (= "review" (:name first-item)))
          (is (nil? (:arguments first-item))
              ":arguments nil must survive the forward unchanged"))))))

(deftest query-files-round-trips-result
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :chat/queryFiles
                        {:files ["/a/b.clj" "/c/d.clj"]})
      (webview/handle
        (fixt/to-json-payload {:type "chat/queryFiles"
                               :data {:query "clj"}})
        project)
      (let [reply (fixt/last-to-webview-of-type bridge "chat/queryFiles")]
        (is (= ["/a/b.clj" "/c/d.clj"] (get-in reply [:data :files])))))))

(deftest tool-call-approve-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "chat/toolCallApprove"
                               :data {:chatId "c1" :callId "tc-1"}})
        project)
      (let [[kind _ body] (fixt/last-to-server-of bridge :chat/toolCallApprove)]
        (is (= :notify kind))
        (is (= "tc-1" (:callId body)))))))

(deftest tool-call-reject-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "chat/toolCallReject"
                               :data {:chatId "c1" :callId "tc-1"}})
        project)
      (is (= :notify (first (fixt/last-to-server-of
                             bridge :chat/toolCallReject)))))))

(deftest prompt-stop-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "chat/promptStop"
                               :data {:chatId "c1"}})
        project)
      (let [[kind _ body] (fixt/last-to-server-of bridge :chat/promptStop)]
        (is (= :notify kind))
        (is (= "c1" (:chatId body)))))))

(deftest prompt-steer-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "chat/promptSteer"
                               :data {:chatId "c1" :text "be brief"}})
        project)
      (is (= :notify (first (fixt/last-to-server-of bridge :chat/promptSteer)))))))

(deftest chat-update-routes-to-server-as-request
  (testing "chat/update is a request (carries the new trust flag) per
            the chat-trust toggle wiring in webview.clj."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle
          (fixt/to-json-payload {:type "chat/update"
                                 :data {:chatId "c1"
                                        :title "renamed"
                                        :trust "trusted"}})
          project)
        (let [[kind _ body] (fixt/last-to-server-of bridge :chat/update)]
          (is (= :request kind))
          (is (= "c1" (:chatId body)))
          (is (= "renamed" (:title body)))
          (is (= "trusted" (:trust body))))))))

(deftest chat-delete-routes-to-server-as-request
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "chat/delete"
                               :data {:chatId "c1"}})
        project)
      (is (= :request (first (fixt/last-to-server-of bridge :chat/delete)))))))

(deftest chat-fork-routes-to-server-as-request
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "chat/fork"
                               :data {:chatId "c1" :messageId "m-5"}})
        project)
      (is (= :request (first (fixt/last-to-server-of bridge :chat/fork)))))))

(deftest chat-remove-flag-routes-to-server-as-request
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "chat/removeFlag"
                               :data {:chatId "c1" :flagId "f-1"}})
        project)
      (is (= :request (first (fixt/last-to-server-of bridge :chat/removeFlag)))))))

(deftest chat-cleared-forwarded-to-webview
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (api/chat-cleared {:project project} {:chatId "c1"})
      (let [reply (fixt/last-to-webview-of-type bridge "chat/cleared")]
        (is (= "c1" (get-in reply [:data :chatId])))))))

(deftest chat-deleted-forwards-only-the-chat-id
  (testing "Production code extracts :chatId -- verify the wire payload
            is the bare id, not the full params map."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (api/chat-deleted {:project project} {:chatId "c1" :extra "ignored"})
        (let [reply (fixt/last-to-webview-of-type bridge "chat/deleted")]
          (is (= "c1" (:data reply))))))))

(deftest chat-opened-forwarded-to-webview
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (api/chat-opened {:project project} {:chatId "c1" :title "Welcome"})
      (let [reply (fixt/last-to-webview-of-type bridge "chat/opened")]
        (is (= "c1" (get-in reply [:data :chatId])))
        (is (= "Welcome" (get-in reply [:data :title])))))))

(deftest chat-status-changed-forwarded-to-webview
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (api/chat-status-changed {:project project}
                               {:chatId "c1" :status "streaming"})
      (let [reply (fixt/last-to-webview-of-type bridge "chat/statusChanged")]
        (is (= "streaming" (get-in reply [:data :status])))))))

(deftest ask-question-registers-pending-promise-and-resolves-on-answer
  (testing "Server invokes our `chat/askQuestion` request handler,
            which:
              1. mints a requestId
              2. sends chat/askQuestion to the webview with the id
              3. parks on a promise registered under that id
            The webview answers via `chat/answerQuestion` inbound,
            which delivers the matching promise."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        ;; Park the ask call on a future so we can race the answer
        ;; through `handle` without blocking the test thread.
        (let [reply-future (future
                             (api/chat-ask-question
                              {:project project}
                              {:question "ok?"}))]
          ;; Allow the future to send the outbound chat/askQuestion
          ;; before we observe.
          (loop [i 0]
            (when (and (< i 50)
                       (empty? (fixt/webview-of-type bridge "chat/askQuestion")))
              (Thread/sleep 10)
              (recur (inc i))))
          (let [sent (fixt/last-to-webview-of-type bridge "chat/askQuestion")
                request-id (get-in sent [:data :requestId])]
            (is (some? request-id) "outbound payload includes a freshly minted requestId")
            ;; Now deliver the answer via the inbound dispatcher.
            (webview/handle
              (fixt/to-json-payload {:type "chat/answerQuestion"
                                     :data {:requestId request-id
                                            :answer "yes"}})
              project)
            (is (= {:answer "yes"} (deref reply-future 1000 :timeout)))))))))

(deftest ask-question-ignores-unknown-request-id
  (testing "answerQuestion with a requestId that was never minted must
            not throw and must not emit any host-side side effect (no
            outbound to server, no outbound to webview)."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (let [pending @@#'webview/pending-questions]
          (webview/handle
           (fixt/to-json-payload {:type "chat/answerQuestion"
                                  :data {:requestId "bogus"
                                         :answer "y"}})
           project)
          (is (= pending @@#'webview/pending-questions)
              "pending-questions atom must not be mutated for unknown ids")
          (is (empty? (fixt/sent-to-server bridge))
              "no server-bound request or notify")
          (is (empty? (fixt/sent-to-webview bridge))
              "no webview-bound message"))))))
