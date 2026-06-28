plugins {
    // Let Gradle download a matching JDK toolchain (e.g. JDK 21) automatically
    // when one isn't installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "webdx"
