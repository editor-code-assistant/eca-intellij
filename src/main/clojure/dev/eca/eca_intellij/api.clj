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
   [com.intellij.codeInsight.daemon.impl DaemonCodeAnalyzerEx HighlightInfo]
   [com.intellij.lang.annotation HighlightSeverity]
   [com.intellij.openapi.application ApplicationManager]
   [com.intellij.openapi.editor Document]
   [com.intellij.openapi.fileEditor FileDocumentManager FileEditorManager]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.util Computable]
   [com.intellij.openapi.vfs LocalFileSystem VirtualFile]
   [com.intellij.util Processor]
   [java.io File]
   [java.net URI]))

(set! *warn-on-reflection* true)

(defmulti config-updated (constantly :default))
(defmulti chat-content-received (constantly :default))
(defmulti chat-cleared (constantly :default))
(defmulti tool-server-updated (constantly :default))

(defn ^:private severity->string [^HighlightSeverity severity]
  (condp = severity
    HighlightSeverity/ERROR "error"
    HighlightSeverity/WARNING "warning"
    HighlightSeverity/WEAK_WARNING "warning"
    HighlightSeverity/INFORMATION "hint"
    "info"))

(defn ^:private highlight->diagnostic [^HighlightInfo info ^String uri ^Document document]
  (let [start-offset (.getStartOffset info)
        end-offset (.getEndOffset info)
        start-line (.getLineNumber document start-offset)
        start-char (- start-offset (.getLineStartOffset document start-line))
        end-line (.getLineNumber document end-offset)
        end-char (- end-offset (.getLineStartOffset document end-line))]
    {:uri uri
     :severity (severity->string (.getSeverity info))
     :code nil
     :range {:start {:line start-line :character start-char}
             :end {:line end-line :character end-char}}
     :source nil
     :message (or (.getDescription info) "")}))

(defn ^:private get-diagnostics-for-document [^Project project ^Document document ^String uri]
  (let [diagnostics (java.util.ArrayList.)]
    (DaemonCodeAnalyzerEx/processHighlights
     document project nil 0 (.getTextLength document)
     (reify Processor
       (process [_ info]
         (let [^HighlightInfo hi info]
           (when (and (>= (.compareTo (.getSeverity hi) HighlightSeverity/WARNING) 0)
                      (.getDescription hi))
             (.add diagnostics (highlight->diagnostic hi uri document))))
         true)))
    (vec diagnostics)))

(defn ^:private get-editor-diagnostics [^Project project params]
  (try
    (.runReadAction (ApplicationManager/getApplication)
      (reify Computable
        (compute [_]
          (if-let [uri (:uri params)]
            (let [path (.getPath (URI. uri))
                  vfile (.findFileByPath (LocalFileSystem/getInstance) path)
                  document (when vfile
                             (.getDocument (FileDocumentManager/getInstance) vfile))]
              (if document
                {:diagnostics (get-diagnostics-for-document project document uri)}
                {:diagnostics []}))
            (let [open-files (.getOpenFiles (FileEditorManager/getInstance project))
                  all-diags (into []
                              (mapcat (fn [^VirtualFile vfile]
                                        (when-let [document (.getDocument (FileDocumentManager/getInstance) vfile)]
                                          (get-diagnostics-for-document
                                           project document
                                           (str (.toURI (File. (.getPath vfile))))))))
                              open-files)]
              {:diagnostics all-diags})))))
    (catch Exception e
      (logger/warn "Error getting editor diagnostics:" (.getMessage e))
      {:diagnostics []})))

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
  (receive-request [this context {:keys [id method] :as req}]
    (protocols.endpoint/log this :messages "received request:" req)
    (when-let [response-body (case method
                               "editor/getDiagnostics" (get-editor-diagnostics (:project context) (:params req))
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
