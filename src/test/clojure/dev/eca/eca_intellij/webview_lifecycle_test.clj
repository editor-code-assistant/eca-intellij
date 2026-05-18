(ns dev.eca.eca-intellij.webview-lifecycle-test
  "Integration tests for the webview ↔ host lifecycle handshake.

   The webview/ready replay and the api/config-updated :chatId-strip
   are the two pieces most recently bitten by regressions; both have
   named tests below."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.test-fixtures :as fixt]
   [dev.eca.eca-intellij.webview :as webview]))

(deftest webview-ready-sets-webview-ready-flag
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
      (is (true? (db/get-in project [:webview-ready?]))
          ":webview-ready? must flip true so the 10s watchdog stops"))))

(deftest webview-ready-replays-status-running-emits-both-frames
  (testing "When status is :running on ready, the host MUST emit
            server/setWorkspaceFolders (so the React app rebuilds its
            workspace tree) AND server/statusChanged (so the chat input
            unblocks). Missing either was the 'blank webview' symptom in
            the multi-project bug repro."
    (fixt/with-test-project [project
                             :initial-db {:status :running
                                          :settings {:server-path "/x"}}]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (let [types (->> (fixt/sent-to-webview bridge) (map :type) set)]
          (is (contains? types "server/setWorkspaceFolders"))
          (is (contains? types "server/statusChanged"))
          (is (contains? types "config/updated"))
          (is (= "Running"
                 (-> (fixt/last-to-webview-of-type bridge "server/statusChanged")
                     :data))))))))

(deftest webview-ready-replays-status-stopped-emits-only-status
  (testing "When status is :stopped the workspace-folders frame must NOT
            be replayed (it carries the live :running URI shape only)."
    (fixt/with-test-project [project
                             :initial-db {:status :stopped
                                          :settings {}}]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (let [types (->> (fixt/sent-to-webview bridge) (map :type) set)]
          (is (not (contains? types "server/setWorkspaceFolders")))
          (is (contains? types "server/statusChanged"))
          (is (= "Stopped"
                 (-> (fixt/last-to-webview-of-type bridge "server/statusChanged")
                     :data))))))))

(deftest webview-ready-skips-config-updated-when-settings-empty
  (testing "handle-config-changed short-circuits if :settings is nil.
            The webview would otherwise receive a half-formed
            config/updated frame and crash trying to render unknown
            settings fields."
    (fixt/with-test-project [project
                             :initial-db {:status :running
                                          :settings nil}]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (let [types (->> (fixt/sent-to-webview bridge) (map :type) set)]
          (is (not (contains? types "config/updated"))))))))

(deftest webview-ready-registers-on-focus-changed-listener
  (fixt/with-test-project [project]
    (fixt/with-stub-bridge bridge
      (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
      (is (contains? (db/get-in project [:on-focus-changed-fns]) :webview)
          ":webview key in :on-focus-changed-fns is what cursor-editor-listener fans
           out to; missing -> no live editor-focus updates to the chat"))))

(deftest webview-ready-replays-cached-mcp-servers
  (testing "Regression: issue #22. `tool/serverUpdated` LSP
            notifications are reactive-only; if the tool window is
            re-opened (or the user opens Settings -> MCPs) after all
            notifications have already fired, the React `mcp.servers`
            slice would otherwise sit empty. `webview/ready` must
            replay the cached roster as `tool/serversUpdated` so the
            settings tab is never blank when servers actually exist."
    (fixt/with-test-project [project
                             :initial-db {:session
                                          {:mcp-servers
                                           {"foo" {:name "foo"
                                                   :type "mcp"
                                                   :status "running"
                                                   :tools []}
                                            "bar" {:name "bar"
                                                   :type "mcp"
                                                   :status "stopped"
                                                   :tools []}}}}]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (let [out (fixt/last-to-webview-of-type bridge "tool/serversUpdated")]
          (is (some? out)
              "webview/ready MUST emit tool/serversUpdated even with no live notification")
          (is (= #{"foo" "bar"}
                 (->> (:data out) (map :name) set))
              "all cached MCP servers must be replayed"))))))

(deftest webview-ready-emits-empty-mcp-list-when-no-servers
  (testing "With an empty cache the replay still fires (just with an
            empty list). This keeps the webview's mcp.servers slice
            consistent with the host's truth-of-record on every ready
            handshake and lets the React side clear any stale rows."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (let [out (fixt/last-to-webview-of-type bridge "tool/serversUpdated")]
          (is (some? out))
          (is (= [] (:data out))))))))

(deftest webview-ready-subscribes-log-store-with-replace-semantics
  (testing "Re-delivery of webview/ready (e.g. tool-window re-open) must
            replace the prior :webview log subscriber, not stack a
            second one. Stacking would double every logs/appended."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (is (= 1 (count (db/get-in project [:log-subscribers])))
            "exactly one :webview subscriber after two webview/ready calls")))))

(deftest config-updated-strips-chat-id-from-cached-server-config
  (testing "Regression: a7221cb. The server emits per-chat config
            scoped with :chatId; replaying :server-config on
            webview/ready must NOT leak a stale chatId into a new chat
            window. The dispatch path:
            api/config-updated -> db/update-in :server-config
            -> (merge old new) but with :chatId dissoc'd."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (api/config-updated {:project project}
                            {:chatId "should-be-stripped"
                             :model "claude"
                             :trust "ask"})
        (let [cached (db/get-in project [:server-config])]
          (is (= "claude" (:model cached)))
          (is (= "ask" (:trust cached)))
          (is (not (contains? cached :chatId))
              ":chatId MUST NOT land in cached :server-config"))))))

(deftest config-updated-forwards-original-params-to-webview
  (testing "The live forward (handle-config-changed) keeps :chatId
            because the webview's currently-focused chat handler does
            want to scope the immediate update."
    (fixt/with-test-project [project
                             :initial-db {:settings {:server-path "/x"}}]
      (fixt/with-stub-bridge bridge
        (api/config-updated {:project project}
                            {:chatId "chat-1"
                             :model "claude"})
        (let [out (fixt/last-to-webview-of-type bridge "config/updated")]
          (is (some? out))
          (is (= "claude" (get-in out [:data :model])))
          (is (= "chat-1" (get-in out [:data :chatId]))
              "live forward to the webview MUST include :chatId"))))))
