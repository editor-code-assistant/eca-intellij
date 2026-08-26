(ns dev.eca.eca-intellij.inline-chat
  "Inline chat: ask ECA from any editor and stream the answer into a block
   inlay below the cursor/selection, backed by a regular chat session via
   the `chat/inlinePrompt` server method. Tool calls, approvals, stop and
   follow-ups behave like any other chat; the backing chat also shows up
   in the ECA tool window as a normal tab.

   The chat sticks to the file: follow-up prompts reuse the same chat
   until it is deleted or re-picked. Clicking the inlay opens the actions
   popup (follow-up, stop, approve/reject tool calls, open chat, dismiss)."
  (:require
   [clojure.string :as string]
   [com.github.ericdallo.clj4intellij.app-manager :as app-manager]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [com.rpl.proxy-plus :refer [proxy+]]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.editor :as editor]
   [dev.eca.eca-intellij.notification :as notification])
  (:import
   [com.intellij.openapi.editor Editor EditorCustomElementRenderer Inlay]
   [com.intellij.openapi.editor.colors EditorFontType]
   [com.intellij.openapi.editor.event EditorMouseEvent EditorMouseListener]
   [com.intellij.openapi.fileEditor FileDocumentManager]
   [com.intellij.openapi.project Project]
   [com.intellij.openapi.ui Messages]
   [com.intellij.openapi.util Disposer]
   [com.intellij.ui JBColor]
   [java.awt Color Font FontMetrics Graphics Rectangle]
   [java.util UUID]))

(set! *warn-on-reflection* true)

(def ^:private max-display-lines 20)

;;;; Pure session state machine
;;
;; A session state map: {:answer string :status string
;;                       :state (:sending|:running|:done)
;;                       :pending-tools [[id summary] ...] :dead? bool}
;; Kept pure so the content routing is testable without an editor.

(defn ^:private pending-status [pending-tools]
  (string/join ", " (map second pending-tools)))

(defn ^:private drop-pending [pending-tools id]
  (into [] (remove #(= id (first %))) pending-tools))

(defn transition
  "Return STATE updated for a chat content EVENT {:role r :content c}.
   Mirrors the eca-emacs/eca-vscode inline content mapping. CONTENT keys
   are kebab-cased (lsp4clj converts the wire's camelCase on receive)."
  [state {:keys [role content]}]
  (let [{:keys [type text summary id manual-approval]} content
        tool-name (:name content)]
    (case type
      "text"
      (case role
        ;; A user message starts a turn: reset the answer area so the
        ;; inlay always shows the answer to the last question.
        "user" (assoc state :answer "" :status "Waiting model..." :state :running)
        "assistant" (-> state
                        (update :answer str text)
                        (assoc :status "Streaming..." :state :running))
        ;; System text carries errors and notices; surface it so failed
        ;; turns don't render as a clean Done.
        "system" (update state :answer str text)
        state)

      "progress"
      (case (:state content)
        ;; While tool calls wait for approval, keep their summaries as
        ;; the status instead of the generic server progress text.
        "running" (if (seq (:pending-tools state))
                    state
                    (assoc state :status (or text "Running...") :state :running))
        "finished" (assoc state :status "Done" :state :done :pending-tools [])
        state)

      "reasonStarted" (assoc state :status "Thinking..." :state :running)
      "reasonFinished" (assoc state :status "Waiting model..." :state :running)

      "toolCallPrepare"
      (assoc state
             :status (or summary (str "Preparing tool " tool-name "..."))
             :state :running)

      "toolCallRun"
      (if manual-approval
        (let [pending (conj (drop-pending (:pending-tools state) id)
                            [id (or summary (str "Tool " tool-name " needs approval"))])]
          (assoc state :pending-tools pending :status (pending-status pending)))
        (assoc state
               :status (or summary (str "Running tool " tool-name "..."))
               :state :running))

      ;; Approved (possibly from another client): no longer pending.
      "toolCallRunning"
      (let [pending (drop-pending (:pending-tools state) id)]
        (assoc state
               :pending-tools pending
               :status (or summary (str "Running tool " tool-name "..."))
               :state :running))

      "toolCalled"
      (let [pending (drop-pending (:pending-tools state) id)]
        (if (seq pending)
          (assoc state :pending-tools pending :status (pending-status pending))
          (assoc state :pending-tools [] :status "Waiting model..." :state :running)))

      "toolCallRejected"
      (let [pending (drop-pending (:pending-tools state) id)]
        (if (seq pending)
          (assoc state :pending-tools pending :status (pending-status pending))
          (assoc state :pending-tools [] :status "Tool call rejected")))

      state)))

(def ^:private inline-md-regex
  ;; inline code | bold | italic | [text](url)
  #"`([^`]+)`|\*\*([^*]+?)\*\*|\*([^*\s](?:[^*]*[^*\s])?)\*|\[([^\]]+)\]\(([^)]+)\)")

(defn ^:private parse-inline
  "Split TEXT into styled segments {:text s :style k}, hiding the
   markdown markup of inline code, bold, italic and links."
  [text]
  (let [matcher (re-matcher inline-md-regex text)]
    (loop [last-end 0
           segments []]
      (if (.find matcher)
        (let [start (.start matcher)
              segments (cond-> segments
                         (> start last-end)
                         (conj {:text (subs text last-end start) :style :plain}))
              segments (cond
                         (.group matcher 1) (conj segments {:text (.group matcher 1) :style :code})
                         (.group matcher 2) (conj segments {:text (.group matcher 2) :style :bold})
                         (.group matcher 3) (conj segments {:text (.group matcher 3) :style :italic})
                         (.group matcher 4) (conj segments {:text (.group matcher 4) :style :link})
                         :else segments)]
          (recur (.end matcher) segments))
        (cond-> segments
          (< last-end (count text))
          (conj {:text (subs text last-end) :style :plain}))))))

(defn markdown->lines
  "Parse markdown ANSWER into render lines of styled segments:
   {:segments [{:text s :style k}] :code-block? bool}. Fence lines are
   dropped and fenced content styled :code; headers render bold without
   the #s; list markers render as bullets; inline markup is hidden."
  [answer]
  (if (string/blank? answer)
    []
    (loop [remaining (string/split-lines answer)
           in-fence? false
           out []]
      (if-let [[line & more] (seq remaining)]
        (cond
          (re-find #"^\s*```" line)
          (recur more (not in-fence?) out)

          in-fence?
          (recur more true (conj out {:segments [{:text line :style :code}]
                                      :code-block? true}))

          (re-find #"^#{1,6} " line)
          (recur more false (conj out {:segments [{:text (string/replace line #"^#{1,6} +" "")
                                                   :style :header}]}))

          :else
          (recur more false (conj out {:segments (parse-inline
                                                  (string/replace line #"^(\s*)[-*] " "$1• "))})))
        out))))

(defn wrap-segments
  "Hard-wrap a SEGMENTS vector at WIDTH chars, returning at least one
   line (vector of segment vectors)."
  [segments width]
  (loop [remaining segments
         cur []
         cur-len 0
         lines []]
    (if-let [[{:keys [text style]} & more] (seq remaining)]
      (let [len (count text)]
        (if (<= (+ cur-len len) width)
          (recur more (conj cur {:text text :style style}) (+ cur-len len) lines)
          (let [take-n (- width cur-len)
                head (subs text 0 take-n)
                tail (subs text take-n)]
            (recur (cons {:text tail :style style} more)
                   []
                   0
                   (conj lines (cond-> cur
                                 (seq head) (conj {:text head :style style})))))))
      (if (and (empty? cur) (seq lines))
        lines
        (conj lines cur)))))

(defn display-lines
  "Styled answer lines of STATE to render, wrapped at WIDTH chars and
   capped at MAX-LINES. While streaming shows the tail, when done the
   head, with an indicator line for the hidden part."
  [{:keys [answer state]} width max-lines]
  (let [lines (into []
                    (mapcat (fn [{:keys [segments code-block?]}]
                              (map (fn [segs] {:segments segs :code-block? code-block?})
                                   (wrap-segments segments width))))
                    (markdown->lines answer))
        n (count lines)
        indicator (fn [text] {:segments [{:text text :style :dim}]})]
    (cond
      (<= n max-lines)
      lines

      (= :done state)
      (conj (subvec lines 0 max-lines)
            (indicator (str "... +" (- n max-lines) " lines, open the chat for the full answer")))

      :else
      (into [(indicator (str "... " (- n max-lines) " earlier lines"))]
            (subvec lines (- n max-lines) n)))))

(defn adjusted-end-line
  "END-LINE of a selection, not dragging in the extra line when the
   selection ends at the beginning of a line (whole-lines selection)."
  [start-line end-line end-at-line-start?]
  (if (and end-at-line-start? (> end-line start-line))
    (dec end-line)
    end-line))

(defn session-actions
  "Actions applicable to a session STATE, as quick-pick options."
  [{:keys [state pending-tools dead?]}]
  (cond-> []
    (and (not dead?) (= :done state))
    (conj {:id :follow-up :label "Follow-up"})

    (contains? #{:sending :running} state)
    (conj {:id :stop :label "Stop"})

    (seq pending-tools)
    (into [{:id :approve :label "Approve pending tool calls"}
           {:id :reject :label "Reject pending tool calls"}])

    (not dead?)
    (conj {:id :open-chat :label "Open backing chat"})

    :always
    (conj {:id :dismiss :label "Dismiss"})))

;;;; Session registry
;;
;; db paths per project:
;;   [:inline-chat :sessions chat-id] {:chat-id :state* :editor :inlay :doc-path :repaint!}
;;   [:inline-chat :sticky doc-path]  chat-id
;;   [:inline-chat :titles chat-id]   title

(defn ^:private session-of
  "Live session of CHAT-ID, sweeping it when its inlay died (editor
   closed, anchor line deleted)."
  [^Project project chat-id]
  (when-let [{:keys [^Inlay inlay] :as session} (db/get-in project [:inline-chat :sessions chat-id])]
    (if (.isValid inlay)
      session
      (do (db/update-in project [:inline-chat :sessions] #(dissoc % chat-id))
          nil))))

(defn ^:private busy? [{:keys [state*]}]
  (contains? #{:sending :running} (:state @state*)))

(defn ^:private clear-sticky-of-chat! [^Project project chat-id]
  (db/update-in project [:inline-chat :sticky]
                #(into {} (remove (fn [[_ id]] (= chat-id id))) %)))

(defn ^:private on-edt!
  "invoke-later! whose promise always delivers: INVOKE-FN errors are
   logged and yield nil instead of hanging derefs forever."
  [invoke-fn]
  (app-manager/invoke-later!
   {:invoke-fn (fn []
                 (try
                   (invoke-fn)
                   (catch Throwable e
                     (logger/error e)
                     nil)))}))

(defn ^:private remove-session! [^Project project chat-id]
  (when-let [{:keys [^Inlay inlay]} (session-of project chat-id)]
    (db/update-in project [:inline-chat :sessions] #(dissoc % chat-id))
    (on-edt! (fn [] (Disposer/dispose inlay)))))

;;;; Inlay rendering

(defn ^:private editor-font ^Font [^Editor editor]
  (.getFont (.getColorsScheme editor) EditorFontType/PLAIN))

(defn ^:private font-metrics ^FontMetrics [^Editor editor]
  (.getFontMetrics (.getContentComponent editor) (editor-font editor)))

;; Subtle translucent overlay behind code spans/blocks, theme agnostic.
(def ^:private code-bg
  (JBColor. (Color. (int 0) (int 0) (int 0) (int 18))
            (Color. (int 255) (int 255) (int 255) (int 18))))

(defn ^:private style->font ^Font [^Editor editor style]
  (let [scheme (.getColorsScheme editor)]
    (case style
      (:bold :header) (.getFont scheme EditorFontType/BOLD)
      :italic (.getFont scheme EditorFontType/ITALIC)
      (.getFont scheme EditorFontType/PLAIN))))

(defn ^:private style->color [^Editor editor style]
  (case style
    :dim JBColor/GRAY
    :link (JBColor/namedColor "Hyperlink.linkColor")
    (.getDefaultForeground (.getColorsScheme editor))))

(defn ^:private width-in-chars [^Editor editor]
  (let [char-width (max 1 (.charWidth (font-metrics editor) \m))]
    (max 40 (quot (- (.getWidth (.getContentComponent editor)) 24) char-width))))

(defn ^:private render-lines [^Editor editor state]
  (display-lines state (width-in-chars editor) max-display-lines))

(defn ^:private header-text [{:keys [status state pending-tools]}]
  (str "ECA: " status
       (when (or (= :done state) (seq pending-tools))
         "  [click for actions]")))

(defn ^:private create-renderer [session*]
  (proxy+ [] EditorCustomElementRenderer
    (calcWidthInPixels [_ ^Inlay inlay]
      (max 200 (- (.getWidth (.getContentComponent (.getEditor inlay))) 16)))
    (calcHeightInPixels [_ ^Inlay inlay]
      (let [editor (.getEditor inlay)]
        (* (.getLineHeight editor)
           (inc (count (render-lines editor @session*))))))
    (paint [_ ^Inlay inlay ^Graphics g ^Rectangle r _text-attributes]
      (let [editor (.getEditor inlay)
            state @session*
            component (.getContentComponent editor)
            line-height (.getLineHeight editor)
            ascent (.getAscent (font-metrics editor))
            pending? (boolean (seq (:pending-tools state)))
            accent (if pending? JBColor/ORANGE JBColor/GRAY)
            x (+ (.-x r) 8)
            y (.-y r)]
        (.setFont g (editor-font editor))
        (.setColor g accent)
        (.fillRect g (.-x r) y 2 (.-height r))
        (.drawString g ^String (header-text state) (int x) (int (+ y ascent)))
        (doseq [[i {:keys [segments code-block?]}] (map-indexed vector (render-lines editor state))]
          (let [row-y (+ y (* (inc i) line-height))
                baseline (+ row-y ascent)]
            (when code-block?
              (.setColor g code-bg)
              (.fillRect g (int x) (int row-y) (int (max 0 (- (.-width r) 20))) (int line-height)))
            (loop [segs segments
                   seg-x x]
              (when-let [[{:keys [text style]} & more] (seq segs)]
                (let [font (style->font editor style)
                      seg-width (.stringWidth (.getFontMetrics component font) ^String text)]
                  (when (and (= :code style) (not code-block?))
                    (.setColor g code-bg)
                    (.fillRect g (int seg-x) (int row-y) (int seg-width) (int line-height)))
                  (.setFont g font)
                  (.setColor g (style->color editor style))
                  (.drawString g ^String text (int seg-x) (int baseline))
                  (recur more (+ seg-x seg-width)))))))))))

;;;; Prompt plumbing

(defn ^:private editor-sticky-key [^Editor editor]
  (let [vfile (.getFile (FileDocumentManager/getInstance) (.getDocument editor))]
    (or (some-> vfile .getPath)
        (str "unsaved-" (System/identityHashCode editor)))))

(defn ^:private editor-prompt-info
  "Anchor offset, sticky key and DWIM contexts for EDITOR: the selected
   lines as a file linesRange (1-based, inclusive) or the whole file
   when there is no selection. The anchor is the start of the first
   selected line, so the inlay renders right above the selection (like
   the eca-emacs overlay). Must run on the EDT."
  [^Editor editor]
  (let [document (.getDocument editor)
        selection (.getSelectionModel editor)
        vfile (.getFile (FileDocumentManager/getInstance) document)
        path (some-> vfile .getPath)
        caret-line (.getLineNumber document (.getOffset (.getPrimaryCaret (.getCaretModel editor))))
        has-selection? (.hasSelection selection)
        start-line (if has-selection?
                     (.getLineNumber document (.getSelectionStart selection))
                     caret-line)
        end-line (if has-selection?
                   (let [raw-end (.getLineNumber document (.getSelectionEnd selection))]
                     (adjusted-end-line start-line raw-end
                                        (= (.getSelectionEnd selection)
                                           (.getLineStartOffset document raw-end))))
                   caret-line)]
    {:anchor-offset (.getLineStartOffset document start-line)
     :sticky-key (or path (str "unsaved-" (System/identityHashCode editor)))
     :contexts (if path
                 [(cond-> {:type "file" :path path}
                    has-selection? (assoc :linesRange {:start (inc start-line)
                                                       :end (inc end-line)}))]
                 [])}))

(defn ^:private read-prompt
  "Ask the user for the inline prompt text, describing the target chat
   as DESC. Returns the trimmed text or nil when cancelled/blank."
  [^Project project desc]
  (let [text @(on-edt! (fn []
                         (Messages/showInputDialog
                          project
                          (str "Inline prompt (" desc "):")
                          "ECA Inline Prompt"
                          (Messages/getQuestionIcon))))]
    (some-> text string/trim not-empty)))

(defn ^:private fail-session!
  "Reflect a failed prompt request on the session. When CREATED? (the
   request that failed created the chat), mark it dead and clear the
   sticky binding so the next prompt picks (and re-forks) again."
  [^Project project chat-id created? message]
  (logger/warn "Inline chat prompt failed:" chat-id message)
  (when-let [{:keys [state* repaint!]} (session-of project chat-id)]
    (swap! state* #(cond-> (assoc % :status message :state :done :pending-tools [])
                     created? (assoc :dead? true)))
    (when created?
      (clear-sticky-of-chat! project chat-id))
    (repaint!)))

(defn ^:private send-prompt!
  "Send TEXT to CHAT-ID via chat/inlinePrompt. Blocking, so call it from
   a worker thread."
  [^Project project client chat-id text contexts source-chat-id created?]
  (try
    (let [params (cond-> {:chatId chat-id
                          :message text
                          :contexts (vec contexts)}
                   source-chat-id (assoc :sourceChatId source-chat-id))
          result @(api/request! client [:chat/inlinePrompt params])]
      (logger/info "Inline chat prompt response:" result)
      (cond
        (:error result)
        (fail-session! project chat-id created?
                       (str "Error: " (or (-> result :error :message) "unknown error")))

        ;; Param validation failures and internal server errors come
        ;; back as a normal response with an error status.
        (= "error" (:status result))
        (fail-session! project chat-id created? "Prompt failed, check the ECA server logs")))
    (catch Throwable e
      (logger/error e)
      (fail-session! project chat-id created? (str "Error: " (.getMessage e))))))

;;;; Session actions

(defn ^:private notify-busy! [^Project project]
  (notification/show-notification! {:project project
                                    :type :warning
                                    :title "ECA inline chat"
                                    :message "Inline chat is busy, stop it first."}))

(defn ^:private follow-up! [^Project project chat-id]
  (when-let [{:keys [state* ^Editor editor repaint!]} (session-of project chat-id)]
    (if (busy? {:state* state*})
      (notify-busy! project)
      (when-let [client (api/connected-client project)]
        (future
          (try
            (let [desc (or (db/get-in project [:inline-chat :titles chat-id]) "follow-up")]
              (when-let [text (read-prompt project desc)]
                (let [contexts (if (.isDisposed editor)
                                 []
                                 (:contexts @(on-edt! (fn [] (editor-prompt-info editor)))))]
                  (swap! state* assoc :answer "" :status "Waiting model..." :state :sending)
                  (repaint!)
                  (send-prompt! project client chat-id text contexts nil false))))
            (catch Throwable e
              (logger/error e))))))))

(defn ^:private stop! [^Project project chat-id]
  (when-let [{:keys [state* repaint!]} (session-of project chat-id)]
    (when-let [client (api/connected-client project)]
      ;; Plain notify: the server ignores stops for non-running chats.
      (api/notify! client [:chat/promptStop {:chatId chat-id}])
      (swap! state* assoc :status "Stopping...")
      (repaint!))))

(defn ^:private approve-all! [^Project project chat-id]
  (when-let [{:keys [state*]} (session-of project chat-id)]
    (when-let [client (api/connected-client project)]
      (doseq [[tool-call-id _] (:pending-tools @state*)]
        (api/notify! client [:chat/toolCallApprove {:chatId chat-id
                                                    :toolCallId tool-call-id}])))))

(defn ^:private reject-all! [^Project project chat-id]
  (when-let [{:keys [state*]} (session-of project chat-id)]
    (when-let [client (api/connected-client project)]
      (doseq [[tool-call-id _] (:pending-tools @state*)]
        (api/notify! client [:chat/toolCallReject {:chatId chat-id
                                                   :toolCallId tool-call-id}])))))

(defn ^:private open-chat!
  "Select the backing chat in the ECA tool window. Resolved at runtime to
   avoid a require cycle with the webview namespace."
  [^Project project chat-id]
  (when-let [select-chat! (requiring-resolve 'dev.eca.eca-intellij.webview/select-chat!)]
    (select-chat! project chat-id)))

(defn ^:private dismiss!
  "Dismiss the session inlay, keeping the chat sticky for the file."
  [^Project project chat-id]
  (remove-session! project chat-id))

(defn ^:private show-session-actions! [^Project project chat-id]
  (when-let [{:keys [state*]} (session-of project chat-id)]
    (let [options (session-actions @state*)]
      (future
        (try
          (when-let [choice @(editor/quick-pick options {:title "Inline chat actions"})]
            (case (:id choice)
              :follow-up (follow-up! project chat-id)
              :stop (stop! project chat-id)
              :approve (approve-all! project chat-id)
              :reject (reject-all! project chat-id)
              :open-chat (open-chat! project chat-id)
              :dismiss (dismiss! project chat-id)
              nil))
          (catch Throwable e
            (logger/error e)))))))

;;;; Session creation

(defn ^:private add-click-listener!
  "Open the actions popup when INLAY is clicked. The listener lives as
   long as the editor (passing the inlay as parent disposable would
   leave a leaked Disposer root when the editor closes first)."
  [^Project project ^Editor editor ^Inlay inlay chat-id]
  (.addEditorMouseListener editor
                           (proxy+ [] EditorMouseListener
                             (mouseClicked [_this ^EditorMouseEvent e]
                               (when (= inlay (.getInlay e))
                                 (show-session-actions! project chat-id))))))

(defn ^:private make-repaint!
  "Repaint fn for INLAY, callable from any thread. Coalesces bursts via
   a dirty flag: at most one EDT update is in flight at a time."
  [^Inlay inlay]
  (let [pending?* (atom false)]
    (fn []
      (when (compare-and-set! pending?* false true)
        (on-edt! (fn []
                   (reset! pending?* false)
                   (when (.isValid inlay)
                     (.update inlay))))))))

(defn ^:private create-session!
  "Create (or re-anchor) the inline session for CHAT-ID, rendering above
   ANCHOR-OFFSET's line, and make it sticky for STICKY-KEY. Must run on
   the EDT. Returns the session."
  [^Project project ^Editor editor chat-id anchor-offset sticky-key]
  (when-let [{:keys [^Inlay inlay]} (session-of project chat-id)]
    ;; One inlay per chat: a new prompt re-anchors at the cursor.
    (Disposer/dispose inlay))
  (let [state* (atom {:answer ""
                      :status "Waiting model..."
                      :state :sending
                      :pending-tools []})
        inlay (.addBlockElement (.getInlayModel editor)
                                (int anchor-offset)
                                false
                                true
                                0
                                ^EditorCustomElementRenderer (create-renderer state*))
        session {:chat-id chat-id
                 :state* state*
                 :editor editor
                 :inlay inlay
                 :doc-path sticky-key
                 :repaint! (make-repaint! inlay)}]
    (add-click-listener! project editor inlay chat-id)
    (db/assoc-in project [:inline-chat :sessions chat-id] session)
    (db/assoc-in project [:inline-chat :sticky sticky-key] chat-id)
    (logger/info "Inline chat session created:" chat-id "at offset" anchor-offset)
    session))

;;;; Prompt flows

(defn ^:private pick-target-chat
  "Ask which chat to use: fork an existing chat (from chat/list) or start
   fresh. Returns {:source-chat-id id? :desc s} or nil when cancelled.
   Blocking, so call it from a worker thread."
  [client]
  (let [chats (try
                (vec (:chats @(api/request! client [:chat/list {:limit 20}])))
                (catch Throwable _ []))]
    (if (empty? chats)
      {:desc "new chat"}
      (let [options (into [{:id :new :label "New inline chat"}]
                          (map (fn [{:keys [id title kind]}]
                                 {:id id
                                  :title (or title id)
                                  :label (str (or title id)
                                              (when (= "inline" kind) "  [inline]")
                                              "  (" (subs id 0 (min 8 (count id))) ")")}))
                          chats)
            choice @(editor/quick-pick options {:title "Chat for the inline prompt"})]
        (cond
          (nil? choice) nil
          (= :new (:id choice)) {:desc "new chat"}
          :else {:source-chat-id (:id choice)
                 :desc (str "fork of " (:title choice))})))))

(defn inline-prompt!
  "Ask ECA a question from EDITOR, streaming the answer into an inlay.
   On first use in a file, asks which chat to use: an existing chat has
   its history forked server-side into the inline chat, `New inline
   chat' starts fresh. The chat then sticks to the file; FORCE-SELECT?
   re-asks. Entry point for the Eca.InlinePrompt action (EDT)."
  [^Project project ^Editor editor {:keys [force-select?]}]
  (if-let [client (api/connected-client project)]
    (let [{:keys [anchor-offset sticky-key contexts]} (editor-prompt-info editor)
          _ (when force-select?
              (db/update-in project [:inline-chat :sticky] #(dissoc % sticky-key)))
          sticky-id (db/get-in project [:inline-chat :sticky sticky-key])
          sticky-session (some->> sticky-id (session-of project))]
      (if (and sticky-session (busy? sticky-session))
        (notify-busy! project)
        (future
          (try
            (when-let [target (if sticky-id
                                {:desc (or (db/get-in project [:inline-chat :titles sticky-id])
                                           "same chat")}
                                (pick-target-chat client))]
              (when-let [text (read-prompt project (:desc target))]
                (let [chat-id (or sticky-id (str (UUID/randomUUID)))
                      created? (nil? sticky-id)]
                  (if @(on-edt! (fn []
                                  (create-session! project editor chat-id
                                                   anchor-offset sticky-key)))
                    (send-prompt! project client chat-id text contexts
                                  (:source-chat-id target) created?)
                    (notification/show-notification!
                     {:project project
                      :type :error
                      :title "ECA inline chat"
                      :message "Could not create the inline session, check the IDE logs."})))))
            (catch Throwable e
              (logger/error e))))))
    (notification/show-notification! {:project project
                                      :type :error
                                      :title "ECA"
                                      :message "ECA server is not running yet."})))

(defn show-actions!
  "Show the actions popup for the inline session of EDITOR's file, or
   start an inline prompt when there is none. Entry point for the
   Eca.InlineChatActions action (EDT)."
  [^Project project ^Editor editor]
  (let [sticky-key (editor-sticky-key editor)
        chat-id (or (db/get-in project [:inline-chat :sticky sticky-key])
                    ;; Dead sessions clear their sticky binding but keep
                    ;; the inlay until dismissed: find them by file.
                    (some (fn [[id session]]
                            (when (= sticky-key (:doc-path session)) id))
                          (db/get-in project [:inline-chat :sessions])))]
    (if (and chat-id (session-of project chat-id))
      (show-session-actions! project chat-id)
      (inline-prompt! project editor {}))))

;;;; Server notification handlers (fanned out from the webview ns)

;; All handlers swallow their own errors: they run inside the LSP
;; notification pipeline, where a throw would kill the connection.
;; Params keys are kebab-cased: lsp4clj converts the wire's camelCase
;; (chatId -> :chat-id) on receive.

(defn on-content-received [^Project project {:keys [chat-id parent-chat-id role content]}]
  (try
    ;; Subagent contents belong to the webview rendering, not the inline answer.
    (when-not parent-chat-id
      (when-let [{:keys [state* repaint!]} (session-of project chat-id)]
        (let [[old _] (swap-vals! state* #(-> %
                                              (transition {:role role :content content})
                                              (assoc :got-events? true)))]
          (when-not (:got-events? old)
            (logger/info "Inline chat receiving contents:" chat-id)))
        (repaint!)))
    (catch Throwable e
      (logger/error e))))

(defn on-status-changed [^Project project {:keys [chat-id status]}]
  (try
    (when-let [{:keys [state* repaint!]} (session-of project chat-id)]
      (case status
        "running" (do (swap! state* assoc :state :running)
                      (repaint!))
        ;; Safety net: mark the turn done even if the progress finished
        ;; content was missed (e.g. after a stop).
        "idle" (do (swap! state* (fn [{:keys [state] :as s}]
                                   (if (= :done state)
                                     s
                                     (assoc s :status "Done" :state :done :pending-tools []))))
                   (repaint!))
        nil))
    (catch Throwable e
      (logger/error e))))

(defn on-chat-opened [^Project project {:keys [chat-id title]}]
  (try
    (when (and title (session-of project chat-id))
      (db/assoc-in project [:inline-chat :titles chat-id] title))
    (catch Throwable e
      (logger/error e))))

(defn on-chat-deleted [^Project project {:keys [chat-id]}]
  (try
    (db/update-in project [:inline-chat :titles] #(dissoc % chat-id))
    (remove-session! project chat-id)
    ;; Clear stickiness even when the inlay was already dismissed, so the
    ;; next prompt asks for a chat again instead of reviving the dead id.
    (clear-sticky-of-chat! project chat-id)
    (catch Throwable e
      (logger/error e))))

(defn on-ask-question [^Project project {:keys [chat-id]}]
  ;; Questions are answered in the tool window chat; surface a hint on
  ;; the inlay so the user knows the turn is waiting on them.
  (try
    (when-let [{:keys [state* repaint!]} (session-of project chat-id)]
      (swap! state* assoc :status "Question pending, open the chat to answer")
      (repaint!))
    (catch Throwable e
      (logger/error e))))
