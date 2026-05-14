(ns eca-intellij.test-runner
  "Discovers every `*_test.clj` namespace under `src/test/clojure` and runs
   their deftests. Exits with code 1 on the first failure or error so the
   Gradle `clojureTest` task fails loudly.

   Lives outside the `dev.eca.eca-intellij` namespace tree so its filename
   (`test_runner.clj`) cannot match the `_test.clj` discovery pattern.
   Invoked from build.gradle.kts via `clojure.main -m eca-intellij.test-runner`."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :as t]))

(def ^:private test-root "src/test/clojure")

(defn ^:private file->ns-sym [^java.io.File f]
  (let [path (.getPath f)
        ;; Strip the test-root prefix so we end up with a relative path,
        ;; then translate the filesystem layout into a namespace symbol
        ;; (e.g. dev/eca/eca_intellij/foo_test.clj
        ;;       → dev.eca.eca-intellij.foo-test).
        idx (string/index-of path test-root)
        rel (subs path (+ idx (count test-root) 1))]
    (-> rel
        (string/replace #"\.clj$" "")
        (string/replace #"/" ".")
        (string/replace #"_" "-")
        symbol)))

(defn ^:private discover-test-namespaces []
  (->> (file-seq (io/file test-root))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"_test\.clj$" (.getName ^java.io.File %)))
       (sort-by #(.getPath ^java.io.File %))
       (mapv file->ns-sym)))

(defn -main [& _]
  (let [nss (discover-test-namespaces)]
    (println (format "[clojureTest] discovered %d test namespace(s)" (count nss)))
    (doseq [ns nss]
      (println "[clojureTest] require" ns)
      (require ns))
    (let [{:keys [fail error]} (apply t/run-tests nss)
          failed? (or (pos? fail) (pos? error))]
      (shutdown-agents)
      (System/exit (if failed? 1 0)))))
