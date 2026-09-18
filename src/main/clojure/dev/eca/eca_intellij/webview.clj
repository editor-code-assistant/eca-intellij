(ns dev.eca.eca-intellij.webview
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.github.ericdallo.clj4intellij.app-manager :as app-manager]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.editor :as editor]
   [dev.eca.eca-intellij.editor-actions :as editor-actions]
   [dev.eca.eca-intellij.extension.server-logs :as server-logs]
   [dev.eca.eca-intellij.inline-chat :as inline-chat]
   [dev.eca.eca-intellij.log-store :as log-store]
   [dev.eca.eca-intellij.shared :as shared])
  (:import
   [com.github.ericdallo.clj4intellij ClojureClassLoader]
   [com.intellij.ide BrowserUtil]
   [com.intellij.openapi.editor Editor]
   [com.intellij.openapi.editor.colors EditorColorsManager]
   [com.intellij.openapi.fileEditor FileEditorManager]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.ui Messages]
   [com.intellij.openapi.util.io FileUtil]
   [com.intellij.openapi.fileChooser FileChooserFactory FileSaverDescriptor]
   [com.intellij.openapi.vfs LocalFileSystem VirtualFileWrapper]
   [com.intellij.openapi.wm ToolWindowManager]
   [com.intellij.ui ColorUtil JBColor]
   [com.intellij.ui.jcef JBCefBrowser]
   [com.intellij.util.ui JBUI$CurrentTheme$ToolWindow]
   [java.awt Color]
   [java.util Base64]
   [java.util.concurrent ExecutorService Executors ThreadFactory]))

(set! *warn-on-reflection* true)

(def ^:private pending-questions (atom {}))

(defn ^:private async!
  "Runs F on a worker thread, logging failures. Every wait on the ECA
   server or on the user inside `handle` goes through here, so the
   thread handling webview messages is never blocked (tests run it
   inline)."
  [f]
  (future
    (try
      (f)
      (catch Throwable e
        (logger/error "Error handling webview message:" e)))))

(defn ^:private hex [jb-color]
  (str "#" (ColorUtil/toHex jb-color)))

(defn ^:private rgba ^String [^java.awt.Color c alpha]
  (format "rgba(%d, %d, %d, %.2f)"
          (.getRed c) (.getGreen c) (.getBlue c) (double alpha)))

;; `JBColor/GREEN` resolves to `java.awt.Color.green` = `#00FF00` (CRT-pure
;; saturated green) on every Light LAF. Used as a foreground (MCP "running"
;; status, MCP-resource context icon) or as a background (approve action
;; button) against the near-white chat surface, that is the "heavy / glaring"
;; effect the user pushed back on. The Dark-LAF value `#629655` baked into
;; `JBColor/GREEN` is already nicely muted, so we keep it and only soften the
;; Light side to a forest/sea-green that still reads clearly as "success".
(def ^:private success-green
  (JBColor. (Color. 0x3C9F40) (Color. 0x629655)))

(defn ^:private theme-css-map []
  (let [global-scheme (.getGlobalScheme (EditorColorsManager/getInstance))]
    ;; Most come from https://github.com/JetBrains/intellij-community/blob/master/platform/platform-resources/src/themes/metadata/IntelliJPlatform.themeMetadata.json
    {"editor-bg" (hex (.getDefaultBackground global-scheme))
     "editor-fg" (hex (JBColor/namedColor "Editor.foreground"))
     "panel-bg" (hex (JBColor/namedColor "Editor.background"))
     "panel-border" (hex (JBUI$CurrentTheme$ToolWindow/borderColor))
     "input-bg" (hex (JBColor/namedColor "OptionPane.background"))
     "input-fg" (hex (JBColor/namedColor "TextField.caretForeground"))
     "input-placeholder-fg" (hex (JBColor/namedColor "Editor.foreground"))
     "base-border" (if (= (hex (.getDefaultBackground global-scheme)) (hex (JBColor/namedColor "Borders.ContrastBorderColor")))
                     (hex (JBColor/namedColor "Editor.background"))
                     (hex (JBColor/namedColor "Borders.ContrastBorderColor")))
     "base-hover" (hex (JBColor/namedColor "Borders.ContrastBorderColor"))

     "item-selectable-fg" (hex (JBColor/namedColor "Editor.foreground"))
     "link-fg" (hex (JBColor/namedColor "Hyperlink.linkColor"))

     "button-primary-fg" (hex (JBColor/namedColor "Button.default.foreground"))
     "button-primary-bg" (hex (JBColor/namedColor "Button.default.startBackground"))
     "button-primary-border" (hex (JBColor/namedColor "Button.default.borderColor"))
     "button-primary-hover-bg" (hex (JBColor/namedColor "Button.default.focusColor"))
     "button-primary-active-bg" (hex (JBColor/namedColor "Button.default.focusColor"))

     "button-secondary-fg" (hex (JBColor/namedColor "Button.foreground"))
     "button-secondary-bg" (hex (JBColor/namedColor "Button.background"))
     "button-secondary-border" (hex (JBColor/namedColor "Button.default.borderColor"))
     "button-secondary-hover-bg" (hex (JBColor/namedColor "Button.default.focusColor"))
     "button-secondary-active-bg" (hex (JBColor/namedColor "Button.default.startBackground"))

     "tooltip-bg" (hex (JBColor/namedColor "ToolTip.background"))
     "tooltip-fg" (hex (JBColor/namedColor "ToolTip.foreground"))

     "accent-fg" (hex (JBColor/namedColor "Hyperlink.linkColor"))

     "success-fg" (hex success-green)
     "warning-fg" (hex (JBColor/namedColor "Component.warningFocusColor"))
     "approval-fg" (hex JBColor/ORANGE)
     "error-fg" (hex (JBColor/namedColor "Component.errorFocusColor"))
     "warning-message-fg" (hex (JBColor/namedColor "Component.warningFocusColor"))

     "confirm-action-bg" (hex success-green)
     "confirm-action-fg" (hex (JBColor/namedColor "Button.default.foreground"))

     ;; Diff highlights need translucent fills so the underlying code stays readable.
     "diff-unchanged-bg" (rgba JBColor/GRAY 0.16)
     "diff-insert-bg" (rgba JBColor/GREEN 0.20)
     "diff-delete-bg" (rgba JBColor/RED 0.20)

     "toggle-slider-bg" (hex (JBColor/namedColor "Button.background"))
     "toggle-icon-bg" (hex (JBColor/namedColor "Button.foreground"))
     "toggle-bg" (hex (JBColor/namedColor "Button.default.startBackground"))

     "context-file-fg" (hex JBColor/ORANGE)
     "context-directory-fg" (hex JBColor/YELLOW)
     "context-web-fg" (hex JBColor/CYAN)
     "context-repo-map-fg" (hex JBColor/MAGENTA)
     "context-cursor-fg" (hex (JBColor/namedColor "Editor.foreground"))
     "context-mcp-resource-fg" (hex success-green)}))

(defn theme-css ^String []
  (str ":root {\n"
       (reduce
        (fn [s [name value]]
          (str s (format "--intellij-%s: %s;\n" name value)))
        ""
        (theme-css-map))
       "}"))

(defn ^:private send-msg! [^Project project msg]
  (when-let [browser ^JBCefBrowser (db/get-in project [:webview-browser])]
    (let [cef-browser (.getCefBrowser browser)]
      (.executeJavaScript (.getCefBrowser browser)
                          (format "window.postMessage(%s, \"*\");"
                                  (json/generate-string (shared/map->camel-cased-map msg)))
                          (.getURL cef-browser)
                          0))))

(defn ^:private request-then!
  "Sends REQ to the server right away, keeping the order webview messages
   arrived in, and calls ON-RESULT with the response on a worker thread."
  [client req on-result]
  (let [response (api/request! client req)]
    (async! #(on-result @response))))

(defn ^:private reply!
  "Answers a webview request of TYPE with RESULT, echoing the requestId the
   webview correlates its pending promise with."
  [^Project project type request-id result]
  (send-msg! project {:type type
                      :data (merge {:requestId request-id} result)}))

(defn ^:private rpc-error-reply!
  "`reply!` with the `{:error {:code :message}}` envelope the webview
   thunks reject on, normalized from an `:error` keyed server response."
  [^Project project type request-id message]
  (reply! project type request-id {:error {:code "rpc_error"
                                           :message (or message "RPC error")}}))

(defn select-chat!
  "Activate the ECA tool window and select CHAT-ID in the webview."
  [^Project project chat-id]
  (app-manager/invoke-later!
   {:invoke-fn (fn []
                 (some-> (ToolWindowManager/getInstance project)
                         (.getToolWindow "ECA")
                         (.activate nil))
                 (send-msg! project {:type "chat/selectChat"
                                     :data chat-id}))}))

(defn handle-config-changed [^Project project config]
  (when-let [settings (db/get-in project [:settings])]
    (send-msg! project
               {:type "config/updated"
                :data (merge config settings)})))

(defn handle-server-status-changed [status ^Project project]
  (when (= :running status)
    (send-msg! project {:type "server/setWorkspaceFolders"
                        :data [{:name (.getName project)
                                :uri (str (.toURI (io/file (.getBasePath project))))}]}))
  (when status
    (send-msg! project {:type "server/statusChanged"
                        :data (string/capitalize (name status))})))

(defn add-context-to-system-prompt [context ^Project project]
  (send-msg! project {:type "chat/addContextToSystemPrompt"
                      :data context}))

(defn ^:private on-focus-changed [^Editor editor _]
  (when-let [project (some-> editor .getProject)]
    (app-manager/read-action!
     {:run-fn
      (fn []
        (when-let [vfile (.getVirtualFile editor)]
          (let [caret-model (.getCaretModel editor)
                primary-caret (.getPrimaryCaret caret-model)
                selection-start (.getSelectionStart primary-caret)
                selection-end (.getSelectionEnd primary-caret)
                document (.getDocument editor)
                start-line (.getLineNumber document selection-start)
                start-char (- selection-start (.getLineStartOffset document start-line))
                end-line (.getLineNumber document selection-end)
                end-char (- selection-end (.getLineStartOffset document end-line))]
            (send-msg! project {:type "editor/focusChanged"
                                :data {:type :fileFocused
                                       :path (.getPath vfile)
                                       :position {:start {:line (inc start-line) :character (inc start-char)}
                                                  :end {:line (inc end-line) :character (inc end-char)}}}}))))})))

(defn ^:private current-selected-editor
  "Extracted so tests can stub the FileEditorManager static call. Returns
   the currently-focused editor in `project`, or nil when none. Wrapping
   in `some->` defends against `FileEditorManager/getInstance` returning
   nil in early-startup races and against the manager itself reporting no
   selected editor."
  [^Project project]
  (some-> (FileEditorManager/getInstance project) .getSelectedTextEditor))

(defn handle
  "Handles one MSG (JSON string) posted by the webview.

   Must return promptly: branches send requests/notifications to the
   server synchronously (preserving arrival order) but never wait for a
   response, a dialog or a popup here; those waits go through `async!`.
   Waiting here used to freeze the whole tool window: `handle` ran on the
   JCEF UI thread and a `chat/askQuestion` pending in the server message
   pipeline meant the awaited response could not even be read."
  [msg ^Project project]
  (let [{:keys [type data]} (json/parse-string msg keyword)]
    (if (= "webview/ready" type)
      (do
        ;; Mark the JS bridge as healthy so the watchdog scheduled in
        ;; tool_window.clj's onLoadingStateChange (loading?=false) handler does
        ;; not fire a false-positive "did not initialize" notification. Must be
        ;; set BEFORE any handle-* call below so a slow downstream call cannot
        ;; race with the 10s deadline.
        (db/assoc-in project [:webview-ready?] true)
        (handle-server-status-changed (db/get-in project [:status])
                                      project)
        (handle-config-changed project (db/get-in project [:server-config]))
        ;; Replay the cached MCP server roster. `tool/serverUpdated`
        ;; notifications from the ECA server are reactive-only -- if the
        ;; tool window is re-opened (or the user navigates to Settings ->
        ;; MCPs) after all notifications have already fired, the React
        ;; Redux slice would otherwise sit empty until the next change.
        ;; Mirrors the broadcast shape used by tool-server-updated /
        ;; tool-server-removed so consumers converge through the same code
        ;; path. `vec` so an empty cache serialises as `[]` instead of
        ;; `null`, which the React slice cannot iterate over. Closes #22.
        (send-msg! project {:type "tool/serversUpdated"
                            :data (vec (vals (db/get-in project [:session :mcp-servers])))})
        ;; send current opened editor if any
        (when-let [editor (current-selected-editor project)]
          (on-focus-changed editor nil))
        (db/assoc-in project [:on-focus-changed-fns :webview] #'on-focus-changed)
        ;; Hook the log-store into the webview. Every new entry is
        ;; forwarded as `logs/appended` so the Settings → Logs tab
        ;; renders them live. `subscribe!` replaces the previous
        ;; listener under `:webview`, which is what we want on
        ;; webview/ready re-delivery (e.g. tool-window re-open).
        (log-store/subscribe! project :webview
                              (fn [entry]
                                (send-msg! project {:type "logs/appended"
                                                    :data entry}))))
      (when-let [client (api/connected-client project)]
        (case type
          "chat/userPrompt" (request-then! client
                                           [:chat/prompt {:chatId (:chatId data)
                                                          :message (:prompt data)
                                                          :model (:model data)
                                                          :variant (:variant data)
                                                          :trust (:trust data)
                                                          :agent (:agent data)
                                                          :requestId (str (data :requestId))
                                                          :contexts (:contexts data)}]
                                           (fn [result]
                                             (send-msg! project
                                                        {:type "chat/newChat"
                                                         :data {:id (:chat-id result)}})))
          "chat/selectedModelChanged" (api/notify! client [:chat/selectedModelChanged {:chatId (:chatId data)
                                                                                       :model (:model data)
                                                                                       :variant (:variant data)}])
          "chat/selectedAgentChanged" (api/notify! client [:chat/selectedAgentChanged {:chatId (:chatId data)
                                                                                       :agent (:agent data)}])
          "chat/queryContext" (request-then! client [:chat/queryContext data]
                                            #(send-msg! project {:type "chat/queryContext"
                                                                 :data %}))
          "chat/queryCommands" (request-then! client [:chat/queryCommands data]
                                             #(send-msg! project {:type "chat/queryCommands"
                                                                  :data %}))
          "chat/queryFiles" (request-then! client [:chat/queryFiles data]
                                          #(send-msg! project {:type "chat/queryFiles"
                                                               :data %}))
          "editor/refresh"
          (.refreshFiles (LocalFileSystem/getInstance) [(.findFileByIoFile (LocalFileSystem/getInstance) (io/file (.getBasePath project)))] true true nil)
          "chat/toolCallApprove" (api/notify! client [:chat/toolCallApprove data])
          "chat/toolCallReject" (api/notify! client [:chat/toolCallReject data])
          "chat/promptStop" (api/notify! client [:chat/promptStop data])
          "chat/promptSteer" (api/notify! client [:chat/promptSteer data])
          "chat/promptSteerRemove" (api/notify! client [:chat/promptSteerRemove data])
          ;; Requests whose response carries nothing the webview needs:
          ;; the server drives the UI through its notifications.
          "chat/update" (api/request! client [:chat/update {:chatId (:chatId data)
                                                            :title (:title data)
                                                            :trust (:trust data)}])
          "chat/delete" (api/request! client [:chat/delete data])
          "chat/addFlag" (app-manager/invoke-later!
                          {:invoke-fn (fn []
                                        (let [user-input (Messages/showInputDialog
                                                          project
                                                          "Enter flag name"
                                                          "Add Flag"
                                                          (Messages/getQuestionIcon))]
                                          (when user-input
                                            (api/request! client [:chat/addFlag {:chatId (:chatId data)
                                                                                 :contentId (:contentId data)
                                                                                 :text user-input}]))))})
          "chat/removeFlag" (api/request! client [:chat/removeFlag data])
          "chat/fork" (api/request! client [:chat/fork data])
          "chat/rollback" (let [option (editor/quick-pick [{:id :rollback-messages-and-tools :label "Rollback messages and changes done by tool calls"}
                                                           {:id :rollback-only-messages :label "Rollback only messages"}
                                                           {:id :rollback-only-tools :label "Rollback only changes done by tool calls"}]
                                                          {:title "Select which rollback type"})]
                            (async!
                             (fn []
                               (when-let [includes (case (:id @option)
                                                     :rollback-messages-and-tools ["messages" "tools"]
                                                     :rollback-only-messages ["messages"]
                                                     :rollback-only-tools ["tools"]
                                                     nil)]
                                 (api/request! client [:chat/rollback (assoc data :includes includes)])))))
          ;; Resume-picker support. The webview asks for the list of
          ;; persisted chats; on click of a row it sends `chat/open`
          ;; which causes the server to emit `chat/cleared` →
          ;; `chat/opened` → N × `chat/contentReceived` → `config/updated`
          ;; BEFORE returning the open response. Those notifications
          ;; are already forwarded by the `defmethod` blocks below /
          ;; in `api.clj`, so the two branches here only need to round-
          ;; trip the request and its `{:found? bool ...}` response.
          ;; Both check the result for `:error` so the webview sees a
          ;; `{:requestId ... :error {...}}` envelope on failure rather
          ;; than a silently-dropped reply.
          "chat/list" (request-then! client [:chat/list {:limit (:limit data)
                                                         :sortBy (:sortBy data)}]
                                     (fn [result]
                                       (if-let [err (:error result)]
                                         (rpc-error-reply! project "chat/list" (:requestId data) (:message err))
                                         (reply! project "chat/list" (:requestId data) result))))
          "chat/open" (request-then! client [:chat/open {:chatId (:chatId data)}]
                                     (fn [result]
                                       (if-let [err (:error result)]
                                         (rpc-error-reply! project "chat/open" (:requestId data) (:message err))
                                         (reply! project "chat/open" (:requestId data) result))))
          "mcp/startServer" (api/notify! client [:mcp/startServer data])
          "mcp/stopServer" (api/notify! client [:mcp/stopServer data])
          "mcp/connectServer" (api/notify! client [:mcp/connectServer data])
          "mcp/logoutServer" (api/notify! client [:mcp/logoutServer data])
          "mcp/disableServer" (api/notify! client [:mcp/disableServer data])
          "mcp/enableServer" (api/notify! client [:mcp/enableServer data])
          "mcp/updateServer" (request-then! client [:mcp/updateServer data]
                                           #(reply! project "mcp/updateServer" (:requestId data) %))
          "mcp/addServer" (request-then! client [:mcp/addServer data]
                                        #(reply! project "mcp/addServer" (:requestId data) %))
          "mcp/removeServer" (request-then! client [:mcp/removeServer data]
                                           #(reply! project "mcp/removeServer" (:requestId data) %))
          "providers/list" (request-then! client [:providers/list {}]
                                         #(reply! project "providers/list" (:requestId data) %))
          "providers/login" (request-then! client [:providers/login data]
                                          #(reply! project "providers/login" (:requestId data) %))
          "providers/loginInput" (request-then! client [:providers/loginInput data]
                                               #(reply! project "providers/loginInput" (:requestId data) %))
          "providers/logout" (request-then! client [:providers/logout data]
                                           #(reply! project "providers/logout" (:requestId data) %))
          "editor/readInput" (app-manager/invoke-later!
                              {:invoke-fn (fn []
                                            (let [user-input (Messages/showInputDialog
                                                              project
                                                              ^String (:message data)
                                                              "Input Required"
                                                              (Messages/getQuestionIcon))]
                                              (send-msg! project {:type "editor/readInput"
                                                                  :data {:requestId (:requestId data)
                                                                         :value user-input}})))})
          "editor/openFile" (let [path (:path data)
                                  sys-ind-path (FileUtil/toSystemIndependentName path)
                                  vfile (.refreshAndFindFileByPath (LocalFileSystem/getInstance) sys-ind-path)]
                              (app-manager/invoke-later! {:invoke-fn
                                                          (fn []
                                                            (when vfile
                                                              (.openFile (FileEditorManager/getInstance project) vfile true)))}))
          "editor/openUrl" (when-let [url (:url data)]
                             (BrowserUtil/browse ^String url))
          "editor/openGlobalConfig"
          ;; Delegates to editor-actions so the same resolution rules
          ;; (ECA_CONFIG_PATH > XDG_CONFIG_HOME > platform default) are
          ;; shared with editor/readGlobalConfig / editor/writeGlobalConfig
          ;; below — and with eca-vscode + eca-desktop.
          (app-manager/invoke-later!
           {:invoke-fn
            (fn []
              (try
                (let [config-file (editor-actions/ensure-global-config-exists!)]
                  (if-let [vfile (.refreshAndFindFileByPath (LocalFileSystem/getInstance)
                                                            (FileUtil/toSystemIndependentName
                                                             (.getAbsolutePath config-file)))]
                    (.openFile (FileEditorManager/getInstance project) vfile true)
                    (Messages/showErrorDialog project
                                              "Failed to open global config file."
                                              "ECA Global Config")))
                (catch Exception e
                  (Messages/showErrorDialog project
                                            (str "Failed to prepare global config: "
                                                 (or (.getMessage e) (str e)))
                                            "ECA Global Config"))))})

          "editor/readGlobalConfig"
          (let [result (editor-actions/read-global-config)]
            (send-msg! project {:type "editor/readGlobalConfig"
                                :data (assoc result :request-id (:requestId data))}))

          "editor/writeGlobalConfig"
          (let [result (editor-actions/write-global-config
                        {:contents (or (:contents data) "")})]
            (send-msg! project {:type "editor/writeGlobalConfig"
                                :data (assoc result :request-id (:requestId data))}))

          "logs/snapshot"
          ;; Fire-and-forget response. `camel-cased-map` will convert
          ;; keyword keys (:ts :seq :source :level :text :session-id)
          ;; to the `ts seq source level text sessionId` shape the
          ;; webview expects.
          (send-msg! project {:type "logs/snapshot"
                              :data (log-store/snapshot project)})

          "logs/clear"
          ;; Wipe only the in-memory ring buffer; the legacy
          ;; `:server-stderr-string` surface that powers the 'ECA:
          ;; Show server logs' editor buffer is intentionally
          ;; preserved so bug reports still have full history.
          (log-store/clear! project)

          "logs/openFolder"
          ;; The IntelliJ port does not maintain a separate on-disk
          ;; log file — see log_store.clj for rationale. The natural
          ;; equivalent of the desktop's "reveal log file" surface is
          ;; the in-editor LightVirtualFile already provided by
          ;; `editor/openServerLogs`, so we just reuse that flow here.
          (server-logs/open-server-logs! project)
          "jobs/list" (request-then! client [:jobs/list {}]
                                    #(reply! project "jobs/list" (:requestId data) %))
          "jobs/readOutput" (request-then! client [:jobs/readOutput {:job-id (:jobId data)}]
                                          #(reply! project "jobs/readOutput" (:requestId data) %))
          "jobs/kill" (request-then! client [:jobs/kill {:job-id (:jobId data)}]
                                    #(reply! project "jobs/kill" (:requestId data) %))
          "editor/openServerLogs" (server-logs/open-server-logs! project)
          "chat/answerQuestion" (let [request-id (:requestId data)]
                                  (when-let [p (get @pending-questions request-id)]
                                    (deliver p {:answer (:answer data)})
                                    (swap! pending-questions dissoc request-id)))
          "editor/saveClipboardImage"
          (let [{:keys [base64Data mimeType requestId]} data
                ext-map {"image/png" "png"
                         "image/jpeg" "jpg"
                         "image/jpg" "jpg"
                         "image/gif" "gif"
                         "image/webp" "webp"
                         "image/svg+xml" "svg"}
                ext (get ext-map mimeType "png")
                tmp-file (io/file (System/getProperty "java.io.tmpdir")
                                  (str "eca-screenshot-" (System/currentTimeMillis) "." ext))]
            (try
              (let [bytes (.decode (Base64/getDecoder) ^String base64Data)]
                (with-open [out (io/output-stream tmp-file)]
                  (.write out ^bytes bytes))
                (send-msg! project {:type "editor/saveClipboardImage"
                                    :data {:requestId requestId
                                           :path (.getAbsolutePath tmp-file)}}))
              (catch Exception e
                (logger/warn "Failed to save clipboard image:" (.getMessage e)))))
          "editor/saveFile"
          (app-manager/invoke-later!
           {:invoke-fn
            (fn []
              (let [default-name (or (:defaultName data) "chat-export.md")
                    descriptor (FileSaverDescriptor. "Export Chat to Markdown" "Choose location to save the chat export" ^"[Ljava.lang.String;" (into-array String ["md"]))
                    dialog (.createSaveFileDialog (FileChooserFactory/getInstance) descriptor project)
                    base-dir (.findFileByIoFile (LocalFileSystem/getInstance) (io/file (.getBasePath project)))
                    wrapper ^VirtualFileWrapper (.save dialog base-dir ^String default-name)]
                (when wrapper
                  (spit (.getFile wrapper) (:content data) :encoding "UTF-8"))))})
          (logger/warn "Unknown webview message type:" type)))))
  nil)

(defonce ^:private ^ExecutorService message-executor
  (Executors/newSingleThreadExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. ^Runnable runnable "ECA webview messages")
         (.setDaemon true)
         (.setContextClassLoader (.getClassLoader ClojureClassLoader)))))))

(defn dispatch!
  "Queues MSG from the webview for `handle` on a dedicated thread, in
   arrival order. This is what the JBCefJSQuery handler calls: it runs on
   the JCEF UI thread and has to return right away, since while that
   thread is busy the browser neither processes input nor runs the
   JavaScript `send-msg!` posts, and the whole panel looks dead."
  [msg ^Project project]
  (.execute message-executor
            (fn []
              (try
                (handle msg project)
                (catch Throwable e
                  (logger/error "Error handling webview message:" e)))))
  nil)

(defmethod api/config-updated :default
  [{:keys [project]} params]
  ;; The server scopes per-chat config updates with a top-level chatId
  ;; (kebab-cased to :chat-id by lsp4clj on receive). Keep that field out
  ;; of the persisted :server-config snapshot (which is replayed verbatim
  ;; on webview/ready) so a stale chatId never leaks into an unrelated
  ;; re-broadcast; the live params keep the field for the immediate
  ;; forward to the webview.
  (db/update-in project [:server-config] #(merge % (dissoc params :chat-id)))
  (handle-config-changed project params))

(defmethod api/chat-content-received :default
  [{:keys [project]} params]
  (send-msg! project {:type "chat/contentReceived"
                      :data params})
  (inline-chat/on-content-received project params))

(defmethod api/chat-cleared :default
  [{:keys [project]} params]
  (send-msg! project {:type "chat/cleared"
                      :data params}))

(defmethod api/chat-deleted :default
  [{:keys [project]} params]
  (send-msg! project {:type "chat/deleted"
                      :data (:chat-id params)})
  (inline-chat/on-chat-deleted project params))

(defmethod api/chat-opened :default
  [{:keys [project]} params]
  (send-msg! project {:type "chat/opened"
                      :data params})
  (inline-chat/on-chat-opened project params))

(defmethod api/chat-status-changed :default
  [{:keys [project]} params]
  (send-msg! project {:type "chat/statusChanged"
                      :data params})
  (inline-chat/on-status-changed project params))

(defmethod api/tool-server-updated  :default
  [{:keys [project]} params]
  (db/assoc-in project [:session :mcp-servers (:name params)] params)
  (send-msg! project {:type "tool/serversUpdated"
                      :data (vals (db/get-in project [:session :mcp-servers]))}))

(defmethod api/tool-server-removed :default
  [{:keys [project]} params]
  (db/update-in project [:session :mcp-servers] #(dissoc % (:name params)))
  (send-msg! project {:type "tool/serverRemoved"
                      :data params})
  ;; Defensive re-broadcast so consumers that only listen to the
  ;; full-list message still converge.
  (send-msg! project {:type "tool/serversUpdated"
                      :data (vals (db/get-in project [:session :mcp-servers]))}))

(defmethod api/providers-updated :default
  [{:keys [project]} params]
  (send-msg! project {:type "providers/updated"
                      :data params}))

(defmethod api/jobs-updated :default
  [{:keys [project]} params]
  (send-msg! project {:type "jobs/updated"
                      :data params}))

(defmethod api/chat-ask-question :default
  [{:keys [project]} params]
  (inline-chat/on-ask-question project params)
  (let [request-id (str (java.util.UUID/randomUUID))
        p (promise)]
    (swap! pending-questions assoc request-id p)
    (send-msg! project {:type "chat/askQuestion"
                        :data (assoc params :requestId request-id)})
    (let [result (deref p 300000 {:answer nil :cancelled true})]
      (swap! pending-questions dissoc request-id)
      result)))
