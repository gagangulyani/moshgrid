import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

// Opt-in instrumentation of the minified variant; production release signing is unchanged.
val releaseUiTests = providers.gradleProperty("releaseUiTests").orNull == "true"
val composerUiTests = providers.gradleProperty("composerUiTests").orNull == "true"
require(!(releaseUiTests && composerUiTests)) { "Select one UI test configuration" }

val githubReleaseCertSha256 = providers
    .environmentVariable("BITCHAT_GITHUB_RELEASE_CERT_SHA256")
    .orElse(providers.gradleProperty("BITCHAT_GITHUB_RELEASE_CERT_SHA256"))
    .orElse("")
val normalizedGithubReleaseCertSha256 = githubReleaseCertSha256.get()
    .replace(":", "")
    .trim()
    .lowercase()
require(
    normalizedGithubReleaseCertSha256.isEmpty() ||
        normalizedGithubReleaseCertSha256.matches(Regex("[a-f0-9]{64}"))
) {
    "BITCHAT_GITHUB_RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint"
}

// MoshGrid: path to the release keystore, set moshgridReleaseStoreFile in ~/.gradle/gradle.properties.
val moshgridReleaseStoreFile: String? = providers.gradleProperty("moshgridReleaseStoreFile").orNull

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    if (releaseUiTests) testBuildType = "release"

    defaultConfig {
        applicationId = "com.moshgrid.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 11
        versionName = "1.4.1-moshgrid"
        buildConfigField(
            "String",
            "GITHUB_RELEASE_CERT_SHA256",
            "\"$normalizedGithubReleaseCertSha256\""
        )
        // MoshGrid: Sentry DSN comes from -PsentryDsn=... or ~/.gradle/gradle.properties. Empty disables Sentry.
        buildConfigField("String", "SENTRY_DSN", "\"${(project.findProperty("sentryDsn") as String?) ?: ""}\"")

        testInstrumentationRunner = if (composerUiTests) {
            "com.bitchat.android.ui.ComposerPanicTestRunner"
        } else if (releaseUiTests) {
            "com.bitchat.android.ui.ReleaseScannerTestRunner"
        } else {
            "com.bitchat.android.ui.PerformanceTestRunner"
        }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    if (composerUiTests) {
        sourceSets.getByName("androidTest").kotlin.directories.add("src/composerAndroidTest/java")
    }

    signingConfigs {
        if (moshgridReleaseStoreFile != null) {
            create("moshgridRelease") {
                storeFile = file(moshgridReleaseStoreFile)
                storePassword = providers.gradleProperty("moshgridReleaseStorePassword").orNull
                keyAlias = providers.gradleProperty("moshgridReleaseKeyAlias").orNull
                keyPassword = providers.gradleProperty("moshgridReleaseKeyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            if (composerUiTests) applicationIdSuffix = ".composerTest"
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            if (releaseUiTests) {
                signingConfig = signingConfigs.getByName("debug")
                proguardFiles("release-instrumentation.pro")
            } else if (moshgridReleaseStoreFile != null) {
                // MoshGrid release key, read from ~/.gradle/gradle.properties (never committed).
                signingConfig = signingConfigs.getByName("moshgridRelease")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            vcsInfo {
                // BUILDINFO.json and attestations carry the verified commit
                // without depending on host-specific Git/worktree paths.
                include = false
            }
        }
    }

    // APK splits for GitHub releases - creates arm64, x86_64, and universal APKs
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

composeCompiler {
    // Kotlin 2.4.10's optional Compose group-key mapping depends on unspecified
    // class-file iteration order. Keep the normal R8 mapping, but omit that
    // augmentation until its producer is deterministic across clean builds.
    includeComposeMappingFile.set(false)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    // MoshGrid: crash reporting (MIT) and offline venue maps (BSD)
    implementation(libs.sentry.android.core)
    implementation(libs.maplibre.android)
    implementation(libs.zxing.core)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // WorkManager for background APK downloads
    implementation(libs.androidx.work.runtime.ktx)

    // HTTP Server for hotspot APK sharing
    implementation(libs.nanohttpd)



    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // EXIF orientation handling for images
    implementation(libs.androidx.exifinterface)
    
    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Robolectric's own downloader fetches each Android runtime jar (about 200 MB) on first use and
// fails on cold CI runners. Let Gradle fetch them instead (retries, cache, checksum verification)
// and run Robolectric offline from that folder. One jar per SDK level the tests run on.
// One configuration per jar: in a single one Gradle would keep only the highest version.
val robolectricRuntimes = listOf(
    "5.0.2_r3-robolectric-r0-i7",    // SDK 21, which some tests still load
    "9-robolectric-4913185-2-i7",    // SDK 28
    "12.1-robolectric-8229987-i7",   // SDK 32
    "13-robolectric-9030017-i7",     // SDK 33
    "15-robolectric-12650502-i7",    // SDK 35, the default for tests without @Config
).map { version ->
    configurations.create("robolectricRuntime" + version.substringBefore("-robolectric").replace(".", "_")) {
        isTransitive = false
    }.also { dependencies.add(it.name, "org.robolectric:android-all-instrumented:$version") }
}
val robolectricJars = layout.buildDirectory.dir("robolectric-jars")
val fetchRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricRuntimes)
    into(robolectricJars)
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(fetchRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricJars.get().asFile.absolutePath)
}
