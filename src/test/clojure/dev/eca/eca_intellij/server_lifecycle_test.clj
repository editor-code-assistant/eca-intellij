(ns dev.eca.eca-intellij.server-lifecycle-test
  "Tests for server.clj's status broadcast + config.clj's OS-aware
   download path. The Windows .exe suffix (regression b27d515) and the
   listener-keys log line (added so blank-webview bug reports can be
   correlated with whether :webview was reached) both live here."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]
   [com.github.ericdallo.clj4intellij.tasks :as tasks]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.notification :as notification]
   [dev.eca.eca-intellij.server :as server]
   [dev.eca.eca-intellij.test-fixtures :as fixt])
  (:import
   [java.io File]))

(deftest broadcast-status-fans-out-to-every-registered-listener
  (testing "Each listener registered under :on-status-changed-fns must
            receive (project, status). :webview is the listener wired
            in tool_window.clj's onLoadingStateChange; missing it on
            broadcast time is the exact symptom that motivated the
            structured per-broadcast log line in server.clj."
    (fixt/with-test-project [project]
      (let [hits-webview (atom [])
            hits-statusbar (atom [])]
        (db/assoc-in project [:on-status-changed-fns]
                     {:webview (fn [p s] (swap! hits-webview conj [p s]))
                      :status-bar (fn [p s] (swap! hits-statusbar conj [p s]))})
        (#'server/broadcast-status! project :running)
        (is (= 1 (count @hits-webview)))
        (is (= 1 (count @hits-statusbar)))
        (is (= :running (-> @hits-webview first second)))
        (is (= project (-> @hits-webview first first)))))))

(deftest broadcast-status-with-no-listeners-does-not-throw
  (fixt/with-test-project [project]
    (is (nil? (#'server/broadcast-status! project :stopped)))))

(deftest broadcast-status-throwing-listener-propagates
  (testing "Current contract: production uses `run!` which does NOT
            catch. Listeners are expected to be tame (status-bar update,
            webview send-msg). If we ever want isolation across
            listeners, broadcast-status! needs a try/catch and this test
            should flip to assert siblings still fire."
    (fixt/with-test-project [project]
      (db/assoc-in project [:on-status-changed-fns]
                   {:throwy (fn [_ _] (throw (ex-info "boom" {})))})
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'server/broadcast-status! project :running))))))

(deftest download-server-path-uses-exe-suffix-on-windows
  (testing "Regression b27d515: on Windows the auto-downloaded server
            must land as `eca.exe`; without the extension the
            existence-check would always miss and we would re-download
            on every IDE start."
    (with-redefs [config/windows? (constantly true)]
      (is (= "eca.exe" (.getName (config/download-server-path)))))))

(deftest download-server-path-uses-no-extension-on-other-os
  (with-redefs [config/windows? (constantly false)]
    (is (= "eca" (.getName (config/download-server-path))))))

(deftest status-reads-current-db-status
  (fixt/with-test-project [project
                           :initial-db {:status :starting}]
    (is (= :starting (server/status project)))))

(deftest on-initialized-preserves-mcp-servers-cache
  (testing "Regression: issue #22. Before the fix, on-initialized did
            `(update-in [:session] (fn [_] {...}))` which wiped any
            keys populated before `initialized` completed. The ECA
            server can send `tool/serverUpdated` notifications ahead
            of the `initialized` reply (especially for cached MCP
            servers that come up fast), so wiping `:mcp-servers`
            here was the root cause of the empty Settings -> MCPs
            tab. on-initialized must MERGE into the existing
            session, not replace it."
    (fixt/with-test-project [project
                             :initial-db {:session
                                          {:mcp-servers
                                           {"foo" {:name "foo"
                                                   :status "running"}}}}]
      (#'server/on-initialized {:models [{:id "claude"}]
                                :chat-agents [{:id "agent"}]
                                :chat-default-agent "agent"
                                :chat-default-model "claude"
                                :chat-welcome-message "hi"}
                               project)
      (let [session (db/get-in project [:session])]
        (is (= {"foo" {:name "foo" :status "running"}}
               (:mcp-servers session))
            ":mcp-servers populated before initialized MUST survive")
        (is (= [{:id "claude"}] (:models session)))
        (is (= "claude" (:chat-selected-model session)))
        (is (= "hi" (:welcome-message session)))))))

(deftest sha256-hex-computes-file-digest
  (let [f (File/createTempFile "eca-sha-test" ".bin")]
    (try
      (spit f "hello")
      (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
             (#'server/sha256-hex f)))
      (finally
        (.delete f)))))

(deftest expected-sha256-reads-published-digest
  (testing "Reads the bare-hex digest the eca release workflow publishes as
            `<artifact>.sha256` (shasum -a 256 | awk '{print $1}'). Part of
            the download-integrity fix: downloads used to be unzipped
            straight from the network stream with no verification, so a
            truncated transfer produced a corrupt binary that macOS
            SIGKILLs for an invalid code signature."
    (let [artifact (File/createTempFile "eca-artifact" ".zip")
          checksum-file (io/file (str (.getCanonicalPath artifact) ".sha256"))
          digest (apply str (repeat 64 "a"))]
      (try
        (spit checksum-file (str digest "\n"))
        (is (= digest (#'server/expected-sha256 (.getCanonicalPath artifact))))
        (finally
          (.delete artifact)
          (.delete checksum-file))))))

(deftest expected-sha256-tolerates-hex-filename-format-and-case
  (let [artifact (File/createTempFile "eca-artifact" ".zip")
        checksum-file (io/file (str (.getCanonicalPath artifact) ".sha256"))
        digest (apply str (repeat 64 "b"))]
    (try
      (spit checksum-file (str (string/upper-case digest) "  eca-native-macos-aarch64.zip\n"))
      (is (= digest (#'server/expected-sha256 (.getCanonicalPath artifact))))
      (finally
        (.delete artifact)
        (.delete checksum-file)))))

(deftest expected-sha256-returns-nil-on-malformed-content
  (testing "A checksum endpoint answering with an error page must disable
            verification, not install garbage or hard-fail the download."
    (let [artifact (File/createTempFile "eca-artifact" ".zip")
          checksum-file (io/file (str (.getCanonicalPath artifact) ".sha256"))]
      (try
        (spit checksum-file "<html>Not Found</html>")
        (is (nil? (#'server/expected-sha256 (.getCanonicalPath artifact))))
        (finally
          (.delete artifact)
          (.delete checksum-file))))))

(deftest expected-sha256-returns-nil-when-checksum-file-missing
  (is (nil? (#'server/expected-sha256
             (str "/nonexistent/eca-" (System/nanoTime) ".zip")))))

(deftest version-from-release-redirect-parses-tag-url
  (testing "The GitHub /releases/latest redirect Location header points at
            /releases/tag/<version>; that trailing segment is the latest
            *published* release version."
    (is (= "0.148.0"
           (#'server/version-from-release-redirect
            "https://github.com/editor-code-assistant/eca/releases/tag/0.148.0")))
    (is (= "0.148.0"
           (#'server/version-from-release-redirect
            "https://github.com/editor-code-assistant/eca/releases/tag/0.148.0\n")))))

(deftest version-from-release-redirect-nil-on-absent-or-malformed
  (is (nil? (#'server/version-from-release-redirect nil)))
  (is (nil? (#'server/version-from-release-redirect
             "https://github.com/editor-code-assistant/eca/releases")))
  (is (nil? (#'server/version-from-release-redirect
             "https://github.com/editor-code-assistant/eca/releases/tag/"))))

(deftest download-failure-falls-back-to-existing-binary
  (testing "Regression (JetBrains plugin-checker build 365160, 2026-07-22):
            during an eca release window the advertised latest version can
            precede its artifacts by many minutes, so the artifact download
            404s (FileNotFoundException). With a previously downloaded binary
            on disk, startup must warn and reuse it instead of failing."
    (fixt/with-test-project [project]
      (let [existing (File/createTempFile "eca-server" nil)
            notifications (atom [])]
        (try
          (with-redefs [server/download-server! (fn [& _]
                                                  (throw (java.io.FileNotFoundException.
                                                          "https://github.com/.../eca-native-static-linux-amd64.zip")))
                        notification/show-notification! (fn [n] (swap! notifications conj n))]
            (is (true? (#'server/download-or-use-existing!
                        project nil existing (io/file "unused-version-file") "9.9.9")))
            (is (= :warning (:type (first @notifications)))))
          (finally
            (.delete existing)))))))

(deftest download-failure-without-existing-binary-returns-false
  (testing "Same checker regression: with no previously downloaded binary the
            failure is surfaced as a user notification and a false return --
            never an exception bubbling into Logger.error, which JetBrains'
            InstallPluginTest counts as a plugin failure."
    (fixt/with-test-project [project]
      (let [notifications (atom [])
            missing (io/file (str "/nonexistent/eca-" (System/nanoTime)))]
        (with-redefs [server/download-server! (fn [& _]
                                                (throw (java.io.FileNotFoundException. "404")))
                      notification/show-notification! (fn [n] (swap! notifications conj n))]
          (is (false? (#'server/download-or-use-existing!
                       project nil missing (io/file "unused-version-file") "9.9.9")))
          (is (= :error (:type (first @notifications)))))))))

(deftest download-non-io-failure-propagates
  (testing "Non-environmental failures (e.g. checksum mismatch ex-info) are
            potential plugin/release bugs and must keep propagating to
            start!'s catch, where they are logged as real errors."
    (fixt/with-test-project [project]
      (with-redefs [server/download-server! (fn [& _]
                                              (throw (ex-info "Checksum mismatch" {})))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (#'server/download-or-use-existing!
                      project nil (io/file "unused") (io/file "unused") "9.9.9")))))))

(deftest shutdown-works-when-server-stuck-starting
  (testing "Regression: shutdown! was gated on api/connected-client, which
            only returns a client while status is :running. A stuck-starting
            server could not be stopped or restarted, and the status-bar
            restart action then spawned a second process next to the
            orphaned one. shutdown! must reach :stopped from any status."
    (fixt/with-test-project [project
                             :initial-db {:status :starting}]
      (let [statuses (atom [])]
        (db/assoc-in project [:on-status-changed-fns :test]
                     (fn [_ s] (swap! statuses conj s)))
        (server/shutdown! project)
        (is (= :stopped (db/get-in project [:status])))
        (is (= [:stopped] @statuses))))))

(deftest shutdown-does-not-hang-on-dead-server
  (testing "Regression: shutdown! bare-deref'd the :shutdown request; when
            the server process had already died the promise never delivered
            and the restart action hung forever. The deref is now bounded,
            so shutdown! completes and still cleans up."
    (fixt/with-test-project [project
                             :initial-db {:status :running
                                          :client ::dead-client}]
      (with-redefs [api/request! (fn [_ _] (promise)) ; never delivered
                    api/notify! (fn [_ _] nil)]
        (let [done (future (server/shutdown! project))]
          (is (not= ::timeout (deref done 15000 ::timeout))
              "shutdown! must return within its bounded timeout")
          (is (= :stopped (db/get-in project [:status]))))))))

(deftest start-ignores-reentry-while-already-starting
  (testing "Regression: start! unconditionally kicked a new background
            startup task; invoking it while :starting (e.g. project open
            racing the status-bar restart action during a slow first
            download) spawned a duplicate server process."
    (fixt/with-test-project [project
                             :initial-db {:status :starting}]
      (let [task-runs (atom 0)]
        (with-redefs [tasks/run-background-task! (fn [& _] (swap! task-runs inc))]
          (server/start! project)
          (is (zero? @task-runs))
          (is (= :starting (db/get-in project [:status]))))))))
