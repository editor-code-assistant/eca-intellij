(ns dev.eca.eca-intellij.test-fixtures
  "Shared scaffolding for the eca-intellij test suite.

   Two main pieces tests reach for:

     * `with-test-project` -- registers a synthetic `Project` in the real
       `db/db*` atom for the body's duration, then cleans up. Lets every
       db.clj reader/writer in production code run unmodified against a
       per-test scratch slot.

     * `with-stub-bridge` -- replaces the IO-touching seams in
       `webview.clj` (`send-msg!`, `api/connected-client`, `api/request!`,
       `api/notify!`, `app-manager/invoke-later!`, `read-action!`,
       `current-selected-editor`) with capture-and-stub fns. Tests then
       assert on captured messages with `sent-to-webview` /
       `sent-to-server` rather than having to wire up real JCEF or a live
       ECA process."
  (:require
   [cheshire.core :as json]
   [com.github.ericdallo.clj4intellij.app-manager :as app-manager]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.shared :as shared]
   [dev.eca.eca-intellij.webview :as webview])
  (:import
   [com.intellij.openapi.project Project]))

(defn test-project
  "Return a `Project` stand-in with just enough surface for the
   production code under test (`getName`, `getBasePath`). Methods we do
   not list throw `AbstractMethodError` if invoked, which surfaces
   test/prod drift loudly instead of silently producing nil."
  (^Project []
   (test-project {}))
  (^Project [{:keys [name base-path]
              :or {name "test-project"
                   base-path (str "/tmp/eca-test-" (System/nanoTime) "-" (rand-int 100000))}}]
   (reify Project
     (getName [_] name)
     (getBasePath [_] base-path))))

(defn empty-project-state
  "The initial per-project map that the production code expects to find
   in `db/db*`. Mirrors `db/empty-project` but is duplicated here so
   tests stay decoupled from db.clj's private vars."
  [project]
  {:project project
   :status :running
   :downloaded-server-path nil
   :client nil
   :server-process nil
   :session {:mcp-servers {}}
   :server-config {}
   :on-status-changed-fns {}
   :on-focus-changed-fns {}
   :on-settings-changed-fns {}
   :on-stderr-log-updated-fns {}
   :server-stderr-string ""
   :settings {}
   :log-entries []
   :log-next-seq 1
   :log-subscribers {}})

(defmacro with-test-project
  "Bind `project-sym` to a fresh test Project registered in `db/db*`.
   Optional :initial-db map is merged on top of the default empty state
   so individual tests can pre-seed status, settings, etc.

   Removes the project from `db/db*` on exit so siblings stay isolated."
  [bindings & body]
  (let [project-sym (first bindings)
        opts (apply hash-map (rest bindings))]
    `(let [~project-sym (test-project {})
           base-path# (.getBasePath ~project-sym)]
       (swap! db/db* assoc-in [:projects base-path#]
              (merge (empty-project-state ~project-sym)
                     ~(:initial-db opts)))
       (try
         ~@body
         (finally
           (swap! db/db* update :projects dissoc base-path#))))))

(defrecord BridgeStub [sent-to-webview
                      sent-to-server
                      request-replies])

(defn bridge-stub []
  (->BridgeStub (atom [])
                (atom [])
                (atom {})))

(defn sent-to-webview
  "Every message `send-msg!` would have shipped to the React app, in
   order. Each entry is the raw Clojure map captured before the
   camel-case + cheshire trip -- use `msg->json` when a test wants to
   assert on byte-for-byte JSON shape."
  [bridge]
  @(:sent-to-webview bridge))

(defn last-to-webview-of-type
  "Most recent outbound whose `:type` is `type`, or nil. Useful when an
   inbound triggers multiple unrelated emissions and you only care
   about one."
  [bridge type]
  (->> @(:sent-to-webview bridge) (filter #(= type (:type %))) last))

(defn webview-of-type
  "Every outbound whose `:type` is `type`, in order."
  [bridge type]
  (->> @(:sent-to-webview bridge) (filterv #(= type (:type %)))))

(defn sent-to-server
  "Vector of `[kind method body]` tuples for everything the production
   code shipped to the ECA server via api/request! or api/notify!.
   `kind` is `:request` or `:notify`."
  [bridge]
  @(:sent-to-server bridge))

(defn last-to-server-of
  "Most recent `[kind method body]` matching method `method`, or nil."
  [bridge method]
  (->> @(:sent-to-server bridge) (filter #(= method (nth % 1))) last))

(defn stub-reply!
  "Pre-stage the value `api/request!` should deliver when production
   code calls it with method `method`. Method matches the keyword
   passed to api/request! (e.g. `:chat/prompt`)."
  [bridge method value]
  (swap! (:request-replies bridge) assoc method value))

(defmacro with-stub-bridge
  "Run `body` with every IO seam in webview.clj redirected through a
   fresh bridge bound to `bridge-sym`:

     - `webview/send-msg!`              capture msg into bridge
     - `webview/current-selected-editor` nil (no editor active)
     - `api/connected-client`           ::stub-client sentinel
     - `api/request!`                   record + deliver pre-staged
                                        reply (defaults to {})
     - `api/notify!`                    record (no return)
     - `app-manager/invoke-later!`      run :invoke-fn synchronously
     - `app-manager/read-action!`       run :run-fn synchronously"
  [bridge-sym & body]
  `(let [~bridge-sym (bridge-stub)]
     (with-redefs
       [webview/send-msg! (fn [_project# msg#]
                            (swap! (:sent-to-webview ~bridge-sym) conj msg#))
        webview/current-selected-editor (constantly nil)
        api/connected-client (constantly ::stub-client)
        api/request! (fn [_client# args#]
                       (let [method# (first args#)
                             body# (second args#)]
                         (swap! (:sent-to-server ~bridge-sym)
                                conj [:request method# body#])
                         (let [replies# @(:request-replies ~bridge-sym)
                               v# (if (contains? replies# method#)
                                    (get replies# method#)
                                    {})]
                           (doto (promise) (deliver v#)))))
        api/notify! (fn [_client# args#]
                      (let [method# (first args#)
                            body# (second args#)]
                        (swap! (:sent-to-server ~bridge-sym)
                               conj [:notify method# body#]))
                      nil)
        app-manager/invoke-later! (fn [opts#] ((:invoke-fn opts#)))
        app-manager/read-action! (fn [opts#] ((:run-fn opts#)))]
       ~@body)))

(defn msg->json
  "Serialize a captured outbound message through the same path
   send-msg! takes in production (kebab->camel keys, then cheshire).
   Useful when a test wants to assert on the literal JSON shape."
  [msg]
  (json/generate-string (shared/map->camel-cased-map msg)))

(defn to-json-payload
  "Webview -> host messages arrive as JSON strings. Build one from a
   Clojure map so tests can drive `webview/handle` with realistic input."
  [m]
  (json/generate-string m))
