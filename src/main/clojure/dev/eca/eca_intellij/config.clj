(ns dev.eca.eca-intellij.config
  (:require
   [clojure.java.io :as io])
  (:import
   [com.intellij.openapi.project Project]
   [java.io File]))

(set! *warn-on-reflection* true)

(defn ^:private plugin-path* ^File []
  (io/file (com.intellij.openapi.application.PathManager/getPluginsPath) "eca-intellij"))

(defn dev? []
  (boolean (io/resource "is-dev" (.getClassLoader clojure.lang.Symbol))))

(def plugin-path (memoize plugin-path*))

(defn download-server-path ^File []
  (io/file (plugin-path) "eca"))

(defn download-server-version-path ^File []
  (io/file (plugin-path) "eca-version"))

(defn project-cache-path ^File [^Project project]
  (io/file (plugin-path) "cache" (.getName project)))
