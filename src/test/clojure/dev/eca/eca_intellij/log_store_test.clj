(ns dev.eca.eca-intellij.log-store-test
  "Unit tests for the in-memory log ring buffer + subscriber fan-out
   that backs the Settings → Logs webview tab. The cap (5000 entries),
   subscriber replacement on the same key, and subscriber exception
   isolation are all behaviors the webview relies on; a regression
   here would silently lose log entries or stall live tail forever."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.log-store :as log-store]
   [dev.eca.eca-intellij.test-fixtures :as fixt]))

(deftest infer-level-flags-error-keywords
  (is (= :error (log-store/infer-level "Something ERROR happened")))
  (is (= :error (log-store/infer-level "FATAL: oom")))
  (is (= :error (log-store/infer-level "java.lang.RuntimeException: boom")))
  (is (= :error (log-store/infer-level "Traceback (most recent call last):"))))

(deftest infer-level-flags-non-zero-exit
  (is (= :error (log-store/infer-level "process exited with code 1")))
  (is (= :info (log-store/infer-level "process exited with code 0")))
  (testing "leading 0 in a longer code is still non-zero (e.g. 02 == 2)"
    (is (= :error (log-store/infer-level "exited with code 02")))))

(deftest infer-level-flags-failed-to
  (is (= :error (log-store/infer-level "Failed to start server")))
  (is (= :error (log-store/infer-level "failed to download artifact")))
  (testing "case-insensitive"
    (is (= :error (log-store/infer-level "FaIlEd To open file")))))

(deftest infer-level-defaults-to-info
  (is (= :info (log-store/infer-level "starting up")))
  (is (= :info (log-store/infer-level ""))))

(deftest append-stores-entry-in-ring-buffer
  (fixt/with-test-project [project]
    (log-store/append! project {:source :server :text "hello"})
    (let [snap (log-store/snapshot project)]
      (is (= 1 (count snap)))
      (is (= "hello" (-> snap first :text)))
      (is (= :server (-> snap first :source)))
      (is (= 1 (-> snap first :seq))))))

(deftest append-assigns-monotonic-seq-ids
  (fixt/with-test-project [project]
    (log-store/append! project {:source :server :text "a"})
    (log-store/append! project {:source :server :text "b"})
    (log-store/append! project {:source :server :text "c"})
    (is (= [1 2 3] (mapv :seq (log-store/snapshot project))))))

(deftest append-defaults-level-via-infer
  (fixt/with-test-project [project]
    (log-store/append! project {:source :server :text "ERROR ohno"})
    (log-store/append! project {:source :server :text "all good"})
    (is (= [:error :info] (mapv :level (log-store/snapshot project))))))

(deftest append-honors-explicit-level
  (fixt/with-test-project [project]
    (log-store/append! project {:source :server :text "ERROR ohno"
                                :level :info})
    (is (= :info (-> (log-store/snapshot project) first :level)))))

(deftest ring-buffer-caps-at-5000-entries-oldest-first
  (fixt/with-test-project [project]
    (dotimes [i 5050]
      (log-store/append! project {:source :server :text (str "entry-" i)}))
    (let [snap (log-store/snapshot project)]
      (is (= 5000 (count snap)) "buffer size must stay at the 5000-entry cap")
      (is (= "entry-50" (:text (first snap))) "oldest preserved is entry-50 (first 50 evicted)")
      (is (= "entry-5049" (:text (last snap))) "newest preserved is the most recent push"))))

(deftest subscriber-receives-each-new-entry
  (fixt/with-test-project [project]
    (let [received (atom [])]
      (log-store/subscribe! project :test #(swap! received conj %))
      (log-store/append! project {:source :server :text "one"})
      (log-store/append! project {:source :server :text "two"})
      (is (= ["one" "two"] (mapv :text @received))))))

(deftest subscribe-replaces-prior-listener-under-same-key
  (testing "On webview/ready re-delivery (tool-window re-open) the
            handler re-runs subscribe! with key :webview — we MUST NOT
            end up with two listeners both firing on every entry."
    (fixt/with-test-project [project]
      (let [hits-a (atom 0)
            hits-b (atom 0)]
        (log-store/subscribe! project :webview (fn [_] (swap! hits-a inc)))
        (log-store/subscribe! project :webview (fn [_] (swap! hits-b inc)))
        (log-store/append! project {:source :server :text "x"})
        (is (= 0 @hits-a) "first listener replaced; must not fire")
        (is (= 1 @hits-b) "second listener is the live one")))))

(deftest subscriber-exception-does-not-poison-siblings
  (testing "If one subscriber throws, the entry must still be buffered
            and every other subscriber must still receive it."
    (fixt/with-test-project [project]
      (let [sibling-hits (atom 0)]
        (log-store/subscribe! project :throwy
                              (fn [_] (throw (ex-info "boom" {}))))
        (log-store/subscribe! project :sibling
                              (fn [_] (swap! sibling-hits inc)))
        (log-store/append! project {:source :server :text "still landed"})
        (is (= 1 @sibling-hits)
            "sibling subscriber MUST fire even when sibling-key throws")
        (is (= 1 (count (log-store/snapshot project)))
            "entry MUST land in buffer even when a subscriber throws")))))

(deftest unsubscribe-stops-callbacks
  (fixt/with-test-project [project]
    (let [hits (atom 0)]
      (log-store/subscribe! project :x (fn [_entry] (swap! hits inc)))
      (log-store/append! project {:source :server :text "a"})
      (log-store/unsubscribe! project :x)
      (log-store/append! project {:source :server :text "b"})
      (is (= 1 @hits)))))

(deftest clear-wipes-ring-but-not-stderr-string
  (testing "`logs/clear` from the webview drops the in-memory buffer but
            leaves :server-stderr-string alone (the editor-buffer
            surface backs bug reports and must persist)."
    (fixt/with-test-project [project
                             :initial-db {:server-stderr-string "preserved\n"}]
      (log-store/append! project {:source :server :text "a"})
      (log-store/append! project {:source :server :text "b"})
      (log-store/clear! project)
      (is (= [] (log-store/snapshot project)))
      (is (= "preserved\n" (db/get-in project [:server-stderr-string]))))))
