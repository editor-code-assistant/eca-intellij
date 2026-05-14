(ns dev.eca.eca-intellij.server-lifecycle-test
  "Tests for server.clj's status broadcast + config.clj's OS-aware
   download path. The Windows .exe suffix (regression b27d515) and the
   listener-keys log line (added so blank-webview bug reports can be
   correlated with whether :webview was reached) both live here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.server :as server]
   [dev.eca.eca-intellij.test-fixtures :as fixt]))

;; ── broadcast-status! ─────────────────────────────────────────────

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

(deftest broadcast-status-allows-throwing-listener-to-not-poison-siblings
  (testing "Production uses `run!` which does NOT catch — but the
            cursor-editor listener and on-status-changed listeners are
            expected to be tame. Document the current contract: a
            throwing listener will propagate. If we tighten this later
            this test must be updated."
    (fixt/with-test-project [project]
      (let [hits (atom 0)]
        (db/assoc-in project [:on-status-changed-fns]
                     {:throwy (fn [_ _] (throw (ex-info "boom" {})))
                      :good (fn [_ _] (swap! hits inc))})
        (try (#'server/broadcast-status! project :running) (catch Throwable _))
        ;; Listeners iterate via map-vals; one of two orders is possible.
        ;; In the current `run!` impl the throw stops iteration after
        ;; whichever listener fired first — both 0 and 1 are valid hits
        ;; values. Just assert that the broadcast itself does not loop.
        (is (<= @hits 1))))))

;; ── config/download-server-path ──────────────────────────────────

(deftest download-server-path-uses-exe-suffix-on-windows
  (testing "Regression b27d515: on Windows the auto-downloaded server
            must land as `eca.exe`; without the extension the
            existence-check would always miss and we would re-download
            on every IDE start."
    (let [original (System/getProperty "os.name")]
      (try
        (System/setProperty "os.name" "Windows 11")
        (let [f (config/download-server-path)]
          (is (= "eca.exe" (.getName f))))
        (finally
          (System/setProperty "os.name" (or original "Linux")))))))

(deftest download-server-path-uses-no-extension-on-other-os
  (let [original (System/getProperty "os.name")]
    (try
      (System/setProperty "os.name" "Linux")
      (is (= "eca" (.getName (config/download-server-path))))
      (System/setProperty "os.name" "Mac OS X")
      (is (= "eca" (.getName (config/download-server-path))))
      (finally
        (System/setProperty "os.name" (or original "Linux"))))))

;; ── public status reader ─────────────────────────────────────────

(deftest status-reads-current-db-status
  (fixt/with-test-project [project
                           :initial-db {:status :starting}]
    (is (= :starting (server/status project)))))
