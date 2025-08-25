(ns dev.eca.eca-intellij.db
  (:refer-clojure :exclude [get-in assoc-in update-in])
  (:import
   [com.intellij.openapi.project Project]
   [dev.eca.eca_intellij.extension SettingsState]))

(set! *warn-on-reflection* true)

(def ^:private empty-project
  {:status :stopped
   :downloaded-server-path nil
   :client nil
   :server-process nil
   :project nil
   :session {:models []
             :chat-behaviors []
             :chat-selected-behavior nil
             :chat-selected-model nil
             :welcome-message ""
             :mcp-servers {}}
   :on-status-changed-fns {}
   :on-focus-changed-fns {}})

(defonce db* (atom {:projects {}}))

(defn get-in
  ([project fields]
   (get-in project fields nil))
  ([^Project project fields default]
   (clojure.core/get-in @db* (concat [:projects (.getBasePath project)] fields) default)))

(defn assoc-in [^Project project fields value]
  (swap! db* clojure.core/assoc-in (concat [:projects (.getBasePath project)] fields) value))

(defn update-in [^Project project fields fn]
  (swap! db* clojure.core/update-in (concat [:projects (.getBasePath project)] fields) fn))

(defn init-db-for-project [^Project project]
  (swap! db* update :projects (fn [projects]
                                (if (clojure.core/get-in projects [(.getBasePath project) :project])
                                  projects
                                  (update projects (.getBasePath project) #(merge (assoc empty-project :project project) %))))))

(defn all-projects []
  (->> @db*
       :projects
       vals
       (mapv :project)
       (remove nil?)
       (remove #(.isDisposed ^Project %))))

(defn set-server-path-setting! [^SettingsState settings-state server-path]
  (doseq [project (all-projects)]
    (let [server-path (not-empty server-path)]
      (.setServerPath settings-state server-path)
      (assoc-in project [:settings :server-path] server-path))))

(defn set-server-args-setting! [^SettingsState settings-state server-args]
  (doseq [project (all-projects)]
    (let [server-args (not-empty server-args)]
      (.setServerArgs settings-state server-args)
      (assoc-in project [:settings :server-args] server-args))))
