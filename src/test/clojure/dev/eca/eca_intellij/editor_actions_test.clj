(ns dev.eca.eca-intellij.editor-actions-test
  "Unit tests for the global-config JSONC strip/validate/write helpers.

   The same logic ships in eca-vscode and eca-desktop; behavior drift
   here will surprise users switching editors. The 1 MB write cap, the
   trailing-comma + comment handling, and the atomic write pattern are
   all explicit acceptance criteria of the issue (88fca15) that wired
   the Settings -> Global Config tab in the first place."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [dev.eca.eca-intellij.editor-actions :as editor-actions])
  (:import
   [java.io File]))

(deftest get-global-config-path-respects-xdg-via-classpath-call
  (testing "Smoke: returns *some* File (we cannot mutate env from JVM)."
    (is (instance? File (editor-actions/get-global-config-path)))))

;; Tests below exercise the public write/read flow rather than the
;; private strip helpers directly.

(defn ^:private tmp-config-target ^File []
  (File/createTempFile "eca-config-test-" ".json"))

(defmacro ^:private with-isolated-config
  "Re-point `get-global-config-path` to a fresh tmp file for the body."
  [config-path-sym & body]
  `(let [~config-path-sym (tmp-config-target)]
     (.delete ~config-path-sym)
     (with-redefs [editor-actions/get-global-config-path (fn [] ~config-path-sym)]
       (try
         ~@body
         (finally
           (when (.exists ~config-path-sym) (.delete ~config-path-sym)))))))

(deftest write-accepts-empty-content
  (with-isolated-config target
    (let [result (editor-actions/write-global-config {:contents ""})]
      (is (:ok result))
      (is (= "" (slurp target))))))

(deftest write-accepts-trailing-commas
  (testing "JSONC accepts trailing commas before }/]; strip removes them"
    (with-isolated-config target
      (let [src "{\n  \"a\": 1,\n  \"b\": [1, 2,],\n}"
            result (editor-actions/write-global-config {:contents src})]
        (is (:ok result))
        (is (= src (slurp target))
            "file content stays verbatim; only the validation pass strips")))))

(deftest write-accepts-line-comments
  (with-isolated-config target
    (let [src "// top-level\n{\n  // inline\n  \"a\": 1\n}"
          result (editor-actions/write-global-config {:contents src})]
      (is (:ok result))
      (is (= src (slurp target))))))

(deftest write-accepts-block-comments
  (with-isolated-config target
    (let [src "/* multi\nline\ncomment */ { \"a\": 1 }"
          result (editor-actions/write-global-config {:contents src})]
      (is (:ok result)))))

(deftest write-preserves-urls-with-double-slashes-inside-strings
  (testing "A bare `//` inside a JSON string must NOT be treated as a
            line comment -- otherwise URLs in config values get
            mangled. Regression target for the strip-state machine."
    (with-isolated-config target
      (let [src "{ \"webhook\": \"https://example.com/path\" }"
            result (editor-actions/write-global-config {:contents src})]
        (is (:ok result))
        (is (= "https://example.com/path"
               (get (json/parse-string (slurp target)) "webhook"))
            "URL must round-trip intact through strip + write")))))

(deftest write-preserves-escaped-quotes-in-strings
  (with-isolated-config target
    (let [src "{ \"msg\": \"he said \\\"hi\\\"\" }"
          result (editor-actions/write-global-config {:contents src})]
      (is (:ok result))
      (is (= "he said \"hi\""
             (get (json/parse-string (slurp target)) "msg"))))))

(deftest write-rejects-invalid-jsonc
  (with-isolated-config target
    (let [result (editor-actions/write-global-config
                  {:contents "{ this is not json"})]
      (is (false? (:ok result)))
      (is (re-find #"Invalid JSONC" (:error result))))))

(deftest write-rejects-payload-larger-than-1mb
  (testing "Regression target: a runaway webview payload must not wedge
            the host. Bytes-based, not chars-based; multibyte chars
            count by UTF-8 byte length."
    (with-isolated-config target
      (let [too-big (str "\"" (apply str (repeat (* 1024 1024) "a")) "\"")
            result (editor-actions/write-global-config {:contents too-big})]
        (is (false? (:ok result)))
        (is (re-find #"too large" (:error result)))
        (is (not (.exists target))
            "file MUST NOT be created when validation fails")))))

(deftest write-creates-parent-directory-if-missing
  (let [dir (File/createTempFile "eca-cfg-dir-" "")
        target (io/file dir "nested" "deeper" "config.json")]
    (.delete dir)
    (with-redefs [editor-actions/get-global-config-path (fn [] target)]
      (try
        (let [result (editor-actions/write-global-config {:contents "{}"})]
          (is (:ok result))
          (is (.exists target)))
        (finally
          (when (.exists target) (.delete target))
          (loop [d (.getParentFile target)]
            (when (and d (.exists d) (.isDirectory d))
              (.delete d)
              (recur (.getParentFile d)))))))))

(deftest read-returns-empty-contents-when-file-missing
  (with-isolated-config target
    (let [result (editor-actions/read-global-config)]
      (is (= "" (:contents result)))
      (is (false? (:exists result)))
      (is (= (.getAbsolutePath target) (:path result))))))

(deftest read-returns-file-contents-when-present
  (with-isolated-config target
    (spit target "{ \"a\": 1 }")
    (let [result (editor-actions/read-global-config)]
      (is (= "{ \"a\": 1 }" (:contents result)))
      (is (true? (:exists result)))
      (is (= (.getAbsolutePath target) (:path result))))))

(deftest ensure-exists-creates-seed-file
  (with-isolated-config target
    (is (not (.exists target)))
    (let [f (editor-actions/ensure-global-config-exists!)]
      (is (.exists f))
      (is (= "{}\n" (slurp f))))))

(deftest ensure-exists-is-idempotent
  (with-isolated-config target
    (spit target "{\"already\":true}")
    (let [f (editor-actions/ensure-global-config-exists!)]
      (is (.exists f))
      (is (= "{\"already\":true}" (slurp f))
          "existing content must NOT be overwritten by ensure"))))
