(ns dev.eca.eca-intellij.editor-actions
  "Helpers for the Settings → Global Config webview tab.

   Mirrors `src/editor-actions.ts` in eca-vscode so every client
   resolves the same config path (ECA_CONFIG_PATH env > XDG_CONFIG_HOME
   > platform default) and applies the same validation + atomic-write
   guarantees. Keeps the behaviour identical across hosts so users
   don't get surprised switching editors."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io])
  (:import
   [java.io File]
   [java.nio.file CopyOption Files StandardCopyOption]
   [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

;; ── Global config path resolution ──────────────────────────────────

(defn ^:private linux-default-config-path ^File []
  (io/file (System/getProperty "user.home") ".config" "eca" "config.json"))

(defn get-global-config-path
  "Resolve the absolute path to the ECA global config JSON file.

   Resolution order (first match wins):
     1. `ECA_CONFIG_PATH` env var (absolute path) — honored as-is.
     2. `$XDG_CONFIG_HOME/eca/config.json` when the var is set.
     3. Platform default:
        - macOS  : `~/Library/Application Support/eca/config.json`
        - Windows: `%APPDATA%\\eca\\config.json` (falls back to
          `~/.config/eca/config.json` when APPDATA is absent — which
          realistically never happens, but the fallback matches the
          other clients verbatim).
        - Other  : `~/.config/eca/config.json`

   Does not touch the filesystem. Creation is the caller's
   responsibility (see `ensure-global-config-exists!`)."
  ^File []
  (let [override (System/getenv "ECA_CONFIG_PATH")
        xdg (System/getenv "XDG_CONFIG_HOME")
        os-name (clojure.string/lower-case (or (System/getProperty "os.name") ""))
        home (System/getProperty "user.home")]
    (cond
      (and override (pos? (count (clojure.string/trim override))))
      (io/file override)

      (and xdg (pos? (count (clojure.string/trim xdg))))
      (io/file xdg "eca" "config.json")

      (clojure.string/includes? os-name "mac")
      (io/file home "Library" "Application Support" "eca" "config.json")

      (clojure.string/includes? os-name "win")
      (let [appdata (System/getenv "APPDATA")]
        (if (and appdata (pos? (count (clojure.string/trim appdata))))
          (io/file appdata "eca" "config.json")
          (linux-default-config-path)))

      :else
      (linux-default-config-path))))

(def ^:private empty-global-config "{}\n")

(defn ensure-global-config-exists!
  "Make sure the config file exists (create parent dirs and seed with
   `{}` if missing). Returns the file."
  ^File []
  (let [f (get-global-config-path)]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (when-not (.exists f)
      (spit f empty-global-config))
    f))

;; ── JSONC stripping ────────────────────────────────────────────────
;;
;; Cheshire, like every JVM JSON parser we have on the classpath,
;; doesn't accept JSONC's `//` `/* */` comments or trailing commas.
;; The ECA server, eca-desktop and eca-vscode DO accept them — users
;; will discover the inconsistency the first time they paste a
;; comment-laden config into this editor, so we strip JSONC-isms down
;; to strict JSON here before validating.
;;
;; Single-pass state machine over the source. Handles:
;;   * `//` line comments      (erased, newline preserved for line numbers)
;;   * `/* ... */` block comments (erased)
;;   * string literals with backslash escapes (comments inside strings
;;     stay verbatim — otherwise a URL like "http://…" would get eaten)
;;   * trailing commas before `}` / `]` (erased)
;;
;; Implementation choice: explicit (case state …) with char indices,
;; not regex. Regex over multi-state input is a footgun and the perf
;; difference is irrelevant at 1 MB input.

(defn ^:private strip-jsonc ^String [^String source]
  (let [len (.length source)
        out (StringBuilder. len)]
    (loop [i 0
           state :normal]       ;; :normal | :in-string | :line-comment | :block-comment
      (if (>= i len)
        (.toString out)
        (let [c (.charAt source i)
              next-c (when (< (inc i) len) (.charAt source (inc i)))]
          (case state
            :normal
            (cond
              (and (= c \/) (= next-c \/)) (recur (+ i 2) :line-comment)
              (and (= c \/) (= next-c \*)) (recur (+ i 2) :block-comment)
              (= c \")                     (do (.append out c) (recur (inc i) :in-string))
              :else                        (do (.append out c) (recur (inc i) :normal)))

            :in-string
            (cond
              ;; Backslash escapes — copy both chars verbatim; the escape
              ;; target is never a closing quote even if it looks like one.
              (= c \\)
              (do (.append out c)
                  (when next-c (.append out next-c))
                  (recur (+ i (if next-c 2 1)) :in-string))

              (= c \")
              (do (.append out c) (recur (inc i) :normal))

              :else
              (do (.append out c) (recur (inc i) :in-string)))

            :line-comment
            (if (= c \newline)
              (do (.append out c) (recur (inc i) :normal))
              (recur (inc i) :line-comment))

            :block-comment
            (if (and (= c \*) (= next-c \/))
              (recur (+ i 2) :normal)
              (recur (inc i) :block-comment))))))))

(defn ^:private strip-trailing-commas ^String [^String source]
  ;; Remove commas that are followed (after optional whitespace and
  ;; comments-already-gone) by `]` or `}`. We run this AFTER strip-jsonc
  ;; so comments don't complicate the whitespace scan. Still has to
  ;; respect strings — a comma before `}` inside a string is not trailing.
  (let [len (.length source)
        out (StringBuilder. len)]
    (loop [i 0
           in-string? false]
      (if (>= i len)
        (.toString out)
        (let [c (.charAt source i)]
          (cond
            (and (not in-string?) (= c \"))
            (do (.append out c) (recur (inc i) true))

            (and in-string? (= c \\))
            (do (.append out c)
                (when (< (inc i) len) (.append out (.charAt source (inc i))))
                (recur (+ i 2) in-string?))

            (and in-string? (= c \"))
            (do (.append out c) (recur (inc i) false))

            in-string?
            (do (.append out c) (recur (inc i) in-string?))

            (= c \,)
            ;; Scan ahead through whitespace for `]` or `}`. If found,
            ;; drop this comma; otherwise emit it.
            (let [j (loop [k (inc i)]
                      (if (and (< k len) (Character/isWhitespace (.charAt source k)))
                        (recur (inc k))
                        k))]
              (if (and (< j len) (contains? #{\] \}} (.charAt source j)))
                (recur (inc i) in-string?)   ;; drop trailing comma
                (do (.append out c) (recur (inc i) in-string?))))

            :else
            (do (.append out c) (recur (inc i) in-string?))))))))

(defn ^:private valid-jsonc?
  "True when `source` is valid JSONC: strip comments + trailing commas,
   then feed to cheshire. Returns [ok? error-message-or-nil]. An empty
   or whitespace-only source is considered valid (lets users save an
   empty file to create a seed)."
  [^String source]
  (if (clojure.string/blank? source)
    [true nil]
    (try
      (json/parse-string (-> source strip-jsonc strip-trailing-commas))
      [true nil]
      (catch Exception e
        [false (or (.getMessage e) (str e))]))))

;; ── Read / write ───────────────────────────────────────────────────

(defn read-global-config
  "Read the ECA global config from disk. Never throws: returns
   `{:contents :path :exists}` with `:contents` empty when the file
   doesn't exist yet, so the UI can seed a blank editor without a
   scary error banner. IO errors surface as `:error`."
  []
  (let [f (get-global-config-path)
        path (.getAbsolutePath f)]
    (if-not (.exists f)
      {:contents "" :path path :exists false}
      (try
        {:contents (slurp f) :path path :exists true}
        (catch Exception e
          {:contents "" :path path :exists true
           :error (or (.getMessage e) (str e))})))))

;; 1 MB cap — matches the TypeScript port. Hand-authored configs are
;; realistically a few KB; rejecting here prevents a runaway renderer
;; payload from wedging the host.
(def ^:private max-global-config-bytes 1048576)

(defn write-global-config
  "Validate `contents` parses as JSONC, then write atomically to the
   global config path via tmp-file + ATOMIC_MOVE. Returns
   `{:ok true  :path <path>}` on success, `{:ok false :error <msg>}`
   otherwise. The on-disk file is untouched on validation failure."
  [{:keys [contents] :or {contents ""}}]
  (let [^String contents contents
        byte-size (count (.getBytes contents "UTF-8"))]
    (cond
      (> byte-size max-global-config-bytes)
      {:ok false :error (format "Config file too large (max %d bytes, got %d)"
                                max-global-config-bytes byte-size)}

      :else
      (let [[ok? err] (valid-jsonc? contents)]
        (if-not ok?
          {:ok false :error (str "Invalid JSONC: " err)}
          (let [f (get-global-config-path)
                path (.getAbsolutePath f)]
            (try
              (when-let [parent (.getParentFile f)]
                (.mkdirs parent))
              (let [tmp (File/createTempFile "eca-config-"
                                             ".json.tmp"
                                             (.getParentFile f))]
                (spit tmp contents)
                (Files/move (.toPath tmp)
                            (.toPath f)
                            (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                    StandardCopyOption/REPLACE_EXISTING]))
                {:ok true :path path})
              (catch Exception e
                {:ok false :error (or (.getMessage e) (str e))}))))))))
