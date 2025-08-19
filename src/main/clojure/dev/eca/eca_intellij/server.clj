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
   [com.intellij.openapi.project Project]
   [com.intellij.util EnvironmentUtil]
   [java.io File]
   [java.util.zip ZipInputStream]))

(set! *warn-on-reflection* true)

(def ^:private client-capabilities
  {:chat {}})

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
                             (assoc db :status :disconnected
                                    :client nil
                                    :server-process nil)))
  (run! #(% project :disconnected) (:on-status-changed-fns @db/db*)))

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
    (unzip-file (io/input-stream uri) dest-server-file)
    (doto (io/file dest-server-file)
      (.setWritable true)
      (.setReadable true)
      (.setExecutable true))
    (spit server-version-path latest-version)
    (db/assoc-in project [:downloaded-server-path] dest-path)
    (logger/info "Downloaded eca to" dest-path)))

(defn ^:private spawn-server! [^Project project indicator server-path]
  (logger/info "Spawning eca server process using path" server-path)
  (tasks/set-progress indicator "ECA: Starting...")
  (let [trace-level (keyword (db/get-in project [:settings :trace-level]))
        process (p/process [server-path "server"]
                           {:dir (.getBasePath project)
                            :env (EnvironmentUtil/getEnvironmentMap)
                            :err :string})
        client (api/client (:in process) (:out process) trace-level)]
    (db/assoc-in project [:server-process] process)
    (api/start-client! client {:progress-indicator indicator
                               :project project})

    (tasks/set-progress indicator "ECA: Initializing...")
    (let [request-initiatilize (api/request! client [:initialize
                                                     {:initialization-options (db/get-in project [:settings])
                                                      :capabilities client-capabilities}])]
      (loop [count 0]
        (Thread/sleep 500)
        (cond
          (and (not (realized? request-initiatilize))
               (not (p/alive? process)))
          (notification/show-notification! {:project project
                                            :type :error
                                            :title "ECA process error"
                                            :message @(:err process)})

          (and (realized? request-initiatilize)
               (p/alive? process))
          (do (api/notify! client [:initialized {}])
              (db/assoc-in project [:client] client))

          :else
          (do
            (logger/info "Checking if server initialized, try number:" count)
            (recur (inc count))))))))

(defn start! [^Project project]
  (db/assoc-in project [:status] :connecting)
  (run! #(% project :connecting) (:on-status-changed-fns @db/db*))
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

       (db/assoc-in project [:status] :connected)
       (run! #(% project :connected) (:on-status-changed-fns @db/db*))
        ;; For race conditions when server starts too fast
        ;; and other places that listen didn't setup yet
       (future
         (Thread/sleep 1000)
         (run! #(% project :connected) (:on-status-changed-fns @db/db*)))
       (logger/info "Initialized ECA"))))
  true)

(defn shutdown! [^Project project]
  (when-let [client (api/connected-client project)]
    (db/assoc-in project [:status] :shutting-down)
    @(api/request! client [:shutdown {}])
    (clean-up-server project)))

(defn status [^Project project]
  (db/get-in project [:status]))
