plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}

// Ktor's OkHttp engine (3.6.0) asks for OkHttp 5.5.0, which needs compileSdk 37 and
// so AGP 9.1 — the same wall as lifecycle 2.11. The app stays on the OkHttp it has
// always shipped; the engine only uses the stable client API that 4.12 and 5.x share
// (OpdsHttpSmokeTest runs a real request through this exact combination).
// Drop this together with the compileSdk 37 / AGP 9 migration.
subprojects {
    configurations.configureEach {
        resolutionStrategy.force("com.squareup.okhttp3:okhttp:${libs.versions.okhttp.get()}")
    }
}
