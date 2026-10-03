import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-neutral core of Lumina Reader, shared by the Android app (:app) and
// the iPhone app. Packages stay com.lumina.reader.*, so moving a file here from
// :app does not change any import.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    // Stability metadata for the classes that composables in :app / :sharedUi
    // take as parameters (download and TTS states, enums); without the Compose
    // compiler they would be treated as unstable.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // No framework here: :sharedUi produces the single "LuminaUI" framework and
    // embeds this module, so the Kotlin runtime is linked into the app only once.
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // On Android these resolve to androidx.compose runtime / ui-graphics /
            // ui-text 1.11.2, the versions :app already uses.
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.ui.graphics)
            // api: public signatures of :shared use these types (AnnotatedString
            // in BionicReadingHelper; okio Source / BufferedSource in TextEncoding
            // and DecodingCharReader). Same artifacts at runtime as implementation.
            api(libs.jb.compose.ui.text)
            api(libs.okio)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // JVM-only tests: parity of the common text code with java.nio /
        // java.util formatters (they run with testDebugUnitTest on Linux CI).
        androidUnitTest.dependencies {
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "com.lumina.reader.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}
