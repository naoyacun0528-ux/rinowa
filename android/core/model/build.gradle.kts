plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "blog.nextlab.echo.core.model"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    // **ここに増やさない。**
    //
    // このモジュールが依存してよいのは、Compose が「この値は変わらない」と
    // 判断するための @Immutable だけ。Firebase も、暗号も、画面も入れない。
    // 入れた瞬間に、下の層が上の層を知っていることになる。
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)

    // 公開している型の署名に出てくるので api。implementation にすると、
    // 使う側が ImmutableList を名指しできない。
    api(libs.kotlinx.collections.immutable)

    testImplementation(libs.junit)
}
