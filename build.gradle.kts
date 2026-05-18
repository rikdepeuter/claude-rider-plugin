plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.rixit"
version = "0.5.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target Rider. Bump this to whatever Rider build you have installed.
        rider("2024.3")
        // NOTE: deliberately NOT calling instrumentationTools() — the bundled
        // Swing UI Designer form instrumentation needs com.jetbrains.intellij
        // .java:java-compiler-ant-tasks at exactly the platform's build number
        // (e.g. 243.x for Rider 2024.3), and JetBrains rotates older artifacts
        // out of their public repo as new releases ship. We have no .form
        // files, so we don't need it.
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            // High ceiling — we use only stable platform APIs (tool windows,
            // AnAction, PersistentStateComponent, Swing). Tighten this later
            // if anything regresses on a future Rider.
            untilBuild = "299.*"
        }
    }
    // Skip the instrumentCode task entirely — it would try to pull the
    // compiler dependency above.
    instrumentCode = false
}

kotlin {
    jvmToolchain(21)
}

tasks {
    wrapper {
        gradleVersion = "8.10"
    }

 