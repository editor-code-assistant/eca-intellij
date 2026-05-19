(ns dev.eca.eca-intellij.extension.tool-window
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [com.rpl.proxy-plus :refer [proxy+]]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.notification :as notification]
   [dev.eca.eca-intellij.shared :as shared]
   [dev.eca.eca-intellij.webview :as webview])
  (:import
   [com.intellij.ide BrowserUtil]
   [com.intellij.openapi.actionSystem ActionManager AnActionEvent]
   [com.intellij.openapi.keymap Keymap KeymapManager]
   [com.intellij.openapi.project DumbAwareAction Project]
   [com.intellij.openapi.util Disposer]
   [com.intellij.openapi.wm ToolWindow ToolWindowAnchor ToolWindowFactory]
   [com.intellij.ui.content ContentFactory]
   [com.intellij.ui.jcef JBCefApp JBCefBrowser JBCefBrowserBase JBCefClient JBCefClient$Properties JBCefJSQuery]
   [dev.eca.eca_intellij EcaSchemeHandlerFactory]
   [java.awt BorderLayout Component]
   [java.awt.event KeyEvent]
   [javax.swing BorderFactory JComponent JLabel JPanel]
   [org.cef CefApp]
   [org.cef.browser CefBrowser CefFrame]
   [org.cef.callback CefCallback CefSchemeHandlerFactory]
   [org.cef.handler CefKeyboardHandler$CefKeyEvent CefKeyboardHandler$CefKeyEvent$EventType
                    CefKeyboardHandlerAdapter CefLoadHandlerAdapter CefResourceHandler]
   [org.cef.misc BoolRef IntRef]
   [org.cef.network CefRequest CefResponse]))

(set! *warn-on-reflection* true)

(defn ^:private read-response
  "Stream the response in chunks honoring bytes-to-read. Returns true while there is
   data remaining and false when fully sent. Uses a per-request offset atom."
  [^bytes data-out bytes-to-read ^IntRef bytes-read ^bytes bytes offset-atom]
  (let [len (alength bytes)
        offset ^long @offset-atom
        remaining (- len offset)]
    (if (pos? remaining)
      (let [to-copy (int (min bytes-to-read remaining))]
        (System/arraycopy bytes offset data-out 0 to-copy)
        (.set bytes-read to-copy)
        (swap! offset-atom + to-copy)
        ;; More data may remain; signal to call again if needed
        true)
      (do
        ;; No more data: signal EOF
        (.set bytes-read 0)
        false))))

(defn ^:private eca-theme-scheme-handler []
  (proxy+ [] CefSchemeHandlerFactory
    (create [_ _browser _frame _scheme _req]
      (let [offset* (atom 0)
            bytes* (atom nil)]
        (proxy+ [] CefResourceHandler
          (processRequest [_ _req ^CefCallback callback]
            (reset! offset* 0)
            (reset! bytes* (.getBytes (webview/theme-css) "UTF-8"))
            (.Continue callback)
            true)
          (getResponseHeaders [_ ^CefResponse resp ^IntRef length _redirect-url]
            (.setMimeType resp "text/css")
            (.setStatus resp 200)
            (.set length (alength ^bytes @bytes*)))
          (readResponse [_ data-out bytes-to-read bytes-read _callback]
            (read-response data-out bytes-to-read bytes-read @bytes* offset*))
          (cancel [_]))))))

(defn ^:private docs-scheme-handler [^JBCefBrowser browser url]
  (proxy+ [] CefSchemeHandlerFactory
    (create [_ _browser _frame _scheme _req]
      (proxy+ [] CefResourceHandler
        (processRequest [_ ^CefRequest req _]
          (BrowserUtil/browse (.getURL req))
          false)
        (getResponseHeaders [_ _ _ _])
        (readResponse [_ _ _ _ _])
        (cancel [_]
          (.loadURL browser "http://eca/not-found.html")
          (.loadURL browser url))))))

(defn ^:private browser-javascript ^String [^JBCefJSQuery js-query]
  (format (str "window.postMessageToEditor = function(message) {"
               "  const msg = JSON.stringify(message);"
               "  %s"
               "}")
          (.inject js-query "msg")))

;; CEF event-flag bits (mirrors cef_event_flags_t in cef_types.h).
(def ^:private ^:const ^long event-flag-shift   0x02)
(def ^:private ^:const ^long event-flag-control 0x04)
(def ^:private ^:const ^long event-flag-alt     0x08)
(def ^:private ^:const ^long event-flag-command 0x80)

;; Emacs-keymap caret-motion chord (Windows VK code of the typed letter)
;; -> destination navigation key. Matches IntelliJ's built-in Emacs keymap,
;; which the JCEF webview otherwise bypasses entirely.
(def ^:private emacs-chord->nav-key
  {KeyEvent/VK_A KeyEvent/VK_HOME
   KeyEvent/VK_E KeyEvent/VK_END
   KeyEvent/VK_P KeyEvent/VK_UP
   KeyEvent/VK_N KeyEvent/VK_DOWN
   KeyEvent/VK_F KeyEvent/VK_RIGHT
   KeyEvent/VK_B KeyEvent/VK_LEFT})

(defn ^:private emacs-keymap?
  "True when the user's active IntelliJ keymap name contains 'emacs'
   (covers 'Emacs', 'Emacs (macOS)', and user-overridden Emacs variants)."
  []
  (when-let [^Keymap keymap (some-> (KeymapManager/getInstance) .getActiveKeymap)]
    (-> (.getName keymap) .toLowerCase (.contains "emacs"))))

(defn ^:private send-nav-key
  "Synthesize a press+release of `target-vk` on `cef-browser` so the focused
   editable element handles it as a native navigation key."
  [^CefBrowser cef-browser ^Component component target-vk]
  (let [now (System/currentTimeMillis)
        vk (int target-vk)]
    (.sendKeyEvent cef-browser
                   (KeyEvent. component KeyEvent/KEY_PRESSED  now 0 vk KeyEvent/CHAR_UNDEFINED))
    (.sendKeyEvent cef-browser
                   (KeyEvent. component KeyEvent/KEY_RELEASED now 0 vk KeyEvent/CHAR_UNDEFINED))))

(defn ^:private emacs-keyboard-handler
  "CEF keyboard handler that translates the six standard Emacs caret-motion
   chords (Ctrl+A/E/P/N/F/B) inside editable webview elements into the
   matching navigation keys, restoring IntelliJ's Emacs-keymap behavior
   inside the JCEF chat prompt. Active only when the user's active
   IntelliJ keymap is Emacs; otherwise the chord passes through to
   Chromium unchanged (so Ctrl+A still selects-all, Ctrl+F still finds, ...)."
  [^JBCefBrowser browser]
  (let [^CefBrowser cef-browser (.getCefBrowser browser)
        ^Component component (.getComponent browser)
        other-mods-mask (bit-or event-flag-shift event-flag-alt event-flag-command)]
    (proxy+ [] CefKeyboardHandlerAdapter
      (onPreKeyEvent [_this _b ^CefKeyboardHandler$CefKeyEvent event ^BoolRef _shortcut]
        (let [modifiers (.modifiers event)]
          (if (and (identical? CefKeyboardHandler$CefKeyEvent$EventType/KEYEVENT_RAWKEYDOWN
                               (.type event))
                   (pos? (bit-and modifiers event-flag-control))
                   (zero? (bit-and modifiers other-mods-mask))
                   (.focus_on_editable_field event)
                   (emacs-keymap?))
            (if-let [target-vk (get emacs-chord->nav-key (.windows_key_code event))]
              (do (send-nav-key cef-browser component target-vk)
                  true)
              false)
            false))))))

(defn ^:private cef-error-code-name ^String [^Enum error-code]
  ;; CefLoadHandler$ErrorCode is a Java enum; tolerate older binaries
  ;; where the value might be missing/null without breaking the handler.
  ;; The ^Enum hint lets the compiler resolve .name without reflection.
  (try
    (some-> error-code .name)
    (catch Throwable _ (str error-code))))

(defn ^:private schedule-webview-ready-watchdog!
  "Logs a warning + shows a balloon notification when the React side never
   answers our JS bridge with `webview/ready` within `timeout-ms`. This is the
   exact symptom of a JBCefJSQuery pool that was created before our per-client
   size bump took effect — exactly what was observed in IDE 2026.1 logs with
   multiple projects open simultaneously."
  [^Project project timeout-ms]
  (future
    (try
      (Thread/sleep timeout-ms)
      (when (and (not (.isDisposed project))
                 (not (db/get-in project [:webview-ready?])))
        (logger/warn (format "[ECA] webview/ready not received within %dms; JS bridge may be unhealthy."
                             timeout-ms))
        (notification/show-notification!
         {:project project
          :type :warning
          :title "ECA webview did not initialize"
          :message "The chat panel loaded but its JS bridge did not signal ready. Open the ECA tool window's gear menu and pick 'Reload ECA webview'."}))
      (catch InterruptedException _)
      (catch Throwable t
        (logger/warn "[ECA] webview-ready watchdog error:" (.getMessage t))))))

(defn ^:private create-webview ^JBCefBrowser [^Project project url]
  (let [browser (-> (JBCefBrowser/createBuilder)
                    ;; Heavyweight (native) rendering. OSR (true) intermittently fails to
                    ;; invalidate freed regions when a large React subtree is replaced in
                    ;; one commit (e.g. switching Settings tabs while LogsTab holds
                    ;; thousands of entries), leaving the prior tab's pixels visible
                    ;; behind the new one until another event triggers a repaint (#21).
                    (.setOffScreenRendering false)
                    (.build))
        ;; Per-client JS query pool size. We previously bumped this via a
        ;; global `System.setProperty "ide.browser.jcef.jsQueryPoolSize"` inside
        ;; the tool-window factory, but the JCEF global `JSQueryPool` is
        ;; created eagerly by IntelliJ (and bundled plugins like Markdown
        ;; Preview) well before our factory runs — at which point the property
        ;; is silently ignored (`JSQueryPool has already been created, this
        ;; request will be ignored`), and `JBCefJSQuery/create` calls can fail
        ;; on busy IDEs with multiple projects open. Setting it on our own
        ;; JBCefClient before the first `JBCefJSQuery/create` cannot be
        ;; preempted by other plugins.
        ;; Hint must sit before the symbol, not the init expression
        ;; (Clojure drops let-binding metadata on the value side); see the
        ;; same gotcha documented around the `reload-webview` action below.
        ^JBCefClient client (.getJBCefClient browser)
        _ (.setProperty client JBCefClient$Properties/JS_QUERY_POOL_SIZE
                        (Integer/valueOf 200))
        js-query (JBCefJSQuery/create browser)
        cef-browser (.getCefBrowser browser)]
    (db/assoc-in project [:webview-browser] browser)
    (db/assoc-in project [:webview-ready?] false)
    (Disposer/register project browser)
    (.addHandler js-query (fn [msg] (webview/handle msg project)))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "http" "eca" (EcaSchemeHandlerFactory.))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "http" "eca-theme" (eca-theme-scheme-handler))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "https" "eca.dev" (docs-scheme-handler browser url))
    (.addLoadHandler
     client
     (proxy+ [] CefLoadHandlerAdapter
       (onLoadStart [_ _browser ^CefFrame frame _transition-type]
         ;; Inject the JS bridge as soon as the main frame starts loading —
         ;; BEFORE the page's deferred `<script type="module">` runs. We used
         ;; to inject in `onLoadingStateChange(loading?=false)`, which fires
         ;; AFTER all scripts have executed; by then the React app's mount
         ;; already called `window.postMessageToEditor({type:"webview/ready"})`
         ;; against an undefined function, the call silently failed, and the
         ;; webview rendered blank. See bug repro 2026-05-13: status broadcast
         ;; reached the `:webview` listener but `webview/ready` never came
         ;; back within the 10s watchdog window.
         (when (.isMain frame)
           (.executeJavaScript frame (browser-javascript js-query) (.getURL frame) 0)))
       (onLoadingStateChange [_ _ loading? _ _]
         (if loading?
           ;; (Re)load starting: reset readiness so the watchdog can
           ;; distinguish "fresh load that never bridged" from a previous
           ;; load that bridged successfully.
           (db/assoc-in project [:webview-ready?] false)
           (do
             ;; Re-inject the bridge as a defensive backup in case `onLoadStart`
             ;; was missed for the main frame (e.g. exotic IntelliJ builds where
             ;; `CefFrame/isMain` returns false unexpectedly). Idempotent — JS
             ;; just rebinds `window.postMessageToEditor` to the same function.
             (.executeJavaScript cef-browser (browser-javascript js-query) (.getURL cef-browser) 0)
             (webview/handle-server-status-changed (db/get-in project [:status]) project)
             (db/assoc-in project [:on-status-changed-fns :webview] (fn [project status]
                                                                      (webview/handle-server-status-changed status project)))
             (db/assoc-in project [:on-settings-changed-fns :webview] (fn []
                                                                        (webview/handle-config-changed project (db/get-in project [:server-config]))))
             (schedule-webview-ready-watchdog! project 10000))))
       (onLoadError [_ _browser _frame error-code error-text failed-url]
         (let [code-name (cef-error-code-name error-code)]
           (cond
             ;; ERR_ABORTED is Chromium's signal that a navigation was
             ;; preempted by another navigation — exactly what the Reload
             ;; action's `.loadURL "http://eca/not-found.html"` → `.loadURL url`
             ;; trick deliberately causes. Not a real failure; downgrade to
             ;; INFO and skip the user-facing balloon so clicking Reload
             ;; doesn't spawn a spurious "ECA webview failed to load" error.
             (= "ERR_ABORTED" code-name)
             (logger/info (format "[ECA] Webview navigation aborted (expected): url=%s" failed-url))

             :else
             (do
               ;; Real failure: scheme-handler 404/500, broken bundled dist,
               ;; connection refused (dev mode misconfig), etc. Surface both
               ;; in idea.log and as a balloon so the user can recover
               ;; without restarting the IDE.
               (logger/error (format "[ECA] Webview load error: code=%s text=%s url=%s"
                                     code-name error-text failed-url))
               (notification/show-notification!
                {:project project
                 :type :error
                 :title "ECA webview failed to load"
                 :message (format "%s — %s. Try the 'Reload ECA webview' action on the tool window's gear menu."
                                  code-name (or error-text failed-url))}))))))
     cef-browser)
    (.addKeyboardHandler
     client
     (emacs-keyboard-handler browser)
     cef-browser)
    browser))

(defn ^:private event-project ^Project [event]
  ;; Resolve the project from the AnActionEvent, falling back to the first
  ;; live project. The fallback exists for completeness, but in multi-project
  ;; IDEs the event-driven path is the only one that reloads the *correct*
  ;; tool window — the previous (first (db/all-projects)) code was reported
  ;; to reload the wrong browser, leaving the actually-blank one unrecovered.
  (or (some-> ^AnActionEvent event .getProject)
      (first (db/all-projects))))

(defn ^:private reload-webview [url]
  (proxy+
   ["Reload ECA webview" "Reload ECA webview" (shared/logo-icon)]
   DumbAwareAction
    (actionPerformed [_ event]
      ;; The ^JBCefBrowserBase hint MUST sit before the symbol, not before the
      ;; init expression — Clojure metadata on the init expression is silently
      ;; dropped on let/when-let bindings and would leave the (.loadURL ...)
      ;; calls below reflective.
      (when-let [^JBCefBrowserBase browser (some-> (event-project event)
                                                   (db/get-in [:webview-browser]))]
        (.loadURL browser "http://eca/not-found.html")
        (.loadURL browser url)))))

(defn ^:private open-devtools []
  (proxy+
   ["Open ECA webview devtools" "Open ECA webview devtools" (shared/logo-icon)]
   DumbAwareAction
    (actionPerformed [_ event]
      (when-let [^JBCefBrowserBase browser (some-> (event-project event)
                                                   (db/get-in [:webview-browser]))]
        (.openDevtools browser)))))

(defn ^:private jcef-unavailable-panel ^JComponent []
  ;; Shown in place of the JCEF browser when `JBCefApp/isSupported` is false
  ;; (e.g. user is on a non-JBR runtime, or `ide.browser.jcef.enabled` is off).
  ;; Without this, the tool window would silently render nothing — the exact
  ;; "blank webview" symptom we are trying to eliminate.
  (let [panel (doto (JPanel. (BorderLayout.))
                (.setBorder (BorderFactory/createEmptyBorder 16 16 16 16)))
        label (JLabel. (str "<html><body style='width: 320px'>"
                            "<h3>ECA webview unavailable</h3>"
                            "<p>This IDE build does not have JCEF (the embedded "
                            "Chromium browser) available, so the ECA chat tool "
                            "window cannot render.</p>"
                            "<p>To fix:</p>"
                            "<ol>"
                            "<li>Open <b>Help &rarr; Find Action&hellip;</b> and run <b>Registry&hellip;</b></li>"
                            "<li>Enable <code>ide.browser.jcef.enabled</code></li>"
                            "<li>Restart IntelliJ</li>"
                            "</ol>"
                            "<p>If the option is missing or unchangeable, your "
                            "IDE bundle is built without JCEF &mdash; switch to "
                            "a JetBrains Runtime that ships it.</p>"
                            "</body></html>"))]
    (.add panel label BorderLayout/NORTH)
    panel))

(defn ^:private create-tool-window-content
  [^Project project ^ToolWindow tool-window]
  (when (config/dev?)
    (System/setProperty "ide.browser.jcef.contextMenu.devTools.enabled" "true"))
  (if-not (JBCefApp/isSupported)
    (let [content (.createContent (ContentFactory/getInstance)
                                  (jcef-unavailable-panel) nil false)]
      (logger/warn (str "[ECA] JBCefApp/isSupported returned false; rendering "
                        "the JCEF-unavailable fallback panel instead of the "
                        "chat webview."))
      (.addContent (.getContentManager tool-window) content))
    (let [url (if (config/dev?)
                "http://localhost:5173/intellij_index.html"
                "http://eca/index.html")
          browser (create-webview project url)
          content (.createContent (ContentFactory/getInstance) (.getComponent browser) nil false)
          actions (->> [(.getAction (ActionManager/getInstance) "MaximizeToolWindow")
                        (reload-webview url)
                        (when (config/dev?) (open-devtools))]
                       (remove nil?))]
      (.addContent (.getContentManager tool-window) content)
      (.setTitleActions tool-window actions)
      (.loadURL browser url))))

(def-extension EcaToolWindowFactory []
  ToolWindowFactory
  (createToolWindowContent [_this project tool-window]
    (create-tool-window-content project tool-window))
  (shouldBeAvailable [_this _project] true)

  (init [_ ^ToolWindow _tool-window])

  (isApplicableAsync
    ([_ _] true)
    ([_ _ _] true))
  (isApplicable [_ _project] true)
  (isDoNotActivateOnStart [_] false)

  (manager [_ _ _])
  (manage [_ _ _ _])

  (getIcon [_] (shared/logo-icon))

  (getAnchor [_] ToolWindowAnchor/RIGHT))

(comment
  (db/get-in (first (db/all-projects)) [:webview-browser]))
