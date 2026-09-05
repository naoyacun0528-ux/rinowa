import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Google Services plugin fails the build outright when it cannot find a config, or
// finds one whose clients do not match the applicationId. Applying it only once a config
// is actually present means the project still builds for anyone who was never given one,
// and that changing the applicationId does not brick every build until Firebase catches
// up. Firebase engages the moment the file lands.
val firebaseConfigured = listOf(
    "google-services.json",
    "src/debug/google-services.json",
    "src/release/google-services.json",
).any { file(it).exists() }

if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.lifecycle("Firebase: no google-services.json found, Google Services plugin skipped")
}

// Release signing. keystore.properties is machine-local and gitignored, and the keystore
// itself lives outside the repository so it cannot be committed by accident. When the
// file is absent — a fresh clone, or the published source zip — the release build stays
// unsigned rather than failing, so the project still builds for anyone else.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "blog.nextlab.echo"
    compileSdk = 37

    defaultConfig {
        applicationId = "blog.nextlab.echo"
        // Android 8.0 (2017年8月)。24 から上げた。
        //
        // 24 のままだと、着信通知のチャンネルを作る処理（NotificationChannel は
        // API 26 以上）がバージョンの確認なしで呼ばれていて、**Android 7 の端末では
        // 着信が来た瞬間に落ちる**。同種のものが27箇所あった。
        //
        // 全部に if (SDK_INT >= O) を書く手もあるが、それは「動かす当てのない端末
        // 向けの分岐」を27個抱えるということ。切ったのは2016年8月以前の端末で、
        // 世界のシェアで2%程度。
        minSdk = 26
        targetSdk = 37
        // Versioning: fixes and small additions bump the patch (0.1.0 -> 0.1.1); a
        // substantial new feature bumps the minor (0.1.x -> 0.2.0).
        // Keep in sync with outputs/README.md.
        versionCode = 74
        versionName = "0.20.7"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // WebRTC ships native libraries for four ABIs and they dominate the APK. Test
            // builds are downloaded over mobile data every time something changes, and
            // every device in the test set is arm64 — so the other three are 35 MB of
            // nothing. Release keeps all of them until the App Bundle lands.
            ndk { abiFilters += "arm64-v8a" }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Under built-in Kotlin, kotlin.compilerOptions.jvmTarget defaults to targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    /**
     * One APK per architecture, plus a universal one.
     *
     * ## Why this exists now
     *
     * Play delivers only the slice a phone needs, so an App Bundle hides this problem
     * entirely. **Rinowa is handed out as a file over a URL**, and there the universal APK
     * is what somebody actually downloads — four copies of every native library, of which
     * their phone uses one.
     *
     * With E2EE the native payload roughly doubled, and the gap stopped being academic:
     * 14.6 MB became 33.5 MB for one architecture, but 44.7 MB became 120.3 MB for the
     * universal build. **The download that used to be merely wasteful became the reason
     * not to download.**
     *
     * The universal APK is kept because it is the one that works on anything, which matters
     * when handing a build to a device nobody has checked. The publish script picks arm64.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:haptics"))
    implementation(project(":core:analytics"))
    implementation(project(":core:model"))
    implementation(project(":core:wire"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)

    // Firebase. Versions come from the BoM; see libs.versions.toml for why Analytics is
    // not here yet.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.base)
    implementation(libs.play.services.nearby)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.webrtc)
    // E2EE。Matrix の Rust 暗号 SDK（Olm/Megolm）。Apache-2.0、既製 AAR。
    // 採用理由と実測は docs/RESEARCH_E2EE.md §2.4。
    implementation(libs.matrix.crypto)
    implementation(libs.tink)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
