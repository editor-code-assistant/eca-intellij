(ns dev.eca.eca-intellij.api
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string]
   [dev.eca.eca-intellij.db :as db]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [lsp4clj.coercer :as coercer]
   [lsp4clj.io-chan :as io-chan]
   [lsp4clj.lsp.requests :as lsp.requests]
   [lsp4clj.lsp.responses :as lsp.responses]
   [lsp4clj.protocols.endpoint :as protocols.endpoint])
  (:import
   [com.intellij.openapi.project Project]))

(set! *warn-on-reflection* true)

(defmulti config-updated (constantly :default))
(defmulti chat-content-received (constantly :default))
(defmulti chat-cleared (constantly :default))
(defmulti tool-server-updated (constantly :default))

(defn ^:private receive-message
  [client context message]
  (let [message-type (coercer/input-message-type message)]
    (try
      (let [response
            (case message-type
              (:parse-error :invalid-request)
              (protocols.endpoint/log client :error "Error reading message" message)
              :request
              (protocols.endpoint/receive-request client context message)
              (:response.result :response.error)
              (protocols.endpoint/receive-response client message)
              :notification
              (protocols.endpoint/receive-notification client context message))]
        ;; Ensure client only responds to requests
        (when (identical? :request message-type)
          response))
      (catch Throwable e
        (protocols.endpoint/log client :error "Error receiving:" e)
        (throw e)))))

(defrecord Client [client-id
                   input-ch
                   output-ch
                   join
                   request-id
                   sent-requests
                   trace-level]
  protocols.endpoint/IEndpoint
  (start [this context]
    (protocols.endpoint/log this :verbose "lifecycle:" "starting")
    (let [pipeline (async/pipeline-blocking
                    1 ;; no parallelism preserves server message order
                    output-ch
                     ;; `keep` means we do not reply to responses and notifications
                    (keep #(receive-message this context %))
                    input-ch)]
      (async/thread
        ;; wait for pipeline to close, indicating input closed
        (async/<!! pipeline)
        (deliver join :done)))
    ;; invokers can deref the return of `start` to stay alive until server is
    ;; shut down
    join)
  (shutdown [this]
    (protocols.endpoint/log this :verbose "lifecycle:" "shutting down")
    ;; closing input will drain pipeline, then close output, then close
    ;; pipeline
    (async/close! input-ch)
    (if (= :done (deref join 10e3 :timeout))
      (protocols.endpoint/log this :verbose "lifecycle:" "shutdown")
      (protocols.endpoint/log this :verbose "lifecycle:" "shutdown timed out")))
  (log [this msg params]
    (protocols.endpoint/log this :verbose msg params))
  (log [_this level msg params]
    (when (or (identical? trace-level level)
              (identical? trace-level :verbose))
      ;; TODO apply color
      (logger/info (string/join " " [msg params]))))
  (send-request [this method body]
    (let [req (lsp.requests/request (swap! request-id inc) method body)
          p (promise)
          start-ns (System/nanoTime)]
      (protocols.endpoint/log this :messages "sending request:" req)
      ;; Important: record request before sending it, so it is sure to be
      ;; available during receive-response.
      (swap! sent-requests assoc (:id req) {:request p
                                            :start-ns start-ns})
      (async/>!! output-ch req)
      p))
  (send-notification [this method body]
    (let [notif (lsp.requests/notification method body)]
      (protocols.endpoint/log this :messages "sending notification:" notif)
      (async/>!! output-ch notif)))
  (receive-response [this {:keys [id] :as resp}]
    (if-let [{:keys [request start-ns]} (get @sent-requests id)]
      (let [ms (float (/ (- (System/nanoTime) start-ns) 1000000))]
        (protocols.endpoint/log this :messages (format "received response (%.0fms):" ms) resp)
        (swap! sent-requests dissoc id)
        (deliver request (if (:error resp)
                           resp
                           (:result resp))))
      (protocols.endpoint/log this :error "received response for unmatched request:" resp)))
  (receive-request [this _context {:keys [id method _params] :as req}]
    (protocols.endpoint/log this :messages "received request:" req)
    (when-let [response-body (case method
                               (logger/warn "Unknown LSP request method" method))]
      (let [resp (lsp.responses/response id response-body)]
        (protocols.endpoint/log this :messages "sending response:" resp)
        resp)))
  (receive-notification [this context {:keys [method params] :as notif}]
    (protocols.endpoint/log this :messages "received notification:" notif)
    (case method
      "config/updated" (config-updated context params)
      "chat/contentReceived" (chat-content-received context params)
      "chat/cleared" (chat-cleared context params)
      "tool/serverUpdated" (tool-server-updated context params)

      (logger/warn "Unknown LSP notification method" method))))

(defn client [in out trace-level]
  (map->Client
   {:client-id 1
    :input-ch (io-chan/input-stream->input-chan out)
    :output-ch (io-chan/output-stream->output-chan in)
    :join (promise)
    :sent-requests (atom {})
    :request-id (atom 0)
    :trace-level trace-level}))

(defn start-client! [client context]
  (protocols.endpoint/start client context))

(defn request! [client [method body]]
  (protocols.endpoint/send-request client (subs (str method) 1) body))

(defn notify! [client [method body]]
  (protocols.endpoint/send-notification client (subs (str method) 1) body))

(defn connected-client [^Project project]
  (when (identical? :running (db/get-in project [:status]))
    (db/get-in project [:client])))
