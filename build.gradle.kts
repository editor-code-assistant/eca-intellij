import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)
fun prop(name: String): String {
    return properties(name).get()
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.4"
    id("org.jetbrains.changelog") version "1.3.1"
    id("dev.clojurephant.clojure") version "0.8.0"
}

group = prop("pluginGroup")
version = prop("pluginVersion")

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "Clojars"
        url = uri("https://repo.clojars.org")
    }
}

dependencies {
    implementation ("org.clojure:clojure:1.12.1")
    implementation ("com.github.ericdallo:clj4intellij:0.8.0")
    implementation ("com.rpl:proxy-plus:0.0.9")
    implementation ("nrepl:nrepl:1.3.1")
    implementation ("seesaw:seesaw:1.5.0")
    implementation ("babashka:process:0.6.23")
    implementation ("com.github.clojure-lsp:lsp4clj:1.13.1")
    implementation ("camel-snake-kebab:camel-snake-kebab:0.4.3")
    implementation ("org.clojure:core.async:1.5.648") {
        because("issue https://clojure.atlassian.net/browse/ASYNC-248")
    }

    // JUnit 4 stays available for any Kotlin platform-level tests we add
    // alongside the Clojure suite (BasePlatformTestCase is JUnit 4 friendly).
    // Clojure tests do not need a JUnit Platform engine because the
    // `clojureTest` JavaExec task invokes `clojure.main -m
    // eca-intellij.test-runner` directly.
    testImplementation ("junit:junit:4.13.2")
}

sourceSets {
    main {
        java.srcDirs("src/main", "src/gen")
        // `src/main/dev-resources/is-dev` is a sentinel that makes
        // `(config/dev?)` return true so the tool-window loads the Vite dev
        // server at `http://localhost:5173` instead of the bundled webview at
        // `http://eca/...`. We only want this for local `buildPlugin`,
        // `clojureRepl` and `runIde` invocations — and never when the caller
        // explicitly asks for a prod-flavored local build via `-PprodBuild`
        // (see `bb build-prod-plugin` / `bb install-prod-plugin`).
        val isProdBuild = project.hasProperty("prodBuild")
        if (!isProdBuild &&
            (project.gradle.startParameter.taskNames.contains("buildPlugin") ||
             project.gradle.startParameter.taskNames.contains("clojureRepl") ||
             project.gradle.startParameter.taskNames.contains("runIde"))) {
            resources.srcDirs("src/main/dev-resources")
        }
    }
    test {
        java.srcDirs("tests")
    }
}

intellij {
    pluginName.set(properties("pluginName"))
    version.set(prop("platformVersion"))
    type.set(properties("platformType"))
    updateSinceUntilBuild.set(false)
}

changelog {
    version.set(properties("pluginVersion"))
    groups.set(emptyList())
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

tasks.register("classpath") {
    doFirst {
        println(sourceSets["main"].compileClasspath.asPath)
    }
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "17"
            apiVersion = "1.9"
            languageVersion = "1.9"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    wrapper {
        gradleVersion = prop("gradleVersion")
    }

    patchPluginXml {
        sinceBuild.set(properties("pluginSinceBuild"))

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        pluginDescription.set(
            projectDir.resolve("README.md").readText().lines().run {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end))
            }.joinToString("\n").run { markdownToHTML(this) }
        )

        // Get the latest available change notes from the changelog file
        changeNotes.set(provider {
            changelog.run {
                getOrNull(prop("pluginVersion")) ?: getLatest()
            }.toHTML()
        })
    }

    runPluginVerifier {
        ideVersions.set(prop("pluginVerifierIdeVersions").split(',').map { it.trim() }.filter { it.isNotEmpty() })
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        dependsOn("patchChangelog")
        token.set(System.getenv("JETBRAINS_TOKEN"))
        // pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels.set(listOf("default"))
    }

    buildSearchableOptions {
        enabled = false
    }

    clojureRepl {
        dependsOn("compileClojure")
        classpath.from(sourceSets.main.get().runtimeClasspath
                       + file("build/classes/kotlin/main")
                       + file("build/clojure/main"))
        // doFirst {
        //     println(classpath.asPath)
        // }
        forkOptions.jvmArgs = listOf("--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                                     "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
                                     "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                                     "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
                                     "--add-opens=java.base/java.lang=ALL-UNNAMED",
                                     "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader",
                                     "-Didea.mimic.jar.url.connection=true",
                                     "-Didea.force.use.core.classloader=true")
    }
}

clojure.builds.named("main") {
    classpath.from(sourceSets.main.get().runtimeClasspath
                   + file("build/classes/kotlin/main"))
    checkAll()
    aotAll()
    reflection.set("fail")
}

clojure.builds.named("test") {
    // Test compilation needs the main Clojure + Kotlin output on its
    // classpath (so test ns can `require` production code), plus the test
    // runtime classpath itself for the fixtures namespace.
    classpath.from(sourceSets.test.get().runtimeClasspath
                   + file("build/classes/kotlin/main")
                   + file("build/clojure/main")
                   + file("build/classes/kotlin/test"))
    checkAll()
    // No `aotAll()` — keeps test compilation fast and avoids leaking AOT
    // classes into the test-runtime jar. Reflection warnings are surfaced
    // but do not fail the build; the main build is the strict gate.
    reflection.set("warn")
}

// Dedicated test task for the Clojure unit + integration suite.
//
// Why a separate JavaExec instead of customizing the standard `test`:
// The `org.jetbrains.intellij` plugin retrofits every `Test` task with
// JBR (JetBrains Runtime) as the JVM, the IntelliJ sandbox, and
// `-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader`.
// PathClassLoader's URLConnections are not JarURLConnections, which
// makes Clojure's `RT.load` throw a ClassCastException before any
// deftest can run. JBR's dynamically linked binaries also fail to
// launch on NixOS hosts. By running our suite via JavaExec — which the
// IDE plugin does not touch — we keep the stock `test` task available
// for any future BasePlatformTestCase suite while sidestepping every
// IDE-test-runner assumption.
val clojureTest by tasks.registering(JavaExec::class) {
    description = "Runs the Clojure test suite via clojure.test."
    group = "verification"
    dependsOn("compileTestClojure")

    classpath = sourceSets.test.get().runtimeClasspath +
                files("build/classes/kotlin/main") +
                files("build/clojure/main") +
                files("build/clojure/test")

    mainClass.set("clojure.main")

    // -m invokes (-main) on the named namespace, which discovers every
    // `*_test.clj` file under src/test/clojure and runs its deftests.
    args("-m", "eca-intellij.test-runner")
}

// `bb test` is wired to `./gradlew test`. Hook clojureTest into the
// existing alias so the bb task keeps working without user-visible
// changes, and disable the IntelliJ-plugin-managed `test` body itself
// (no BasePlatformTestCase suite yet; reactivate when one lands).
tasks.named("test") {
    dependsOn("clojureTest")
    enabled = false
}

// checkTestClojure reads the same output dir that compileTestClojure writes
// to (build/clojure/test); declare the ordering edge so Gradle's task graph
// validator stops complaining about an implicit dependency.
tasks.named("checkTestClojure") {
    mustRunAfter("compileTestClojure")
}
