(ns dev.eca.eca-intellij.plugin-descriptor-test
  "Guards plugin.xml declarations that only break at runtime inside specific
   IDE builds, where no compile-time check can catch them."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is testing]]))

(def ^:private plugin-xml
  (delay (slurp (io/file "src/main/resources/META-INF/plugin.xml"))))

(deftest declares-optional-jcef-plugin-dependency
  (testing "Regression (JetBrains plugin-checker build 365160, IU-262): since
            2026.2 the com.intellij.ui.jcef / org.cef classes live in the
            separate bundled com.intellij.modules.jcef plugin, so without this
            dependency the tool window and actions fail with
            ClassNotFoundException: com.intellij.ui.jcef.JBCefBrowser. It must
            stay optional: the module id does not exist on 2026.1 and older,
            where a hard depends would prevent the plugin from loading."
    (let [[_ attrs] (re-find #"<depends([^>]*)>com\.intellij\.modules\.jcef</depends>" @plugin-xml)]
      (is (some? attrs) "plugin.xml must declare a depends on com.intellij.modules.jcef")
      (is (string/includes? (or attrs "") "optional=\"true\"")
          "the jcef depends must be optional to keep loading on 2026.1 and older")
      (is (string/includes? (or attrs "") "config-file=\"eca-jcef.xml\"")))))

(deftest jcef-optional-descriptor-file-exists
  (testing "The config-file referenced by the optional jcef dependency must
            ship with the plugin, otherwise the descriptor fails to load on
            IDEs where the dependency resolves."
    (is (.exists (io/file "src/main/resources/META-INF/eca-jcef.xml")))))
