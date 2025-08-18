fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)
fun prop(name: String): String {
    return properties(name).get()
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("dev.clojurephant.clojure") version "0.8.0"
    id("org.jetbrains.changelog") version "1.3.1"
}

group = prop("pluginGroup")
version = prop("pluginVersion")

repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
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
    intellijPlatform {
        val type = providers.gradleProperty("platformType")
        val ver  = providers.gradleProperty("platformVersion")
        create(type, ver)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = prop("pluginSinceBuild")
        }

        // Get the latest available change notes from the changelog file
        changeNotes.set(provider {
            changelog.run {
                getOrNull(prop("pluginVersion")) ?: getLatest()
            }.toHTML()
        })
    }
}

changelog {
    version.set(properties("pluginVersion"))
    groups.set(emptyList())
}

java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

tasks.register("classpath") {
    doFirst {
        println(sourceSets["main"].compileClasspath.asPath)
    }
}



tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "21"
            apiVersion = "1.9"
            languageVersion = "1.9"
            freeCompilerArgs = listOf("-Xjvm-default=all")
        }
    }

    wrapper {
        gradleVersion = prop("gradleVersion")
    }
}

clojure.builds.named("main") {
    classpath.from(sourceSets.main.get().runtimeClasspath.asPath)
    checkAll()
    aotAll()
    reflection.set("fail")
}
