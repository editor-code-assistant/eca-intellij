(ns dev.eca.eca-intellij.webview-editor-logs-test
  "Integration tests for the editor/*, logs/* and chat/askQuestion
   branches that DO NOT touch IntelliJ static services inline.

   The branches that DO (`editor/openFile`, `editor/openGlobalConfig`,
   `editor/openUrl`, `editor/refresh`, `editor/openServerLogs`,
   `editor/saveFile`) live behind a `LocalFileSystem/getInstance` or
   `FileEditorManager/getInstance` call that cannot be exercised
   outside an IDE sandbox; those are covered by the L3 Kotlin
   BasePlatformTestCase suite."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.editor-actions :as editor-actions]
   [dev.eca.eca-intellij.log-store :as log-store]
   [dev.eca.eca-intellij.test-fixtures :as fixt]
   [dev.eca.eca-intellij.webview :as webview])
  (:import
   [com.intellij.openapi.editor Editor]
   [java.io File]
   [java.util Base64]))

;; ── editor/readGlobalConfig ───────────────────────────────────────

(deftest read-global-config-replies-with-content-path-and-request-id
  (testing "Regression 88fca15: the Settings → Global Config tab spun
            forever before this handler existed. The reply MUST carry
            both the absolute path (so the tab can show the file
            location) and a `request-id` (which becomes `requestId`
            after camelCase serialization)."
    (let [tmp (File/createTempFile "eca-rg-" ".json")]
      (try
        (spit tmp "{\"a\": 1}")
        (fixt/with-test-project [project]
          (fixt/with-stub-bridge bridge
            (with-redefs [editor-actions/get-global-config-path (fn [] tmp)]
              (webview/handle
                (fixt/to-json-payload {:type "editor/readGlobalConfig"
                                       :data {:requestId "rg-1"}})
                project))
            (let [reply (fixt/last-to-webview-of-type
                         bridge "editor/readGlobalConfig")
                  json (fixt/msg->json reply)
                  parsed (json/parse-string json keyword)]
              (is (= "rg-1" (get-in reply [:data :request-id])))
              (is (= "{\"a\": 1}" (get-in reply [:data :contents])))
              (is (= (.getAbsolutePath tmp) (get-in reply [:data :path])))
              (is (true? (get-in reply [:data :exists])))
              (is (= "rg-1" (get-in parsed [:data :requestId]))
                  "the camelCase walker MUST translate :request-id → requestId
                   so the React reply handler can correlate"))))
        (finally (.delete tmp))))))

(deftest read-global-config-when-file-missing-replies-with-exists-false
  (let [tmp (File/createTempFile "eca-rg-missing-" ".json")]
    (.delete tmp)
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (with-redefs [editor-actions/get-global-config-path (fn [] tmp)]
          (webview/handle
            (fixt/to-json-payload {:type "editor/readGlobalConfig"
                                   :data {:requestId "rg-2"}})
            project))
        (let [reply (fixt/last-to-webview-of-type
                     bridge "editor/readGlobalConfig")]
          (is (= "rg-2" (get-in reply [:data :request-id])))
          (is (= "" (get-in reply [:data :contents])))
          (is (false? (get-in reply [:data :exists]))))))))

;; ── editor/writeGlobalConfig ──────────────────────────────────────

(deftest write-global-config-success-echoes-request-id
  (let [tmp (File/createTempFile "eca-wg-" ".json")]
    (.delete tmp)
    (try
      (fixt/with-test-project [project]
        (fixt/with-stub-bridge bridge
          (with-redefs [editor-actions/get-global-config-path (fn [] tmp)]
            (webview/handle
              (fixt/to-json-payload
                {:type "editor/writeGlobalConfig"
                 :data {:contents "{\"a\": 1}"
                        :requestId "wg-1"}})
              project))
          (let [reply (fixt/last-to-webview-of-type
                       bridge "editor/writeGlobalConfig")]
            (is (= "wg-1" (get-in reply [:data :request-id])))
            (is (true? (get-in reply [:data :ok])))
            (is (= "{\"a\": 1}" (slurp tmp))))))
      (finally (when (.exists tmp) (.delete tmp))))))

(deftest write-global-config-rejects-payload-over-1mb
  (let [tmp (File/createTempFile "eca-wg-big-" ".json")]
    (.delete tmp)
    (try
      (fixt/with-test-project [project]
        (fixt/with-stub-bridge bridge
          (with-redefs [editor-actions/get-global-config-path (fn [] tmp)]
            (webview/handle
              (fixt/to-json-payload
                {:type "editor/writeGlobalConfig"
                 :data {:contents (str "\"" (apply str (repeat (* 1024 1024) "a")) "\"")
                        :requestId "wg-too-big"}})
              project))
          (let [reply (fixt/last-to-webview-of-type
                       bridge "editor/writeGlobalConfig")]
            (is (= "wg-too-big" (get-in reply [:data :request-id])))
            (is (false? (get-in reply [:data :ok])))
            (is (re-find #"too large" (get-in reply [:data :error])))
            (is (not (.exists tmp)) "file MUST stay un-written on rejection"))))
      (finally (when (.exists tmp) (.delete tmp))))))

(deftest write-global-config-rejects-invalid-jsonc
  (let [tmp (File/createTempFile "eca-wg-bad-" ".json")]
    (.delete tmp)
    (try
      (fixt/with-test-project [project]
        (fixt/with-stub-bridge bridge
          (with-redefs [editor-actions/get-global-config-path (fn [] tmp)]
            (webview/handle
              (fixt/to-json-payload
                {:type "editor/writeGlobalConfig"
                 :data {:contents "{ this is not json"
                        :requestId "wg-bad"}})
              project))
          (let [reply (fixt/last-to-webview-of-type
                       bridge "editor/writeGlobalConfig")]
            (is (false? (get-in reply [:data :ok])))
            (is (re-find #"Invalid JSONC" (get-in reply [:data :error]))))))
      (finally (when (.exists tmp) (.delete tmp))))))

;; ── editor/saveClipboardImage ─────────────────────────────────────

(deftest save-clipboard-image-writes-to-tmp-and-echoes-request-id
  (testing "Image roundtrip: 1x1 transparent PNG (smallest valid PNG)."
    (let [;; tiny base64 sample doesn't have to be a real image; the
          ;; handler doesn't decode/validate beyond base64.
          png-bytes (byte-array [0x89 0x50 0x4E 0x47])
          b64 (.encodeToString (Base64/getEncoder) png-bytes)]
      (fixt/with-test-project [project]
        (fixt/with-stub-bridge bridge
          (webview/handle
            (fixt/to-json-payload {:type "editor/saveClipboardImage"
                                   :data {:base64Data b64
                                          :mimeType "image/png"
                                          :requestId "img-1"}})
            project)
          (let [reply (fixt/last-to-webview-of-type
                       bridge "editor/saveClipboardImage")
                path (get-in reply [:data :requestId])
                target (get-in reply [:data :path])]
            (is (= "img-1" path))
            (is (.endsWith ^String target ".png"))
            (is (.exists (io/file target))
                "tmp file MUST exist after the handler returns")
            (.delete (io/file target))))))))

(deftest save-clipboard-image-defaults-to-png-on-unknown-mime
  (let [b64 (.encodeToString (Base64/getEncoder) (.getBytes "x"))]
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle
          (fixt/to-json-payload {:type "editor/saveClipboardImage"
                                 :data {:base64Data b64
                                        :mimeType "image/totally-bogus"
                                        :requestId "img-bogus"}})
          project)
        (let [reply (fixt/last-to-webview-of-type
                     bridge "editor/saveClipboardImage")
              target (get-in reply [:data :path])]
          (is (.endsWith ^String target ".png")
              "unknown MIME falls back to .png so the React side can still inline")
          (.delete (io/file target)))))))

(deftest save-clipboard-image-bad-base64-does-not-throw
  (testing "Handler wraps the decode in try/catch — malformed base64
            must not crash the host or take the webview down."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (is (nil?
             (webview/handle
              (fixt/to-json-payload {:type "editor/saveClipboardImage"
                                     :data {:base64Data "not!valid!base64!"
                                            :mimeType "image/png"
                                            :requestId "img-bad"}})
              project)))))))

;; ── on-focus-changed defensive nil-handling ───────────────────────
;; Regression bb315a4: NPE when an editor without a project or without
;; a virtual file was focused.

(deftest on-focus-changed-is-noop-when-editor-has-no-project
  (testing "Regression bb315a4 — focusing the floating diff editor or
            scratch buffer with no Project must NOT throw."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (let [editor-without-project (reify Editor
                                       (getProject [_] nil))]
          (is (nil? ((deref #'webview/on-focus-changed)
                     editor-without-project nil)))
          (is (empty? (fixt/webview-of-type bridge "editor/focusChanged"))
              "no editor/focusChanged frame may go out for a project-less editor"))))))

;; ── logs/snapshot, logs/clear, logs/appended ─────────────────────

(deftest logs-snapshot-replies-with-current-buffer
  (fixt/with-test-project [project]
    (log-store/append! project {:source :server :text "first"})
    (log-store/append! project {:source :server :text "second"})
    (fixt/with-stub-bridge bridge
      (webview/handle (fixt/to-json-payload {:type "logs/snapshot"}) project)
      (let [reply (fixt/last-to-webview-of-type bridge "logs/snapshot")]
        (is (= 2 (count (:data reply))))
        (is (= ["first" "second"] (mapv :text (:data reply))))))))

(deftest logs-clear-empties-buffer-without-replying
  (fixt/with-test-project [project]
    (log-store/append! project {:source :server :text "x"})
    (fixt/with-stub-bridge bridge
      (webview/handle (fixt/to-json-payload {:type "logs/clear"}) project)
      (is (empty? (log-store/snapshot project))
          ":log-entries MUST be empty after a logs/clear"))))

(deftest webview-ready-then-log-append-forwards-as-logs-appended
  (testing "After webview/ready hooks the :webview subscriber,
            log_store/append! MUST push a `logs/appended` envelope to
            the React side carrying the entry payload."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (log-store/append! project {:source :server :text "live tail"})
        (let [reply (fixt/last-to-webview-of-type bridge "logs/appended")]
          (is (some? reply))
          (is (= "live tail" (get-in reply [:data :text]))))))))

(deftest logs-appended-keys-camel-case-on-the-wire
  (testing "Regression target: :session-id on a LogEntry MUST land as
            sessionId after the camelCase walker so React Logs panel
            can correlate; assert against the JSON-on-wire form."
    (fixt/with-test-project [project]
      (fixt/with-stub-bridge bridge
        (webview/handle (fixt/to-json-payload {:type "webview/ready"}) project)
        (log-store/append! project {:source :server
                                    :session-id "sess-A"
                                    :text "x"})
        (let [reply (fixt/last-to-webview-of-type bridge "logs/appended")
              wire (fixt/msg->json reply)]
          (is (re-find #"\"sessionId\"\s*:\s*\"sess-A\"" wire)))))))
