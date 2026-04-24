(ns dev.eca.eca-intellij.config
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:import
   [com.intellij.openapi.project Project]
   [java.io File]))

(set! *warn-on-reflection* true)

(defn ^:private plugin-path* ^File []
  (io/file (com.intellij.openapi.application.PathManager/getPluginsPath) "eca-intellij"))

(defn dev? []
  (boolean (io/resource "is-dev" (.getClassLoader clojure.lang.Symbol))))

(defn ^:private windows? []
  (-> (System/getProperty "os.name" "")
      string/lower-case
      (string/includes? "win")))

(def plugin-path (memoize plugin-path*))

(defn download-server-path ^File []
  (io/file (plugin-path) (if (windows?) "eca.exe" "eca")))

(defn download-server-version-path ^File []
  (io/file (plugin-path) "eca-version"))

(defn project-cache-path ^File [^Project project]
  (io/file (plugin-path) "cache" (.getName project)))
