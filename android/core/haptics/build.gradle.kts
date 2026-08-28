plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "blog.nextlab.echo.core.haptics"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
    // 調整表を JSON で書き出す。Android 同梱の org.json は単体テストでは空の殻。
    testImplementation(libs.json)
}
