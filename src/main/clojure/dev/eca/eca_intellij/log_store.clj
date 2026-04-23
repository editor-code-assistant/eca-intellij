(ns dev.eca.eca-intellij.log-store
  "In-memory ring buffer + subscriber fan-out for ECA server stderr and
   lifecycle messages.

   Mirrors eca-vscode's `src/log-store.ts` (and eca-desktop's
   `src/main/log-store.ts`) so the webview's Settings → Logs tab
   consumes the same LogEntry shape across every client.

   Storage lives inside the per-project db under `:log-entries` (a
   capped vector, oldest first) and `:log-subscribers` (an atom of
   keyed fns). We don't maintain a separate log file: IntelliJ's
   `server_logs.clj` already surfaces the full stderr string as a
   LightVirtualFile, which is the 'file on disk' surface most users
   reach for, and duplicating it on disk would just diverge. The
   file-sink feature in the desktop LogStore exists for bug-report
   attachments — IntelliJ users can instead share the 'eca-stderr.txt'
   editor buffer."
  (:require
   [dev.eca.eca-intellij.db :as db])
  (:import
   [com.intellij.openapi.project Project]))

(set! *warn-on-reflection* true)

;; ── Level inference ─────────────────────────────────────────────────
;;
;; Same heuristic as the TypeScript port. Conservative — we'd rather
;; under-flag than spam the webview's error badge. `Exception` has no
;; leading `\b` so Java-style class names (RuntimeException, …) also
;; get flagged; \b fails when the preceding char is a word-char.

(def ^:private error-pattern #"\bERROR\b|\bFATAL\b|\bTraceback\b|Exception\b")
(def ^:private non-zero-exit-pattern #"exited with code (?!0\b)")
(def ^:private failed-pattern #"(?i)\bFailed to\b")

(defn infer-level [^String text]
  (cond
    (re-find error-pattern text) :error
    (re-find non-zero-exit-pattern text) :error
    (re-find failed-pattern text) :error
    :else :info))

;; ── Ring-buffer state ──────────────────────────────────────────────
;;
;; `:log-entries`     → vector of LogEntry maps, oldest first, capped.
;; `:log-next-seq`    → monotonically increasing id for stable sort
;;                      + subscriber dedup on the webview side.
;; `:log-subscribers` → map of fn-key → (fn [entry] ...).

(def ^:private max-entries 5000)

(defn snapshot
  "Immutable snapshot of the current ring buffer, oldest first."
  [^Project project]
  (or (db/get-in project [:log-entries]) []))

(defn clear!
  "Drop all in-memory entries for this project. Does NOT touch the
   `server-stderr-string` surface used by `server_logs.clj` — the
   editor buffer remains useful for bug reports even after a UI clear."
  [^Project project]
  (db/assoc-in project [:log-entries] []))

(defn subscribe!
  "Register a listener for each new entry under `key`. Re-registering
   the same key replaces the previous fn. Returns the fn (for easy
   unsubscription)."
  [^Project project key listener-fn]
  (db/assoc-in project [:log-subscribers key] listener-fn)
  listener-fn)

(defn unsubscribe!
  "Remove the listener stored under `key`. No-op if unknown."
  [^Project project key]
  (db/update-in project [:log-subscribers] #(dissoc % key)))

(defn- trim-to-cap
  "Trim the front of `entries` so its size is at most `max-entries`.
   Cheapest correct operation for a soft-cap ring; at steady state we
   drop one per append."
  [entries]
  (if (> (count entries) max-entries)
    (subvec entries (- (count entries) max-entries))
    entries))

(defn append!
  "Append a new entry. Required keys on `partial`: `:source` (`:server`
   or `:desktop`) and `:text`. `:ts`, `:seq` and `:level` default if
   omitted; `:session-id` is optional.

   Subscriber callbacks are invoked synchronously. A throwing
   subscriber is logged (to stderr) and skipped — it MUST NOT poison
   sibling subscribers or stop the entry from being buffered."
  [^Project project {:keys [ts source level text session-id]
                     :or {ts (System/currentTimeMillis)}}]
  (let [seq (or (db/get-in project [:log-next-seq]) 1)
        entry {:ts ts
               :seq seq
               :session-id session-id
               :source source
               :level (or level (infer-level (str text)))
               :text (str text)}]
    (db/assoc-in project [:log-next-seq] (inc seq))
    (db/update-in project [:log-entries] (fn [xs] (trim-to-cap (conj (or xs []) entry))))
    (doseq [[_ listener-fn] (db/get-in project [:log-subscribers])]
      (try
        (listener-fn entry)
        (catch Throwable t
          (binding [*out* *err*]
            (println "[log-store] subscriber threw:" (.getMessage t))))))
    entry))
