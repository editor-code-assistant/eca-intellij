(ns dev.eca.eca-intellij.webview-mcp-providers-jobs-test
  "Tests for the mcp/*, providers/*, jobs/* branches of webview/handle.
   These three families share the same requestId-echo reply pattern;
   the tests below pin it."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.test-fixtures :as fixt]
   [dev.eca.eca-intellij.webview :as webview]))

(deftest mcp-start-server-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "mcp/startServer"
                               :data {:name "fs"}})
        project)
      (let [[kind _ body] (fixt/last-to-server-of bridge :mcp/startServer)]
        (is (= :notify kind))
        (is (= "fs" (:name body)))))))

(deftest mcp-stop-server-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "mcp/stopServer"
                               :data {:name "fs"}})
        project)
      (is (= :notify (first (fixt/last-to-server-of bridge :mcp/stopServer)))))))

(deftest mcp-connect-server-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "mcp/connectServer"
                               :data {:name "fs"}})
        project)
      (is (= :notify (first (fixt/last-to-server-of bridge :mcp/connectServer)))))))

(deftest mcp-logout-server-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "mcp/logoutServer"
                               :data {:name "github"}})
        project)
      (is (= :notify (first (fixt/last-to-server-of bridge :mcp/logoutServer)))))))

(deftest mcp-disable-server-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "mcp/disableServer"
                               :data {:name "fs"}})
        project)
      (is (= :notify (first (fixt/last-to-server-of bridge :mcp/disableServer)))))))

(deftest mcp-enable-server-routes-to-server-as-notify
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle
        (fixt/to-json-payload {:type "mcp/enableServer"
                               :data {:name "fs"}})
        project)
      (is (= :notify (first (fixt/last-to-server-of bridge :mcp/enableServer)))))))

(deftest mcp-update-server-is-request-and-echoes-request-id
  (testing "mcp/updateServer is wrapped in a future to keep the JS-query
            thread responsive. The reply MUST include the requestId the
            webview sent so the React reply handler can correlate."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (fixt/stub-reply! bridge :mcp/updateServer {:ok true :name "fs"})
        (webview/handle
          (fixt/to-json-payload {:type "mcp/updateServer"
                                 :data {:name "fs" :requestId "req-42"}})
          project)
        ;; The branch is a future -- give it a moment to ship the
        ;; outbound message.
        (loop [i 0]
          (when (and (< i 50)
                     (empty? (fixt/webview-of-type bridge "mcp/updateServer")))
            (Thread/sleep 10)
            (recur (inc i))))
        (let [reply (fixt/last-to-webview-of-type bridge "mcp/updateServer")]
          (is (= "req-42" (get-in reply [:data :requestId])))
          (is (true? (get-in reply [:data :ok]))))))))

(deftest tool-server-updated-merges-into-db-and-forwards-vec
  (testing "Inbound notification updates the per-name MCP server map in
            db.clj, then forwards the *values* (a vector of all known
            servers) to the webview under the plural type
            `tool/serversUpdated` -- the React Tools panel renders the
            entire roster on every push."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (api/tool-server-updated {:project project}
                                 {:name "fs" :status "running"})
        (api/tool-server-updated {:project project}
                                 {:name "github" :status "running"})
        (let [reply (fixt/last-to-webview-of-type bridge "tool/serversUpdated")]
          (is (= 2 (count (:data reply))))
          (is (= #{"fs" "github"} (->> reply :data (map :name) set))))
        (let [stored (db/get-in project [:session :mcp-servers])]
          (is (= #{"fs" "github"} (set (keys stored)))))))))

(deftest providers-list-echoes-request-id
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :providers/list {:providers ["anthropic" "openai"]})
      (webview/handle
        (fixt/to-json-payload {:type "providers/list"
                               :data {:requestId "lst-1"}})
        project)
      (loop [i 0]
        (when (and (< i 50)
                   (empty? (fixt/webview-of-type bridge "providers/list")))
          (Thread/sleep 10)
          (recur (inc i))))
      (let [reply (fixt/last-to-webview-of-type bridge "providers/list")]
        (is (= "lst-1" (get-in reply [:data :requestId])))
        (is (= ["anthropic" "openai"] (get-in reply [:data :providers])))))))

(deftest providers-login-echoes-request-id
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :providers/login {:status "logged-in"})
      (webview/handle
        (fixt/to-json-payload {:type "providers/login"
                               :data {:provider "anthropic" :requestId "lg-1"}})
        project)
      (loop [i 0]
        (when (and (< i 50)
                   (empty? (fixt/webview-of-type bridge "providers/login")))
          (Thread/sleep 10)
          (recur (inc i))))
      (let [reply (fixt/last-to-webview-of-type bridge "providers/login")]
        (is (= "lg-1" (get-in reply [:data :requestId])))))))

(deftest providers-login-input-echoes-request-id
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :providers/loginInput {:status "ok"})
      (webview/handle
        (fixt/to-json-payload {:type "providers/loginInput"
                               :data {:value "tok" :requestId "lin-1"}})
        project)
      (loop [i 0]
        (when (and (< i 50)
                   (empty? (fixt/webview-of-type bridge "providers/loginInput")))
          (Thread/sleep 10)
          (recur (inc i))))
      (let [reply (fixt/last-to-webview-of-type bridge "providers/loginInput")]
        (is (= "lin-1" (get-in reply [:data :requestId])))))))

(deftest providers-logout-echoes-request-id
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :providers/logout {:status "ok"})
      (webview/handle
        (fixt/to-json-payload {:type "providers/logout"
                               :data {:provider "anthropic" :requestId "out-1"}})
        project)
      (loop [i 0]
        (when (and (< i 50)
                   (empty? (fixt/webview-of-type bridge "providers/logout")))
          (Thread/sleep 10)
          (recur (inc i))))
      (let [reply (fixt/last-to-webview-of-type bridge "providers/logout")]
        (is (= "out-1" (get-in reply [:data :requestId])))))))

(deftest providers-updated-forwarded-to-webview
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (api/providers-updated {:project project}
                             {:providers [{:id "anthropic" :loggedIn true}]})
      (let [reply (fixt/last-to-webview-of-type bridge "providers/updated")]
        (is (= [{:id "anthropic" :loggedIn true}]
               (get-in reply [:data :providers])))))))

(deftest jobs-list-echoes-request-id
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :jobs/list {:jobs [{:id "j-1" :status "running"}]})
      (webview/handle
        (fixt/to-json-payload {:type "jobs/list"
                               :data {:requestId "jl-1"}})
        project)
      (loop [i 0]
        (when (and (< i 50)
                   (empty? (fixt/webview-of-type bridge "jobs/list")))
          (Thread/sleep 10)
          (recur (inc i))))
      (let [reply (fixt/last-to-webview-of-type bridge "jobs/list")]
        (is (= "jl-1" (get-in reply [:data :requestId])))
        (is (= [{:id "j-1" :status "running"}] (get-in reply [:data :jobs])))))))

(deftest jobs-read-output-translates-job-id-to-kebab-and-echoes-request-id
  (testing "Production code converts the webview's camelCase :jobId into
            the server-side kebab :job-id. The reply still ships back
            under the camelCase request id."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (fixt/stub-reply! bridge :jobs/readOutput {:output "stdout chunk"})
        (webview/handle
          (fixt/to-json-payload {:type "jobs/readOutput"
                                 :data {:jobId "j-1" :requestId "jr-1"}})
          project)
        (loop [i 0]
          (when (and (< i 50)
                     (empty? (fixt/webview-of-type bridge "jobs/readOutput")))
            (Thread/sleep 10)
            (recur (inc i))))
        (let [reply (fixt/last-to-webview-of-type bridge "jobs/readOutput")
              [_kind _method body] (fixt/last-to-server-of bridge :jobs/readOutput)]
          (is (= "jr-1" (get-in reply [:data :requestId])))
          (is (= "stdout chunk" (get-in reply [:data :output])))
          (is (= "j-1" (:job-id body)) ":job-id MUST be kebab on the server-facing body"))))))

(deftest jobs-kill-echoes-request-id
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (fixt/stub-reply! bridge :jobs/kill {:ok true})
      (webview/handle
        (fixt/to-json-payload {:type "jobs/kill"
                               :data {:jobId "j-1" :requestId "jk-1"}})
        project)
      (loop [i 0]
        (when (and (< i 50)
                   (empty? (fixt/webview-of-type bridge "jobs/kill")))
          (Thread/sleep 10)
          (recur (inc i))))
      (let [reply (fixt/last-to-webview-of-type bridge "jobs/kill")]
        (is (= "jk-1" (get-in reply [:data :requestId])))))))

(deftest jobs-updated-forwarded-to-webview
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (api/jobs-updated {:project project}
                        {:jobs [{:id "j-1" :status "exited" :exitCode 0}]})
      (let [reply (fixt/last-to-webview-of-type bridge "jobs/updated")]
        (is (= 1 (count (get-in reply [:data :jobs]))))))))
