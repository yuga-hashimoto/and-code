plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("com.android.test") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("androidx.baselineprofile") version "1.3.3" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.7" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    id("com.diffplug.spotless") version "6.25.0"
    id("jacoco")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

// Play CI checks application sources against the existing main-branch debt separately from
// the root task, preserving the existing GitHub and F-Droid build configuration.
tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektPlaySources") {
    setSource(files("app/src/main/java", "app/src/play/java", "app/src/play/kotlin"))
    include("**/*.kt", "**/*.kts")
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline.set(file("$rootDir/config/detekt/play-baseline.xml"))
    buildUponDefaultConfig = true
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.2.1")
            .setEditorConfigPath("$rootDir/.editorconfig")
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.2.1")
    }
}
