(ns dev.eca.eca-intellij.extension.general-settings
  (:require
   [com.github.ericdallo.clj4intellij.extension :refer [def-extension]]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db]
   [seesaw.color :as s.color]
   [seesaw.core :as s]
   [seesaw.font :as s.font]
   [seesaw.mig :as s.mig])
  (:import
   [com.intellij.openapi.options Configurable]
   [com.intellij.ui IdeBorderFactory]
   [dev.eca.eca_intellij.extension SettingsState]))

(set! *warn-on-reflection* true)

(defonce ^:private component* (atom nil))

(def ^:private default-usage-string-format
  "{sessionTokens} / {contextLimit} ({sessionCost})")

(defn ^:private build-component [settings]
  (let [custom-server-path (:server-path settings)
        server-args (or (:server-args settings) "")
        server-path (or custom-server-path (.getCanonicalPath (config/download-server-path)))
        usage-string-format (or (:usage-string-format settings) default-usage-string-format)]
    (s.mig/mig-panel
     :items (->> [[(s.mig/mig-panel :border (IdeBorderFactory/createTitledBorder "Settings")
                                    :items [[(s/label "Server path *") ""]
                                            [(s/text :id :server-path
                                                     :columns 30
                                                     :editable? true
                                                     :enabled? custom-server-path
                                                     :text server-path) ""]
                                            [(s/checkbox :id :custom-server-path?
                                                         :selected? custom-server-path
                                                         :text "Custom path?"
                                                         :listen [:action (fn [_]
                                                                            (let [enabled? (s/config (s/select @component* [:#custom-server-path?]) :selected?)
                                                                                  server-path-component (s/select @component* [:#server-path])]
                                                                              (s/config! server-path-component :text "")
                                                                              (s/config! server-path-component :enabled? enabled?)))]) "wrap"]
                                            [(s/label "Server args *") ""]
                                            [(s/text :id :server-args
                                                     :columns 10
                                                     :editable? true
                                                     :enabled? true
                                                     :text server-args) "wrap"]
                                            [(s/label "Usage string format") ""]
                                            [(s/text :id :usage-string-format
                                                     :columns 30
                                                     :editable? true
                                                     :enabled? true
                                                     :text usage-string-format) ""]]) "span"]
                  [(s/label :text "When not speciying a custom server path, the plugin will download the latest eca automatically."
                            :font (s.font/font :size 14)
                            :foreground (s.color/color 110 110 110)) "wrap"]
                  [(s/label :text "*  requires ECA restart"
                            :font (s.font/font :size 14)
                            :foreground (s.color/color 110 110 110)) "wrap"]]
                 (remove nil?)))))

(def-extension GeneralSettingsConfigurable []
  Configurable
  (createComponent [_]
    (let [project (first (db/all-projects))
          component (build-component (db/get-in project [:settings]))]
      (reset! component* component)
      component))

  (getPreferredFocusedComponent [_]
    (s/select @component* [:#server-args]))

  (isModified [_]
    (let [settings-state (SettingsState/get)
          server-path (s/config (s/select @component* [:#server-path]) :text)
          server-args (s/config (s/select @component* [:#server-args]) :text)
          usage-string-format (s/config (s/select @component* [:#usage-string-format]) :text)]
      (boolean
       (or (not= server-path (or (.getServerPath settings-state) ""))
           (not= server-args (or (.getServerArgs settings-state) ""))
           (not= usage-string-format (or (.getServerArgs settings-state) ""))))))

  (reset [_]
    (let [project (first (db/all-projects))
          server-path (or (db/get-in project [:settings :server-path]) (.getCanonicalPath (config/download-server-path)))
          server-args (or (db/get-in project [:settings :server-args]) "")
          usage-string-format (or (db/get-in project [:settings :usage-string-format]) default-usage-string-format)]
      (s/config! (s/select @component* [:#server-path]) :text server-path)
      (s/config! (s/select @component* [:#server-args]) :text server-args)
      (s/config! (s/select @component* [:#usage-string-format]) :text usage-string-format)))

  (disposeUIResources [_]
    (reset! component* nil))

  (apply [_]
    (let [settings-state (SettingsState/get)
          server-path (when (s/config (s/select @component* [:#custom-server-path?]) :selected?)
                        (s/config (s/select @component* [:#server-path]) :text))
          server-args (s/config (s/select @component* [:#server-args]) :text)
          usage-string-format (s/config (s/select @component* [:#usage-string-format]) :text)]
      (db/set-server-path-setting! settings-state server-path)
      (db/set-server-args-setting! settings-state server-args)
      (db/set-usage-string-format-setting! settings-state usage-string-format)
      (db/settings-updated!)))

  (cancel [_]))
