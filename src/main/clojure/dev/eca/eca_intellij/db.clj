(ns dev.eca.eca-intellij.db
  (:refer-clojure :exclude [get-in assoc-in update-in])
  (:require
   [dev.eca.eca-intellij.db :as db])
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
   :session {:mcp-servers {}}
   :server-config {}
   :on-status-changed-fns {}
   :on-focus-changed-fns {}
   :on-stderr-log-updated-fns {}
   :server-stderr-string ""
   :settings nil
   :on-settings-changed-fns {}})

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

(defn all-projects []
  (->> @db*
       :projects
       vals
       (mapv :project)
       (remove nil?)
       (remove #(.isDisposed ^Project %))))

(defn settings-updated! []
  (doseq [project (all-projects)]
    (doseq [on-settings-changed-fn (vals (get-in project [:on-settings-changed-fns]))]
      (on-settings-changed-fn))))

(defn init-db-for-project [^Project project ^SettingsState settings-state]
  (swap! db* update :projects (fn [projects]
                                (if (clojure.core/get-in projects [(.getBasePath project) :project])
                                  projects
                                  (update projects (.getBasePath project) #(merge (assoc empty-project :project project) %)))))
  (update-in project [:settings] (fn [settings]
                                   (if-not (:loaded-settings? settings)
                                     (-> settings
                                         (assoc :loaded-settings? true)
                                         (update :server-path #(or (.getServerPath settings-state) %))
                                         (update :server-args #(or (.getServerArgs settings-state) %))
                                         (update :usage-string-format #(or (.getUsageStringFormat settings-state) %)))
                                     settings)))
  (settings-updated!))

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

(defn set-usage-string-format-setting! [^SettingsState settings-state usage-string-format]
  (doseq [project (all-projects)]
    (let [usage-string-format (not-empty usage-string-format)]
      (.setUsageStringFormat settings-state usage-string-format)
      (assoc-in project [:settings :usage-string-format] usage-string-format))))
