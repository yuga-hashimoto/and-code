plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val repoRoot = rootProject.projectDir
val githubClientId =
    (
        System.getenv("GITHUB_CLIENT_ID")
            ?: findProperty("GITHUB_CLIENT_ID")?.toString()
            ?: ""
    ).trim()
val generatedRuntimeAssets = rootProject.layout.buildDirectory.dir("generated/runtime-assets")
val generatedRuntimeJni = rootProject.layout.buildDirectory.dir("generated/runtime-jni")

val prepareOpenCodeRuntimeAssets =
    tasks.register<Exec>("prepareOpenCodeRuntimeAssets") {
        inputs.file(repoRoot.resolve("runtime_tools/termux_assets.py"))
        inputs.file(repoRoot.resolve("runtime_tools/termux_assets.lock.json"))
        inputs.file(repoRoot.resolve("scripts/prepare_android_runtime_assets.py"))
        outputs.dir(generatedRuntimeAssets)
        commandLine(
            "python3",
            repoRoot.resolve("scripts/prepare_android_runtime_assets.py").absolutePath,
            "--output-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--lock-file",
            repoRoot.resolve("runtime_tools/termux_assets.lock.json").absolutePath,
        )
    }

val prepareOpenCodeRuntimeNativeLibs =
    tasks.register<Exec>("prepareOpenCodeRuntimeNativeLibs") {
        dependsOn(prepareOpenCodeRuntimeAssets)
        inputs.dir(generatedRuntimeAssets)
        inputs.file(repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py"))
        outputs.dir(generatedRuntimeJni)
        commandLine(
            "python3",
            repoRoot.resolve("scripts/prepare_android_runtime_native_libs.py").absolutePath,
            "--linux-assets-dir",
            generatedRuntimeAssets.get().asFile.absolutePath,
            "--output-dir",
            generatedRuntimeJni.get().asFile.absolutePath,
        )
    }

val releaseStoreFile =
    (
        System.getenv("AND_CODE_STORE_FILE")
            ?: findProperty("AND_CODE_STORE_FILE")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseStorePassword =
    (
        System.getenv("AND_CODE_STORE_PASSWORD")
            ?: findProperty("AND_CODE_STORE_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseKeyAlias =
    (
        System.getenv("AND_CODE_KEY_ALIAS")
            ?: findProperty("AND_CODE_KEY_ALIAS")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val releaseKeyPassword =
    (
        System.getenv("AND_CODE_KEY_PASSWORD")
            ?: findProperty("AND_CODE_KEY_PASSWORD")?.toString()
    )
        ?.takeIf { it.isNotBlank() }
val hasReleaseSigning =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() } &&
        releaseStoreFile!!.let { path ->
            val resolved =
                if (File(path).isAbsolute) {
                    File(path)
                } else {
                    File(rootProject.projectDir, path)
                }
            resolved.isFile
        }

android {
    namespace = "com.yugahashimoto.andcode"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yugahashimoto.andcode"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "1.1.22"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$githubClientId\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                val storeFilePath = releaseStoreFile!!
                storeFile =
                    if (File(storeFilePath).isAbsolute) {
                        File(storeFilePath)
                    } else {
                        File(rootProject.projectDir, storeFilePath)
                    }
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    lint {
        // The androidx.startup Initializers are deliberately not auto-started: the manifest removes
        // their <meta-data> entries with tools:node="remove" so they cannot run inside
        // InitializationProvider (which fires before Application.onCreate, where the dependencies
        // they need are built). AndCodeApplication initializes them itself instead. Without this,
        // lintVitalRelease fails the check and no release APK can be produced.
        disable += "EnsureInitializerMetadata"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs +=
            listOf(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main").jniLibs.srcDir(generatedRuntimeJni)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        noCompress += "tflite"
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareOpenCodeRuntimeNativeLibs)
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")

    // QR code scanning for connection setup
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // TensorFlow Lite (wake word detection)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // DI
    implementation("io.insert-koin:koin-android:4.0.1")
    implementation("io.insert-koin:koin-androidx-compose:4.0.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Baseline Profiles
    baselineProfile(project(":benchmark"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
