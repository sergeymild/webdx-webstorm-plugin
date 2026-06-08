import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.intch"
version = "1.0.17"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against the WebStorm that is already installed locally,
        // so we don't download a multi-GB SDK.
        local("/Applications/WebStorm.app")

        // PSI for CSS/SCSS and for JS/TS imports lives in these bundled plugins.
        bundledPlugins("com.intellij.css", "JavaScript")

        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.intch.css-modules-scoped-usages"
        name = "CSS Modules Scoped Usages"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null } // no upper bound
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    // No settings UI in this plugin, and the headless run needed to index
    // searchable options fails on the bundled JBR — skip it.
    buildSearchableOptions {
        enabled = false
    }
}
