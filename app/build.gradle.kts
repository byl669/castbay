import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The native AirPlay integration depends on a separately built UxPlay artifact.
// Keep the UI app buildable until that integration is supplied.
val enableNativeBuild = providers.gradleProperty("enableNativeBuild")
    .map(String::toBoolean)
    .orElse(false)

// The commit a test build comes from, for its version name; "local" outside a git checkout
// (e.g. building from the release's source archive).
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "local" } }

// Installing a downloaded update needs REQUEST_INSTALL_PACKAGES, declared in its own manifest so
// that store builds (-Pcastbay.selfUpdate=false) leave the permission out altogether.
val selfUpdate = (project.findProperty("castbay.selfUpdate") as String?)?.toBoolean() ?: true
androidComponents {
    onVariants { variant ->
        if (selfUpdate) variant.sources.manifests.addStaticManifestFile("src/selfUpdate/AndroidManifest.xml")
    }
}

android {
    namespace = "com.weenas.castbay"
    compileSdk = 37
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.weenas.castbay"
        minSdk = 23
        targetSdk = 35
        // Changed only for a release. versionName follows semantic versioning: the middle
        // number for new features (1.1.0), the last for fixes only (1.1.1), the first for big
        // changes. versionCode goes up by one with each release.
        versionCode = 121
        versionName = "1.8.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Only the ABIs the AirPlay library (airplay/) is built for: other libraries also ship x86
        // and x86_64 code, and an APK carrying those would install on x86 devices and crash there.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        // Where the update check, problem reports and statistics go: the website, then the relay
        // for networks that can't reach Cloudflare (util/Servers.kt). -Pcastbay.serverUrl=
        // http://<mac>:8787 tests a local `wrangler dev` of the website's Worker, without relay.
        val serverUrl = project.findProperty("castbay.serverUrl") as String?
        // Resources rather than BuildConfig constants, which Kotlin copies into their callers
        // (an incremental build then kept an old address).
        resValue("string", "server_url", serverUrl ?: "https://castbay.weenas.com")
        resValue("string", "relay_url", if (serverUrl == null) "https://cast.weenas.com" else "")
        // The in-app updater and the daily update check. Store builds (F-Droid) turn both off
        // with -Pcastbay.selfUpdate=false: the store updates the app, and doesn't allow it to.
        buildConfigField("boolean", "SELF_UPDATE", selfUpdate.toString())
        manifestPlaceholders["selfUpdate"] = selfUpdate.toString()

        if (enableNativeBuild.get()) {
            externalNativeBuild {
                cmake {
                    cppFlags("-std=c++17 -frtti -fexceptions")
                    arguments("-DANDROID_STL=c++_shared")
                }
            }
        }
    }

    // The release key comes from the environment (GitHub Actions secrets, or a local shell);
    // it never lives in the repository. Without it, release builds fall back to the debug key
    // so local release builds still install on test TVs.
    val releaseKeystore = System.getenv("CASTBAY_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("CASTBAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CASTBAY_KEY_ALIAS")
                keyPassword = System.getenv("CASTBAY_KEY_PASSWORD")
            }
        }
    }

    lint {
        // Calling an API newer than minSdk crashes on older TVs and cars (1.4.0 did on any key
        // before Android 12); lintVital, which CI and every release build run, stops on it.
        fatal += "NewApi"
    }

    buildTypes {
        debug {
            // Installs beside the release app, with the simulated sender (src/debug) for tests.
            applicationIdSuffix = ".debug"
            // e.g. 1.1.0-dev+6228385: the commit it was built from (test builds keep the version).
            versionNameSuffix = "-dev+" + gitCommit.get()
        }
        release {
            // R8 drops the unused parts of Compose, Media3 and Kotlin (about 2/3 of the dex).
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    if (enableNativeBuild.get()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    // No "Dependency metadata" block in the APK: AGP encrypts the dependency list for Google Play
    // only, and F-Droid rejects APKs carrying it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The reports address (resValue in defaultConfig); AGP 9 turns this off by default.
        resValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":airplay"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.tv:tv-foundation:1.0.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    // DLNA apps may cast DASH (.mpd), which GetProtocolInfo offers.
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")

    implementation("com.google.code.gson:gson:2.14.0")
    // QR codes on the About screen (a TV can't easily open a link; a phone can scan one).
    implementation("com.google.zxing:core:3.5.4")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only stubbed in JVM unit tests.
    testImplementation("org.json:json:20260814")
    // On-device tests of what needs real Android media (AudioTrack timing).
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
