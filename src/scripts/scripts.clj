(ns scripts
  (:require
   [babashka.fs :as fs]
   [babashka.tasks :refer [shell]]
   [clojure.string :as string]))

(def version-regex #"pluginVersion = ([0-9]+.[0-9]+.[0-9]+.*)")

(defn ^:private replace-in-file [file regex content]
  (as-> (slurp file) $
    (string/replace $ regex content)
    (spit file $)))

(defn ^:private add-changelog-entry [tag comment]
  (replace-in-file "CHANGELOG.md"
                   #"## \[Unreleased\]"
                   (if comment
                     (format "## [Unreleased]\n\n## %s\n\n- %s" tag comment)
                     (format "## [Unreleased]\n\n## %s" tag))))

(defn ^:private replace-tag [tag]
  (replace-in-file "gradle.properties"
                   version-regex
                   (format "pluginVersion = %s" tag)))

(defn tag [& [tag]]
  (shell "git fetch origin")
  (shell "git pull origin HEAD")
  (replace-tag tag)
  (add-changelog-entry tag nil)
  (shell "git add gradle.properties CHANGELOG.md")
  (shell (format "git commit -m \"Release: %s\"" tag))
  (shell (str "git tag " tag))
  (shell "git push origin HEAD")
  (shell "git push origin --tags"))

(defn tests []
  (shell "./gradlew test"))

(defn dev-webview []
  (shell "npm run dev --prefix eca-webview"))

(defn ^:private build-webview []
  (shell "npm install --prefix eca-webview")
  (shell "npm run build --prefix eca-webview"))

(defn build-plugin []
  (shell "./gradlew buildPlugin"))

(defn build-prod-plugin
  "Build a prod-flavored plugin zip with the production webview bundled in.

  Unlike `build-plugin`, this skips `src/main/dev-resources/is-dev` so the
  tool-window loads the bundled webview at `http://eca/...` instead of the
  Vite dev server at `http://localhost:5173`. Use this when you want to
  install the plugin locally without running `bb dev-webview`."
  []
  (build-webview)
  (shell "./gradlew clean buildPlugin -PprodBuild"))

(defn ^:private unzip-plugin-into [intellij-plugins-path]
  (let [version (last (re-find version-regex (slurp "gradle.properties")))]
    (fs/unzip (format "./build/distributions/eca-intellij-%s.zip" version)
              intellij-plugins-path
              {:replace-existing true})
    (println "Installed!")))

(defn install-plugin [& [intellij-plugins-path]]
  (if-not intellij-plugins-path
    (println "Specify the Intellij plugins path\ne.g: bb install-plugin /home/greg/.local/share/JetBrains/IdeaIC2024.3")
    (do (build-plugin)
        (unzip-plugin-into intellij-plugins-path))))

(defn install-prod-plugin
  "Install a prod-flavored plugin zip into the given IntelliJ plugins path.

  Mirrors `install-plugin` but uses `build-prod-plugin` under the hood, so the
  installed plugin uses the bundled webview instead of the Vite dev server."
  [& [intellij-plugins-path]]
  (if-not intellij-plugins-path
    (println "Specify the Intellij plugins path\ne.g: bb install-prod-plugin /home/greg/.local/share/JetBrains/IdeaIC2024.3")
    (do (build-prod-plugin)
        (unzip-plugin-into intellij-plugins-path))))

(defn publish-plugin []
  (build-webview)
  (shell "./gradlew clean publishPlugin"))
