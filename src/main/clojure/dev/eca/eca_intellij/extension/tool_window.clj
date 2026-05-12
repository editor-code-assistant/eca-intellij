(ns dev.eca.eca-intellij.extension.tool-window
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [com.rpl.proxy-plus :refer [proxy+]]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.shared :as shared]
   [dev.eca.eca-intellij.webview :as webview])
  (:import
   [com.intellij.ide BrowserUtil]
   [com.intellij.openapi.actionSystem ActionManager]
   [com.intellij.openapi.keymap Keymap KeymapManager]
   [com.intellij.openapi.project DumbAwareAction Project]
   [com.intellij.openapi.util Disposer]
   [com.intellij.openapi.wm ToolWindow ToolWindowAnchor ToolWindowFactory]
   [com.intellij.ui.content ContentFactory]
   [com.intellij.ui.jcef JBCefBrowser JBCefBrowserBase JBCefJSQuery]
   [dev.eca.eca_intellij EcaSchemeHandlerFactory]
   [java.awt Component]
   [java.awt.event KeyEvent]
   [org.cef CefApp]
   [org.cef.browser CefBrowser]
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

(defn ^:private create-webview ^JBCefBrowser [^Project project url]
  (let [browser (-> (JBCefBrowser/createBuilder)
                    (.setOffScreenRendering true) ;; TODO move to config
                    (.build))
        js-query (JBCefJSQuery/create browser)
        cef-browser (.getCefBrowser browser)]
    (db/assoc-in project [:webview-browser] browser)
    (Disposer/register project browser)
    (.addHandler js-query (fn [msg] (webview/handle msg project)))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "http" "eca" (EcaSchemeHandlerFactory.))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "http" "eca-theme" (eca-theme-scheme-handler))
    (.registerSchemeHandlerFactory
     (CefApp/getInstance) "https" "eca.dev" (docs-scheme-handler browser url))
    (.addLoadHandler
     (.getJBCefClient browser)
     (proxy+ [] CefLoadHandlerAdapter
       (onLoadingStateChange [_ _ loading? _ _]
         (when-not loading?
           (let [javascript (browser-javascript js-query)]
             (.executeJavaScript cef-browser javascript (.getURL cef-browser) 0)
             (webview/handle-server-status-changed (db/get-in project [:status]) project)
             (db/assoc-in project [:on-status-changed-fns :webview] (fn [project status]
                                                                      (webview/handle-server-status-changed status project)))
             (db/assoc-in project [:on-settings-changed-fns :webview] (fn []
                                                                        (webview/handle-config-changed project (db/get-in project [:server-config]))))))))
     (.getCefBrowser browser))
    (.addKeyboardHandler
     (.getJBCefClient browser)
     (emacs-keyboard-handler browser)
     (.getCefBrowser browser))
    browser))

(defn ^:private reload-webview [url]
  (proxy+
   ["Reload ECA webview" "Reload ECA webview" (shared/logo-icon)]
   DumbAwareAction
    (actionPerformed [_ _event]
      (let [browser ^JBCefBrowserBase (db/get-in (first (db/all-projects)) [:webview-browser])]
        (.loadURL browser "http://eca/not-found.html")
        (.loadURL browser url)))))

(defn ^:private open-devtools []
  (proxy+
   ["Open ECA webview devtools" "Open ECA webview devtools" (shared/logo-icon)]
   DumbAwareAction
    (actionPerformed [_ _event]
      (let [browser ^JBCefBrowserBase (db/get-in (first (db/all-projects)) [:webview-browser])]
        (.openDevtools browser)))))

(defn ^:private create-tool-window-content
  [^Project project ^ToolWindow tool-window]
  (System/setProperty "ide.browser.jcef.jsQueryPoolSize" "200")
  (when (config/dev?)
    (System/setProperty "ide.browser.jcef.contextMenu.devTools.enabled" "true"))
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
    (.loadURL browser url)))

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
