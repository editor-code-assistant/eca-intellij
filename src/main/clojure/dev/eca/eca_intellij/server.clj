(ns dev.eca.eca-intellij.server
  (:require
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.github.ericdallo.clj4intellij.logger :as logger]
   [com.github.ericdallo.clj4intellij.tasks :as tasks]
   [dev.eca.eca-intellij.api :as api]
   [dev.eca.eca-intellij.config :as config]
   [dev.eca.eca-intellij.db :as db]
   [dev.eca.eca-intellij.notification :as notification])
  (:import
   [com.github.ericdallo.clj4intellij ClojureClassLoader]
   [com.intellij.openapi.application ApplicationInfo]
   [com.intellij.openapi.project Project]
   [com.intellij.util EnvironmentUtil]
   [java.io File]
   [java.util.zip ZipInputStream]))

(set! *warn-on-reflection* true)

(def ^:private client-capabilities
  {:codeAssistant {:chat true
                   :rewrite true}})

(def ^:private artifacts
  {:linux {:amd64 "eca-native-static-linux-amd64.zip"
           :aarch64 "eca-native-linux-aarch64.zip"}
   :macos {:amd64 "eca-native-macos-amd64.zip"
           :aarch64 "eca-native-macos-aarch64.zip"}
   :windows {:amd64 "eca-native-windows-amd64.zip"}})

(defn ^:private clean-up-server [^Project project]
  (let [server-process (db/get-in project [:server-process])]
    (when (some-> server-process p/alive?)
      (p/destroy server-process)))
  (db/update-in project [] (fn [db]
                             (assoc db :status :stopped
                                    :client nil
                                    :server-process nil)))
  (run! #(% project :stopped) (vals (db/get-in project [:on-status-changed-fns]))))

(defn ^:private os-name []
  (let [os-name (string/lower-case (System/getProperty "os.name" "generic"))]
    (cond
      (string/includes? os-name "win") :windows
      (string/includes? os-name "mac") :macos
      :else :linux)))

(defn ^:private os-arch []
  (if (= "aarch64" (System/getProperty "os.arch"))
    :aarch64
    :amd64))

(def ^:private latest-version-uri
  "https://raw.githubusercontent.com/editor-code-assistant/eca/master/resources/ECA_VERSION")

(def ^:private download-artifact-uri
  "https://github.com/editor-code-assistant/eca/releases/download/%s/%s")

(defn ^:private unzip-file [input ^File dest-file]
  (with-open [stream (-> input io/input-stream ZipInputStream.)]
    (loop [entry (.getNextEntry stream)]
      (when entry
        (if (.isDirectory entry)
          (when-not (.exists dest-file)
            (.mkdirs dest-file))
          (clojure.java.io/copy stream dest-file))
        (recur (.getNextEntry stream))))))

(defn ^:private download-server! [project indicator ^File download-path ^File server-version-path latest-version]
  (tasks/set-progress indicator "ECA: Downloading")
  (let [platform (os-name)
        arch (os-arch)
        artifact-name (get-in artifacts [platform arch])
        uri (format download-artifact-uri latest-version artifact-name)
        dest-server-file download-path
        dest-path (.getCanonicalPath dest-server-file)]
    (logger/info "Downloading eca from" uri)
    (when (.exists dest-server-file)
      (io/delete-file dest-server-file true))
    (unzip-file (io/input-stream uri) dest-server-file)
    (doto (io/file dest-server-file)
      (.setWritable true)
      (.setReadable true)
      (.setExecutable true))
    (spit server-version-path latest-version)
    (db/assoc-in project [:downloaded-server-path] dest-path)
    (logger/info "Downloaded eca to" dest-path)))

(defn ^:private on-initialized [result project]
  (db/update-in project [:session] (fn [_]
                                     {:models (:models result)
                                      :chat-behaviors (:chat-behaviors result)
                                      :chat-selected-behavior (:chat-default-behavior result)
                                      :chat-selected-model (:chat-default-model result)
                                      :welcome-message (:chat-welcome-message result)})))

(defn ^:private env []
  (let [env (EnvironmentUtil/getEnvironmentMap)
        ;; We get env from logged user shell
        shell-env (try
                    (let [{:keys [out]} (p/shell {:out :string}
                                                 (or (get env "SHELL") "bash") "--login" "-i" "-c" "env")]
                      (->> (string/split-lines out)
                           (map #(string/split % #"=" 2))
                           (filter #(= 2 (count %)))
                           (map (fn [[k v]] [k v]))
                           (into {})))
                    (catch Exception e
                      (logger/warn "Could not get user shell env: " (.getMessage e))
                      nil))]
    (merge {}
           (EnvironmentUtil/getEnvironmentMap)
           shell-env)))

(defn ^:private spawn-server! [^Project project indicator server-path]
  (tasks/set-progress indicator "ECA: Starting...")
  (let [server-args (or (some-> (db/get-in project [:settings :server-args])
                                (string/split #" "))
                        [])
        env (env)
        command (concat [server-path "server"] server-args)
        _ (logger/info "Spawning server:" (string/join " " command) "with env" env)
        process (p/process command
                           {:dir (.getBasePath project)
                            :env env})
        ;; TODO pass trace-level
        client (api/client (:in process) (:out process) nil)]
    (db/assoc-in project [:server-process] process)
    (future
      (try
        (with-open [r (io/reader (:err process))]
          (doseq [line (line-seq r)]
            (db/update-in project [:server-stderr-string] #(str % line "\n"))
            (doseq [on-stderr-log-updated-fn (vals (db/get-in project [:on-stderr-log-updated-fns]))]
              (on-stderr-log-updated-fn))
            (logger/info "stderr:" line)))
        (catch Throwable e
          (logger/error "Error reading ECA process err: " e)
          ;; Swallow errors while reading stderr to avoid interfering with startup
          )))

    (api/start-client! client {:progress-indicator indicator
                               :project project})

    (tasks/set-progress indicator "ECA: Initializing...")
    (let [request-initiatilize (api/request! client [:initialize
                                                     {:initialization-options (db/get-in project [:settings])
                                                      :client-info {:name "IntelliJ"
                                                                    :version (str (.getBuild (ApplicationInfo/getInstance)))}
                                                      :capabilities client-capabilities
                                                      :workspace-folders [{:name (.getName project)
                                                                           :uri (str (.toURI (io/file (.getBasePath project))))}]}])]
      (loop [count 0]
        (Thread/sleep 500)
        (cond
          (and (not (realized? request-initiatilize))
               (not (p/alive? process)))
          (notification/show-notification! {:project project
                                            :type :error
                                            :title "ECA process error"
                                            :message "Check server logs via 'ECA: Show server logs' action"})

          (and (realized? request-initiatilize)
               (p/alive? process))
          (do (api/notify! client [:initialized {}])
              (db/assoc-in project [:client] client)
              (on-initialized (deref request-initiatilize) project))

          :else
          (do
            (logger/info "Checking if server initialized, try number:" count)
            (recur (inc count))))))))

(defn start! [^Project project]
  (db/assoc-in project [:status] :starting)
  (run! #(% project :starting) (vals (db/get-in project [:on-status-changed-fns])))
  (tasks/run-background-task!
   project
   "ECA startup"
   (fn [indicator]
     (ClojureClassLoader/bind)
     (let [download-path (config/download-server-path)
           server-version-path (config/download-server-version-path)
           latest-version* (delay (try (string/trim (slurp latest-version-uri)) (catch Exception _ nil)))
           custom-server-path (db/get-in project [:settings :server-path])]
       (cond
         custom-server-path
         (spawn-server! project indicator custom-server-path)

         (and (.exists download-path)
              (or (not @latest-version*) ;; on network connection issues we use any downloaded server
                  (= (try (slurp server-version-path) (catch Exception _ :error-checking-local-version))
                     @latest-version*)))
         (spawn-server! project indicator download-path)

         @latest-version*
         (do (download-server! project indicator download-path server-version-path @latest-version*)
             (spawn-server! project indicator download-path))

         :else
         (notification/show-notification! {:project project
                                           :type :error
                                           :title "ECA download error"
                                           :message "There is no server downloaded and there was a network issue trying to download the latest server"}))

       (db/assoc-in project [:status] :running)
       (run! #(% project :running) (vals (db/get-in project [:on-status-changed-fns])))
        ;; For race conditions when server starts too fast
        ;; and other places that listen didn't setup yet
       (future
         (Thread/sleep 1000)
         (run! #(% project :running) (vals (db/get-in project [:on-status-changed-fns]))))
       (logger/info "Initialized ECA"))))
  true)

(defn shutdown! [^Project project]
  (when-let [client (api/connected-client project)]
    (db/assoc-in project [:status] :stopped)
    @(api/request! client [:shutdown {}])
    (api/notify! client [:exit {}])
    (clean-up-server project)))

(defn status [^Project project]
  (db/get-in project [:status]))
