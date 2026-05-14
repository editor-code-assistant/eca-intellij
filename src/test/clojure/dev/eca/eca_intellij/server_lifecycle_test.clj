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
