// AGP 9 has built-in Kotlin support, so the org.jetbrains.kotlin.android plugin is gone.
// AGP 9.3.1 bundles KGP 2.2.10; this raises it to the latest stable Kotlin.
// See https://developer.android.com/build/migrate-to-built-in-kotlin
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
