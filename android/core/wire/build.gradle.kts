plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "blog.nextlab.echo.core.wire"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// No Compose, no Android framework types, no Firebase. Yosegi is bytes in and bytes out, so
// the whole of it runs under plain JVM unit tests — which is what makes a hundred thousand
// fuzz cases per run affordable. A codec that needs a device to test is a codec that gets
// tested rarely.
dependencies {
    testImplementation(libs.junit)
    // ベクタは JSON。Android 同梱の org.json は単体テストでは空の殻なので、本物を入れる。
    testImplementation(libs.json)
}
