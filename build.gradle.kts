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
        // - com.intellij.css          : plain CSS
        // - org.jetbrains.plugins.sass: SCSS / SASS file types & PSI
        // - org.jetbrains.plugins.less: LESS file type & PSI
        // - JavaScript                : JS/TS/JSX/TSX PSI and import resolution
        // - com.intellij.modules.json : required dependency of the JavaScript plugin
        bundledPlugins(
            "com.intellij.css",
            "org.jetbrains.plugins.sass",
            "org.jetbrains.plugins.less",
            "JavaScript",
            "com.intellij.modules.json",
        )

        pluginVerifier()

        // Platform test fixtures (BasePlatformTestCase, CodeInsightTestFixture, …).
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Light fixtures need the headless UI bits available.
    systemProperty("java.awt.headless", "true")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
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
