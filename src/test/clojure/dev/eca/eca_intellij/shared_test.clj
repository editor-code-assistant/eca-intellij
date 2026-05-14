(ns dev.eca.eca-intellij.shared-test
  "Unit tests for the camelCase walker and throttle helper. Every
   outbound webview message goes through `map->camel-cased-map`, so a
   regression here would silently mis-key every message the React app
   listens for."
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.shared :as shared]))

(deftest map->camel-cased-map-converts-top-level-keyword-keys
  (testing "csk returns keywords; cheshire later JSON-encodes those into
            string keys, so the Clojure-level invariant is :kebab-case →
            :camelCase keywords."
    (is (= {:chatId 1 :fooBar 2}
           (shared/map->camel-cased-map {:chat-id 1 :foo-bar 2})))))

(deftest map->camel-cased-map-walks-into-nested-maps
  (testing "Nested maps in values are also camel-cased"
    (is (= {:outerKey {:innerKey 1}}
           (shared/map->camel-cased-map {:outer-key {:inner-key 1}})))))

(deftest map->camel-cased-map-walks-into-vectors-of-maps
  (testing "Each map inside a vector gets its keys camelCased independently"
    (is (= {:items [{:itemId 1} {:itemId 2}]}
           (shared/map->camel-cased-map {:items [{:item-id 1} {:item-id 2}]})))))

(deftest map->camel-cased-map-preserves-non-keyword-keys
  (testing "Strings, numbers, and other non-keyword keys are untouched"
    (is (= {"already" 1 42 2}
           (shared/map->camel-cased-map {"already" 1 42 2})))))

(deftest map->camel-cased-map-handles-already-camel-keys
  (testing "Already-camel keyword keys round-trip without further mangling"
    (is (= {:chatId 1}
           (shared/map->camel-cased-map {:chatId 1})))))

(deftest map->camel-cased-map-handles-empty-input
  (is (= {} (shared/map->camel-cased-map {}))))

(deftest map->camel-cased-map-leaves-primitives-alone
  (testing "Non-map non-vector branches in postwalk return unchanged"
    (is (= "scalar" (shared/map->camel-cased-map "scalar")))
    (is (= 42 (shared/map->camel-cased-map 42)))
    (is (nil? (shared/map->camel-cased-map nil)))))

(deftest map->camel-cased-map-handles-realistic-logs-appended-payload
  (testing "`logs/appended` (regression target: 88fca15) — :session-id must
            land on the wire as sessionId or the React Logs tab cannot
            correlate entries with their chat."
    (let [appended {:type "logs/appended"
                    :data {:ts 1700000000000
                           :seq 42
                           :session-id "sess-abc"
                           :source :server
                           :level :info
                           :text "started"}}
          out (shared/map->camel-cased-map appended)]
      (is (= :sessionId (->> out :data keys (some #{:sessionId})))
          "sessionId key must appear on the data map as :sessionId")
      (is (= "sess-abc" (get-in out [:data :sessionId]))))))

(deftest throttle-runs-an-invocation
  (let [hits (atom [])
        throttled (shared/throttle (fn [v] (swap! hits conj v)) 50)]
    (throttled :only)
    ;; Give the go-loop a beat to consume the put before we observe.
    (Thread/sleep 30)
    (is (= [:only] @hits))))

(deftest throttle-rate-limits-bursts
  (testing "Pumping a burst within the throttle window must NOT fan
            out to N calls — `throttle` is the throttle behind the
            server-logs editor update, where unbounded invocation would
            wedge the EDT."
    (let [hits (atom 0)
          throttled (shared/throttle (fn [_] (swap! hits inc)) 200)]
      (dotimes [_ 10] (throttled :tick))
      (Thread/sleep 50)
      (let [count-at-50 @hits]
        ;; Inside the 200ms window we should see at most one hit land
        ;; (the go-loop reads exactly once then sleeps). Two is the
        ;; safe upper bound if the test JVM was paused mid-burst.
        (is (<= count-at-50 2)
            (str "throttle should bound burst hits within window; got "
                 count-at-50))))))
