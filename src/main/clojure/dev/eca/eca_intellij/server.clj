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
   [dev.eca.eca-intellij.log-store :as log-store]
   [dev.eca.eca-intellij.notification :as notification])
  (:import
   [com.github.ericdallo.clj4intellij ClojureClassLoader]
   [com.intellij.openapi.application ApplicationInfo]
   [com.intellij.openapi.project Project]
   [com.intellij.util EnvironmentUtil]
   [java.io File IOException InputStream]
   [java.math BigInteger]
   [java.net HttpURLConnection URI]
   [java.nio.file CopyOption Files StandardCopyOption]
   [java.security MessageDigest]
   [java.util.zip ZipInputStream]))

(set! *warn-on-reflection* true)

(def ^:private client-capabilities
  {:code-assistant {:chat true
                    :editor {:diagnostics true}
                    :chat-capabilities {:ask-question true}}})

(def ^:private artifacts
  {:linux {:amd64 "eca-native-static-linux-amd64.zip"
           :aarch64 "eca-native-linux-aarch64.zip"}
   :macos {:amd64 "eca-native-macos-amd64.zip"
           :aarch64 "eca-native-macos-aarch64.zip"}
   :windows {:amd64 "eca-native-windows-amd64.zip"}})

(defn ^:private broadcast-status!
  "Notify every registered `:on-status-changed-fns` listener of `status` for
   `project`, logging which listener keys are present so blank-webview bug
   reports can be correlated with whether the `:webview` listener was reached
   at broadcast time. The listener set is small (typically `:webview` plus
   `:status-bar`), so logging the keys verbatim is safe."
  [^Project project status]
  (let [listeners (db/get-in project [:on-status-changed-fns])]
    (logger/info (format "[ECA] Broadcasting status %s for project %s (listeners=%s)"
                         status
                         (.getName project)
                         (vec (keys listeners))))
    (run! #(% project status) (vals listeners))))

(defn ^:private clean-up-server [^Project project]
  (let [server-process (db/get-in project [:server-process])]
    (when (some-> server-process p/alive?)
      (p/destroy server-process)))
  (db/update-in project [] (fn [db]
                             (assoc db :status :stopped
                                    :client nil
                                    :server-process nil)))
  (broadcast-status! project :stopped))

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

(def ^:private latest-release-uri
  "https://github.com/editor-code-assistant/eca/releases/latest")

(def ^:private download-artifact-uri
  "https://github.com/editor-code-assistant/eca/releases/download/%s/%s")

(defn ^:private version-from-release-redirect
  "Extracts the version from a GitHub `/releases/latest` redirect Location
   header (`https://github.com/<org>/<repo>/releases/tag/<version>`).
   Nil when absent or not a tag URL."
  [location]
  (some-> location (string/split #"/tag/") second string/trim not-empty))

(defn ^:private latest-version!
  "Resolves the latest *published* ECA release version by reading the
   Location header of the GitHub `/releases/latest` redirect.

   The previous source (`resources/ECA_VERSION` on master) races the release
   pipeline: the file is bumped when the release tag is cut, up to ~1h before
   the native artifacts finish building/uploading, so downloads during that
   window 404 (exactly what JetBrains' marketplace plugin checker hit with
   0.148.1). The redirect can only ever point at a published release.
   Returns nil when it cannot be resolved (e.g. offline)."
  []
  (try
    (let [^HttpURLConnection conn (.openConnection (.toURL (URI. latest-release-uri)))]
      (try
        (doto conn
          (.setInstanceFollowRedirects false)
          (.setConnectTimeout 10000)
          (.setReadTimeout 10000)
          (.setRequestMethod "HEAD"))
        (version-from-release-redirect (.getHeaderField conn "Location"))
        (finally
          (.disconnect conn))))
    (catch Exception e
      (logger/warn "Could not resolve latest ECA version:" (.getMessage e))
      nil)))

(defn ^:private unzip-file [input ^File dest-file]
  (with-open [stream (-> input io/input-stream ZipInputStream.)]
    (loop [entry (.getNextEntry stream)]
      (when entry
        (if (.isDirectory entry)
          (when-not (.exists dest-file)
            (.mkdirs dest-file))
          (clojure.java.io/copy stream dest-file))
        (recur (.getNextEntry stream))))))

(defn ^:private sha256-hex [^File file]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (with-open [^InputStream in (io/input-stream file)]
      (loop []
        (let [n (.read in buffer)]
          (when-not (neg? n)
            (when (pos? n)
              (.update digest buffer 0 n))
            (recur)))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn ^:private expected-sha256
  "Fetches the sha256 digest published alongside the release artifact
   (`<artifact-uri>.sha256`). Returns nil when unavailable or malformed so
   releases without checksums still install."
  [artifact-uri]
  (try
    (let [sha (some-> (slurp (str artifact-uri ".sha256"))
                      string/trim
                      (string/split #"\s+")
                      first
                      string/lower-case)]
      (if (and sha (re-matches #"[0-9a-f]{64}" sha))
        sha
        (do (logger/warn "Unexpected content in checksum file for" artifact-uri)
            nil)))
    (catch Exception e
      (logger/warn "Could not fetch checksum for" artifact-uri ":" (.getMessage e))
      nil)))

(defn ^:private atomic-move! [^File source ^File dest]
  (try
    (Files/move (.toPath source) (.toPath dest)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (catch java.io.IOException _
      ;; Windows can refuse to atomically replace an existing/locked file.
      (io/delete-file dest true)
      (Files/move (.toPath source) (.toPath dest)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(defn ^:private download-server! [project indicator ^File download-path ^File server-version-path latest-version]
  (tasks/set-progress indicator "ECA: Downloading")
  (let [platform (os-name)
        arch (os-arch)
        artifact-name (get-in artifacts [platform arch])
        uri (format download-artifact-uri latest-version artifact-name)
        dest-path (.getCanonicalPath download-path)
        zip-temp (io/file (str dest-path ".zip.tmp"))
        binary-temp (io/file (str dest-path ".tmp"))]
    (logger/info "Downloading eca from" uri)
    (try
      ;; Download to disk first so the zip can be checksum-verified before
      ;; anything is extracted or installed.
      (with-open [in (io/input-stream uri)
                  out (io/output-stream zip-temp)]
        (io/copy in out))
      (when-let [expected (expected-sha256 uri)]
        (let [actual (sha256-hex zip-temp)]
          (if (= expected actual)
            (logger/info "Checksum OK for" artifact-name)
            (throw (ex-info (format "Checksum mismatch for %s (expected %s, got %s), download is corrupted"
                                    artifact-name expected actual)
                            {:uri uri})))))
      (unzip-file zip-temp binary-temp)
      (doto binary-temp
        (.setWritable true)
        (.setReadable true)
        (.setExecutable true))
      ;; Atomic install: a partially written binary must never land on the
      ;; final path, which offline starts reuse blindly.
      (atomic-move! binary-temp download-path)
      (spit server-version-path latest-version)
      (db/assoc-in project [:downloaded-server-path] dest-path)
      (logger/info "Downloaded eca to" dest-path)
      (finally
        (io/delete-file zip-temp true)
        (io/delete-file binary-temp true)))))

(defn ^:private download-or-use-existing!
  "Runs download-server!, degrading gracefully on environmental IO failures
   (offline, proxy issues, or a release published seconds ago whose artifacts
   are still uploading): falls back to a previously downloaded binary when
   one exists. Logs warnings, never Logger.error -- JetBrains' marketplace
   plugin checker fails any plugin that logs an error during startup and none
   of these conditions are plugin bugs. Returns true when a usable binary
   sits at download-path afterwards."
  [project indicator ^File download-path ^File server-version-path latest-version]
  (try
    (download-server! project indicator download-path server-version-path latest-version)
    true
    (catch IOException e
      (logger/warn (str "Could not download ECA " latest-version ": " (.getMessage e)))
      (if (.exists download-path)
        (do
          (logger/info "Using previously downloaded ECA server")
          (notification/show-notification! {:project project
                                            :type :warning
                                            :title "ECA download failed"
                                            :message (str "Could not download ECA " latest-version
                                                          ", using the previously downloaded version instead: "
                                                          (.getMessage e))})
          true)
        (do
          (notification/show-notification! {:project project
                                            :type :error
                                            :title "ECA download error"
                                            :message (str "Could not download ECA " latest-version ": "
                                                          (.getMessage e))})
          false)))))

(def ^:private initialize-timeout-ms 60000)

(defn ^:private process-exit-code [process]
  (try
    (.exitValue ^Process (:proc process))
    (catch Exception _ nil)))

(defn ^:private invalidate-downloaded-server!
  "Deletes the downloaded server binary and its version marker so the next
   start re-downloads a fresh copy. Used when the cached binary proves
   unusable at runtime (e.g. SIGKILLed by macOS for a broken code
   signature); retrying the same file would fail identically forever."
  []
  (io/delete-file (config/download-server-path) true)
  (io/delete-file (config/download-server-version-path) true))

(defn ^:private on-initialized [result project]
  ;; Merge on top of the existing session map instead of replacing it
  ;; wholesale -- a wholesale replace silently wipes any keys populated
  ;; before `initialized` completes (notably `:mcp-servers`, which
  ;; `webview.clj`'s tool-server-updated defmethod fills in from
  ;; reactive `tool/serverUpdated` notifications that can race ahead of
  ;; this callback). Wiping that map then leaves the Settings -> MCPs
  ;; tab empty after webview/ready replay until the next notification
  ;; arrives. Closes #22.
  (db/update-in project [:session]
                #(merge % {:models (:models result)
                           :chat-agents (:chat-agents result)
                           :chat-selected-agent (:chat-default-agent result)
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
        _ (log-store/append! project {:source :server
                                      :text (str "Spawning ECA server: "
                                                 (string/join " " command))})
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
            ;; Dual sink: the legacy `:server-stderr-string` surface
            ;; powers the existing "ECA: Show server logs" editor
            ;; buffer (server_logs.clj); the new log-store feeds the
            ;; webview's Settings → Logs tab with structured entries.
            (db/update-in project [:server-stderr-string] #(str % line "\n"))
            (log-store/append! project {:source :server :text line})
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
                                                                           :uri (str (.toURI (io/file (.getBasePath project))))}]}])
          custom-server? (some? (db/get-in project [:settings :server-path]))
          max-tries (quot initialize-timeout-ms 500)]
      (loop [count 0]
        (Thread/sleep 500)
        (cond
          ;; Checked before `realized?`: a response followed by process death
          ;; must not count as a healthy start, and the old realized+alive
          ;; check looped forever in that state.
          (not (p/alive? process))
          (let [exit-code (process-exit-code process)]
            (when (and (identical? :macos (os-name))
                       (= 137 exit-code)
                       (not custom-server?))
              ;; SIGKILL on macOS is the fingerprint of a binary failing
              ;; code-signature validation (e.g. corrupted download).
              (logger/info "Server SIGKILLed, invalidating downloaded server so the next start re-downloads it")
              (invalidate-downloaded-server!))
            (notification/show-notification! {:project project
                                              :type :error
                                              :title "ECA process error"
                                              :message (str "Server exited during startup (exit code " exit-code "). "
                                                            "Check server logs via 'ECA: Show server logs' action")})
            false)

          (realized? request-initiatilize)
          (do (api/notify! client [:initialized {}])
              (db/assoc-in project [:client] client)
              (on-initialized (deref request-initiatilize) project)
              true)

          (>= count max-tries)
          (do (p/destroy process)
              (notification/show-notification! {:project project
                                                :type :error
                                                :title "ECA initialize timeout"
                                                :message (str "Server did not answer initialize within "
                                                              (quot initialize-timeout-ms 1000)
                                                              "s. Check server logs via 'ECA: Show server logs' action")})
              false)

          :else
          (do
            (logger/info "Checking if server initialized, try number:" count)
            (recur (inc count))))))))

(defn start! [^Project project]
  (if (contains? #{:starting :running} (db/get-in project [:status]))
    (logger/info "ECA server already starting/running, ignoring start request")
    (do
      (db/assoc-in project [:status] :starting)
      (broadcast-status! project :starting)
      (tasks/run-background-task!
       project
       "ECA startup"
       (fn [indicator]
         (ClojureClassLoader/bind)
         (let [download-path (config/download-server-path)
               server-version-path (config/download-server-version-path)
               latest-version* (delay (latest-version!))
               custom-server-path (db/get-in project [:settings :server-path])
               started? (try
                          (cond
                            custom-server-path
                            (spawn-server! project indicator custom-server-path)

                            (and (.exists download-path)
                                 (or (not @latest-version*) ;; on network connection issues we use any downloaded server
                                     (= (try (slurp server-version-path) (catch Exception _ :error-checking-local-version))
                                        @latest-version*)))
                            (spawn-server! project indicator download-path)

                            @latest-version*
                            (and (download-or-use-existing! project indicator download-path server-version-path @latest-version*)
                                 (spawn-server! project indicator download-path))

                            :else
                            (do (notification/show-notification! {:project project
                                                                  :type :error
                                                                  :title "ECA download error"
                                                                  :message "There is no server downloaded and there was a network issue trying to download the latest server"})
                                false))
                          (catch Exception e
                            ;; IO failures are environmental (network, disk), not plugin
                            ;; bugs: warn so JetBrains' plugin checker (which fails
                            ;; plugins on any logged error) stays green; the user is
                            ;; informed via the notification either way.
                            (if (instance? IOException e)
                              (logger/warn (str "Could not start ECA server: " (.getMessage e)))
                              (logger/error "Error starting ECA server:" e))
                            (notification/show-notification! {:project project
                                                              :type :error
                                                              :title "ECA start error"
                                                              :message (or (ex-message e) (str e))})
                            false))]
           (if started?
             (do
               (db/assoc-in project [:status] :running)
               (broadcast-status! project :running)
               ;; For race conditions when server starts too fast
               ;; and other places that listen didn't setup yet
               (future
                 (Thread/sleep 1000)
                 (broadcast-status! project :running))
               (logger/info "Initialized ECA"))
             ;; Status must reflect reality: the old code broadcast :running
             ;; unconditionally, showing a healthy UI over a dead server.
             (do
               (db/assoc-in project [:status] :failed)
               (broadcast-status! project :failed))))))))
  true)

(defn shutdown! [^Project project]
  ;; Deliberately not gated on :running: a stuck-starting or failed server
  ;; must still be stoppable (the old api/connected-client gate made
  ;; stop/restart a no-op exactly when users needed it, and the restart
  ;; action then spawned a second process next to the orphan).
  (when-let [client (db/get-in project [:client])]
    (try
      ;; Bounded graceful shutdown: a dead server never answers and an
      ;; unbounded deref would hang the restart action forever.
      (when (identical? ::timeout (deref (api/request! client [:shutdown {}]) 3000 ::timeout))
        (logger/warn "Timed out waiting for server shutdown response"))
      (api/notify! client [:exit {}])
      (catch Exception e
        (logger/warn "Error requesting server shutdown:" (.getMessage e)))))
  (clean-up-server project))

(defn status [^Project project]
  (db/get-in project [:status]))
