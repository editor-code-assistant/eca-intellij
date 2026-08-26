(ns dev.eca.eca-intellij.inline-chat-test
  "Unit tests for the pure inline-chat state machine. The content
   routing mirrors eca-emacs/eca-vscode: a regression here would make
   inlays show stale statuses, lose streamed text or hide pending tool
   approvals."
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.inline-chat :as inline-chat]
   [dev.eca.eca-intellij.test-fixtures :refer [with-stub-bridge with-test-project
                                               last-to-webview-of-type]])
  (:import
   [com.intellij.openapi.editor Inlay]))

(def ^:private running-state
  {:answer "" :status "Waiting model..." :state :running :pending-tools []})

(defn ^:private evt [role content]
  {:role role :content content})

(deftest transition-text-events
  (testing "a user message starts a fresh turn"
    (is (= running-state
           (inline-chat/transition {:answer "old answer" :status "Done" :state :done :pending-tools []}
                                   (evt "user" {:type "text" :text "again?"})))))
  (testing "assistant text accumulates and marks streaming"
    (let [s (-> running-state
                (inline-chat/transition (evt "assistant" {:type "text" :text "Hel"}))
                (inline-chat/transition (evt "assistant" {:type "text" :text "lo"})))]
      (is (= "Hello" (:answer s)))
      (is (= "Streaming..." (:status s)))
      (is (= :running (:state s)))))
  (testing "system text surfaces errors in the answer without touching status"
    (let [s (inline-chat/transition (assoc running-state :status "Streaming...")
                                    (evt "system" {:type "text" :text "Error: boom"}))]
      (is (= "Error: boom" (:answer s)))
      (is (= "Streaming..." (:status s))))))

(deftest transition-progress-events
  (testing "progress running updates the status text"
    (is (= "Waiting LLM..."
           (:status (inline-chat/transition running-state
                                            (evt "system" {:type "progress" :state "running" :text "Waiting LLM..."}))))))
  (testing "progress running without text falls back"
    (is (= "Running..."
           (:status (inline-chat/transition running-state
                                            (evt "system" {:type "progress" :state "running"}))))))
  (testing "progress running does not clobber pending approval summaries"
    (let [pending (assoc running-state :pending-tools [["1" "Tool eca_shell_command needs approval"]]
                         :status "Tool eca_shell_command needs approval")]
      (is (= pending
             (inline-chat/transition pending
                                     (evt "system" {:type "progress" :state "running" :text "Waiting for tool call approval"}))))))
  (testing "progress finished finalizes the turn"
    (let [s (inline-chat/transition (assoc running-state :pending-tools [["1" "x"]])
                                    (evt "system" {:type "progress" :state "finished"}))]
      (is (= "Done" (:status s)))
      (is (= :done (:state s)))
      (is (empty? (:pending-tools s))))))

(deftest transition-reason-events
  (is (= "Thinking..."
         (:status (inline-chat/transition running-state (evt "assistant" {:type "reasonStarted" :id "r1"})))))
  (is (= "Waiting model..."
         (:status (inline-chat/transition running-state (evt "assistant" {:type "reasonFinished" :id "r1"}))))))

(deftest transition-tool-call-events
  (testing "toolCallPrepare shows the summary or a fallback"
    (is (= "Reading file foo..."
           (:status (inline-chat/transition running-state
                                            (evt "assistant" {:type "toolCallPrepare" :id "1" :name "read" :summary "Reading file foo..."})))))
    (is (= "Preparing tool read..."
           (:status (inline-chat/transition running-state
                                            (evt "assistant" {:type "toolCallPrepare" :id "1" :name "read"}))))))
  (testing "toolCallRun without approval shows running"
    (let [s (inline-chat/transition running-state
                                    (evt "assistant" {:type "toolCallRun" :id "1" :name "read" :manual-approval false}))]
      (is (= "Running tool read..." (:status s)))
      (is (empty? (:pending-tools s)))))
  (testing "toolCallRun with approval tracks pending and joins summaries in arrival order"
    (let [s (-> running-state
                (inline-chat/transition (evt "assistant" {:type "toolCallRun" :id "1" :name "shell" :manual-approval true :summary "Run ls"}))
                (inline-chat/transition (evt "assistant" {:type "toolCallRun" :id "2" :name "edit" :manual-approval true})))]
      (is (= [["1" "Run ls"] ["2" "Tool edit needs approval"]] (:pending-tools s)))
      (is (= "Run ls, Tool edit needs approval" (:status s)))))
  (testing "toolCallRunning drops the pending entry (approved elsewhere)"
    (let [pending (-> running-state
                      (inline-chat/transition (evt "assistant" {:type "toolCallRun" :id "1" :name "shell" :manual-approval true :summary "Run ls"})))
          s (inline-chat/transition pending (evt "assistant" {:type "toolCallRunning" :id "1" :name "shell" :summary "Running ls"}))]
      (is (empty? (:pending-tools s)))
      (is (= "Running ls" (:status s)))))
  (testing "toolCalled shows remaining pending summaries or waits for model"
    (let [two-pending (-> running-state
                          (inline-chat/transition (evt "assistant" {:type "toolCallRun" :id "1" :name "a" :manual-approval true :summary "A"}))
                          (inline-chat/transition (evt "assistant" {:type "toolCallRun" :id "2" :name "b" :manual-approval true :summary "B"})))
          one-left (inline-chat/transition two-pending (evt "assistant" {:type "toolCalled" :id "1" :name "a"}))
          none-left (inline-chat/transition one-left (evt "assistant" {:type "toolCalled" :id "2" :name "b"}))]
      (is (= "B" (:status one-left)))
      (is (= "Waiting model..." (:status none-left)))
      (is (= :running (:state none-left)))))
  (testing "toolCallRejected with none left shows rejection"
    (let [pending (inline-chat/transition running-state
                                          (evt "assistant" {:type "toolCallRun" :id "1" :name "a" :manual-approval true}))
          s (inline-chat/transition pending (evt "assistant" {:type "toolCallRejected" :id "1" :name "a"}))]
      (is (= "Tool call rejected" (:status s))))))

(deftest transition-unknown-content-is-ignored
  (is (= running-state
         (inline-chat/transition running-state (evt "assistant" {:type "usage" :sessionTokens 10})))))

(defn ^:private line-text
  "Concatenated visible text of a styled display line."
  [line]
  (apply str (map :text (:segments line))))

(deftest markdown->lines-test
  (testing "plain text stays plain"
    (is (= [{:segments [{:text "hello" :style :plain}]}]
           (inline-chat/markdown->lines "hello"))))
  (testing "inline markup is hidden and styled"
    (is (= [{:text "use " :style :plain}
            {:text "foo" :style :code}
            {:text " and " :style :plain}
            {:text "bar" :style :bold}
            {:text " or " :style :plain}
            {:text "baz" :style :italic}]
           (:segments (first (inline-chat/markdown->lines "use `foo` and **bar** or *baz*"))))))
  (testing "links show only their text"
    (is (= [{:text "see " :style :plain}
            {:text "docs" :style :link}]
           (:segments (first (inline-chat/markdown->lines "see [docs](https://eca.dev)"))))))
  (testing "headers drop the #s and style as header"
    (is (= [{:text "Title" :style :header}]
           (:segments (first (inline-chat/markdown->lines "## Title"))))))
  (testing "list markers become bullets"
    (is (= "• first" (line-text (first (inline-chat/markdown->lines "- first")))))
    (is (= "  • nested" (line-text (first (inline-chat/markdown->lines "  * nested"))))))
  (testing "code fences are dropped and content marked as code block"
    (let [lines (inline-chat/markdown->lines "before\n```clojure\n(+ 1 2)\n```\nafter")]
      (is (= ["before" "(+ 1 2)" "after"] (map line-text lines)))
      (is (= [nil true nil] (map :code-block? lines)))
      (is (= :code (-> lines second :segments first :style))))))

(deftest wrap-segments-test
  (is (= [[]] (inline-chat/wrap-segments [] 10)))
  (is (= [[{:text "short" :style :plain}]]
         (inline-chat/wrap-segments [{:text "short" :style :plain}] 10)))
  (testing "long segments split preserving style"
    (is (= [[{:text "0123456789" :style :code}]
            [{:text "abcde" :style :code}]]
           (inline-chat/wrap-segments [{:text "0123456789abcde" :style :code}] 10))))
  (testing "wrap point can fall between segments"
    (is (= [[{:text "aaaa" :style :plain} {:text "bbbb" :style :bold}]
            [{:text "cccc" :style :plain}]]
           (inline-chat/wrap-segments [{:text "aaaa" :style :plain}
                                       {:text "bbbb" :style :bold}
                                       {:text "cccc" :style :plain}]
                                      8)))))

(deftest display-lines-test
  (testing "blank answer renders nothing"
    (is (= [] (inline-chat/display-lines {:answer "" :state :running} 80 5)))
    (is (= [] (inline-chat/display-lines {:answer "  \n" :state :running} 80 5))))
  (testing "short answers render fully"
    (is (= ["a" "b"] (map line-text (inline-chat/display-lines {:answer "a\nb" :state :running} 80 5)))))
  (testing "while streaming shows the tail with an indicator"
    (let [answer (string/join "\n" (map str (range 10)))
          lines (inline-chat/display-lines {:answer answer :state :running} 80 5)]
      (is (= 6 (count lines)))
      (is (= "... 5 earlier lines" (line-text (first lines))))
      (is (= :dim (-> lines first :segments first :style)))
      (is (= ["5" "6" "7" "8" "9"] (map line-text (rest lines))))))
  (testing "when done shows the head with an indicator"
    (let [answer (string/join "\n" (map str (range 10)))
          lines (inline-chat/display-lines {:answer answer :state :done} 80 5)]
      (is (= 6 (count lines)))
      (is (= ["0" "1" "2" "3" "4"] (map line-text (subvec lines 0 5))))
      (is (= "... +5 lines, open the chat for the full answer" (line-text (last lines))))))
  (testing "long lines are wrapped before capping"
    (is (= ["01234" "56789"]
           (map line-text (inline-chat/display-lines {:answer "0123456789" :state :running} 5 5))))))

(deftest adjusted-end-line-test
  (testing "selection ending at the beginning of a line drops that line"
    (is (= 4 (inline-chat/adjusted-end-line 2 5 true))))
  (testing "selection ending mid-line keeps it"
    (is (= 5 (inline-chat/adjusted-end-line 2 5 false))))
  (testing "single-line selection at line start keeps the line"
    (is (= 2 (inline-chat/adjusted-end-line 2 2 true)))))

(defn ^:private seed-session!
  "Register a fake inline session in db for PROJECT, returning its state
   atom. The reified Inlay only answers `isValid`, which is all the
   notification path touches."
  [project chat-id]
  (let [state* (atom {:answer "" :status "Waiting model..." :state :sending :pending-tools []})]
    (db/assoc-in project [:inline-chat :sessions chat-id]
                 {:chat-id chat-id
                  :state* state*
                  :inlay (reify Inlay
                           (isValid [_] true)
                           (dispose [_]))
                  :doc-path "/tmp/f.clj"
                  :repaint! (fn [])})
    state*))

(deftest content-received-params-are-kebab-cased
  (testing "lsp4clj kebab-cases wire keys (chatId -> :chat-id,
            manualApproval -> :manual-approval): the real defmethod must
            route kebab params into the session. Regression: sessions
            froze at 'Waiting model...' because handlers read :chatId."
    (with-test-project [project]
      (with-stub-bridge _bridge
        (let [state* (seed-session! project "chat-1")]
          (api/chat-content-received {:project project}
                                     {:chat-id "chat-1"
                                      :role "assistant"
                                      :content {:type "text" :text "hi"}})
          (is (= "hi" (:answer @state*)))
          (is (= "Streaming..." (:status @state*)))
          (api/chat-content-received {:project project}
                                     {:chat-id "chat-1"
                                      :role "assistant"
                                      :content {:type "toolCallRun" :id "t1" :name "shell"
                                                :manual-approval true :summary "Run ls"}})
          (is (= [["t1" "Run ls"]] (:pending-tools @state*)))
          (api/chat-content-received {:project project}
                                     {:chat-id "chat-1"
                                      :role "system"
                                      :content {:type "progress" :state "finished"}})
          (is (= :done (:state @state*))))))))

(deftest subagent-content-is-ignored
  (with-test-project [project]
    (with-stub-bridge _bridge
      (let [state* (seed-session! project "chat-1")]
        (api/chat-content-received {:project project}
                                   {:chat-id "chat-1"
                                    :parent-chat-id "parent-1"
                                    :role "assistant"
                                    :content {:type "text" :text "subagent noise"}})
        (is (= "" (:answer @state*)))))))

(deftest chat-deleted-forwards-kebab-id-and-clears-session
  (testing "chat/deleted params carry :chat-id; the webview forward must
            send the id (was nil while reading :chatId) and the inline
            session plus its sticky binding must be dropped."
    (with-test-project [project]
      (with-stub-bridge bridge
        (seed-session! project "chat-1")
        (db/assoc-in project [:inline-chat :sticky "/tmp/f.clj"] "chat-1")
        (api/chat-deleted {:project project} {:chat-id "chat-1"})
        (is (= "chat-1" (:data (last-to-webview-of-type bridge "chat/deleted"))))
        (is (nil? (db/get-in project [:inline-chat :sessions "chat-1"])))
        (is (empty? (db/get-in project [:inline-chat :sticky])))))))

(deftest session-actions-test
  (testing "idle sessions offer follow-up, open chat and dismiss"
    (is (= [:follow-up :open-chat :dismiss]
           (map :id (inline-chat/session-actions {:state :done :pending-tools []})))))
  (testing "running sessions offer stop"
    (is (= [:stop :open-chat :dismiss]
           (map :id (inline-chat/session-actions {:state :running :pending-tools []})))))
  (testing "pending approvals add approve/reject"
    (is (= [:stop :approve :reject :open-chat :dismiss]
           (map :id (inline-chat/session-actions {:state :running :pending-tools [["1" "x"]]})))))
  (testing "dead sessions only offer dismiss"
    (is (= [:dismiss]
           (map :id (inline-chat/session-actions {:state :done :pending-tools [] :dead? true}))))))
