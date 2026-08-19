(ns dev.eca.eca-intellij.editor-nav
  "Answers the ECA server's editor/getDefinition and editor/getReferences
   requests with IntelliJ's PSI, so the LLM navigates code using the IDE's
   own indexes.

   Wire positions are 1-based (`character` counts UTF-16 code units, which
   IntelliJ Documents use natively); IntelliJ lines are 0-based, so only a
   +-1 conversion happens at this boundary.

   IntelliJ has no per-language server lifecycle: dumb mode (indexing in
   progress) is the analog of a starting language server and is answered
   as 'starting' so the ECA server re-polls; files IntelliJ parses as
   plain text or binary (no language plugin) are answered as 'no-server'."
  (:require
   [com.github.ericdallo.clj4intellij.logger :as logger])
  (:import
   [com.intellij.openapi.application ApplicationManager]
   [com.intellij.openapi.editor Document]
   [com.intellij.openapi.fileEditor FileDocumentManager]
   [com.intellij.openapi.project DumbService IndexNotReadyException Project]
   [com.intellij.openapi.util Computable TextRange]
   [com.intellij.openapi.vfs LocalFileSystem VirtualFile]
   [com.intellij.psi PsiBinaryFile PsiElement PsiFile PsiManager PsiNameIdentifierOwner PsiPlainTextFile PsiPolyVariantReference PsiReference ResolveResult]
   [com.intellij.psi.search.searches ReferencesSearch]
   [com.intellij.psi.util PsiTreeUtil]
   [com.intellij.util Processor Query]
   [java.io File]
   [java.net URI]))

(set! *warn-on-reflection* true)

(def ^:private max-references
  "Bounds ReferencesSearch runtime, the search blocks the client message
   pipeline. The ECA server shows at most 100 locations anyway."
  200)

(defn ^:private position->offset
  "1-based wire POSITION -> document offset, clamped into the document."
  [^Document document {:keys [line character]}]
  (if (zero? (.getLineCount document))
    0
    (let [line0 (-> (dec (long (or line 1))) (max 0) (min (dec (.getLineCount document))))
          line-start (.getLineStartOffset document (int line0))
          line-end (.getLineEndOffset document (int line0))]
      (min (+ line-start (max 0 (dec (long (or character 1))))) line-end))))

(defn ^:private offset->position
  "Document OFFSET -> 1-based wire position."
  [^Document document offset]
  (let [offset (max 0 (min (long offset) (.getTextLength document)))
        line (.getLineNumber document (int offset))]
    {:line (inc line)
     :character (inc (- offset (.getLineStartOffset document (int line))))}))

(defn ^:private vfile->uri [^VirtualFile vfile]
  (str (.toURI (File. (.getPath vfile)))))

(defn ^:private range->location [^VirtualFile vfile ^Document document ^TextRange range]
  {:uri (vfile->uri vfile)
   :range {:start (offset->position document (.getStartOffset range))
           :end (offset->position document (.getEndOffset range))}})

(defn ^:private element->location
  "Location of ELEMENT's declaration, preferring its name identifier range
   (the symbol name) over the whole element range, and source over
   compiled via the navigation element."
  [^PsiElement element]
  (let [element (.getNavigationElement element)
        vfile (some-> element .getContainingFile .getVirtualFile)
        document (when vfile (.getDocument (FileDocumentManager/getInstance) vfile))
        range (when element
                (or (when (instance? PsiNameIdentifierOwner element)
                      (some-> (.getNameIdentifier ^PsiNameIdentifierOwner element) .getTextRange))
                    (.getTextRange element)))]
    (when (and vfile document range)
      (range->location vfile document range))))

(defn ^:private reference->location [^PsiReference ref]
  (let [element (.getElement ref)
        vfile (some-> element .getContainingFile .getVirtualFile)
        document (when vfile (.getDocument (FileDocumentManager/getInstance) vfile))
        element-range (some-> element .getTextRange)
        range (when element-range
                (some-> (.getRangeInElement ref)
                        (.shiftRight (.getStartOffset ^TextRange element-range))))]
    (when (and vfile document range)
      (range->location vfile document range))))

(defn ^:private declaration-at
  "The named declaration whose name identifier spans OFFSET, if any."
  [^PsiFile psi-file offset]
  (when-let [element (.findElementAt psi-file (int offset))]
    (when-let [^PsiNameIdentifierOwner owner (PsiTreeUtil/getParentOfType element PsiNameIdentifierOwner)]
      (when-let [^TextRange name-range (some-> (.getNameIdentifier owner) .getTextRange)]
        (when (.containsOffset name-range (int offset))
          owner)))))

(defn ^:private definition-targets
  "PSI elements the symbol at OFFSET resolves to: the resolved reference
   target(s) when the caret is on a usage, the declaration itself when
   the caret is on its own name."
  [^PsiFile psi-file offset]
  (let [ref (.findReferenceAt psi-file (int offset))
        resolved (when ref
                   (if (instance? PsiPolyVariantReference ref)
                     (into []
                           (keep (fn [^ResolveResult result] (.getElement result)))
                           (.multiResolve ^PsiPolyVariantReference ref false))
                     (some-> (.resolve ref) vector)))]
    (or (not-empty (vec resolved))
        (some-> (declaration-at psi-file offset) vector)
        [])))

(defn ^:private search-references [^PsiElement target]
  (let [found (java.util.ArrayList.)]
    (.forEach ^Query (ReferencesSearch/search target)
              (reify Processor
                (process [_ ref]
                  (.add found ref)
                  (< (.size found) max-references))))
    (vec found)))

(defn ^:private nav-request
  [^Project project uri position locations-fn]
  (try
    (cond
      (or (nil? uri) (nil? position))
      {:status "error" :message "Missing uri or position"}

      ;; Indexing in progress: PSI resolve is unavailable, the ECA server
      ;; re-polls until indexes are ready.
      (.isDumb (DumbService/getInstance project))
      {:status "starting"}

      :else
      (let [path (.getPath (URI. ^String uri))
            vfile (.findFileByPath (LocalFileSystem/getInstance) path)]
        (if-not vfile
          {:status "error" :message (str "File not found: " uri)}
          (.runReadAction
           (ApplicationManager/getApplication)
           (reify Computable
             (compute [_]
               (let [document (.getDocument (FileDocumentManager/getInstance) vfile)
                     psi-file (.findFile (PsiManager/getInstance project) vfile)]
                 (cond
                   (nil? document)
                   {:status "error" :message (str "Cannot read document: " uri)}

                   (or (nil? psi-file)
                       (instance? PsiPlainTextFile psi-file)
                       (instance? PsiBinaryFile psi-file))
                   {:status "no-server" :message "No language support in IntelliJ for this file"}

                   :else
                   {:status "success"
                    :locations (locations-fn psi-file (position->offset document position))}))))))))
    (catch IndexNotReadyException _
      {:status "starting"})
    (catch Exception e
      (logger/warn "Error computing editor navigation:" (.getMessage e))
      {:status "error" :message (or (ex-message e) (str e))})))

(defn get-definition
  "Handles the editor/getDefinition server request."
  [^Project project {:keys [uri position]}]
  (nav-request project uri position
               (fn [psi-file offset]
                 (into [] (keep element->location) (definition-targets psi-file offset)))))

(defn get-references
  "Handles the editor/getReferences server request. IntelliJ's
   ReferencesSearch returns usages only, so the declaration location is
   prepended unless include-declaration is explicitly false."
  [^Project project {:keys [uri position include-declaration]}]
  (nav-request project uri position
               (fn [psi-file offset]
                 (if-let [target (first (definition-targets psi-file offset))]
                   (let [refs (->> (search-references target)
                                   (keep reference->location)
                                   (sort-by (juxt :uri
                                                  #(get-in % [:range :start :line])
                                                  #(get-in % [:range :start :character]))))
                         declaration (when-not (false? include-declaration)
                                       (element->location target))]
                     (vec (distinct (concat (some-> declaration vector) refs))))
                   []))))
