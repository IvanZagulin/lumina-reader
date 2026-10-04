import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Compose Multiplatform UI of Lumina Reader, shared by the Android app (:app)
// and the iPhone app. Packages stay com.lumina.reader.ui.*, so moving a file
// here from :app does not change any import. The UI moves bottom-up (stage 8):
// the design system (theme, fonts, components) first, screens later.
//
// It produces the only Kotlin framework ("LuminaUI") that the Xcode host app
// links; :shared is embedded in it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LuminaUI"
            isStatic = true
            binaryOption("bundleId", "com.lumina.reader.LuminaUI")
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // api: public signatures here use :shared types (DownloadState, BookFormat).
            api(project(":shared"))
            implementation(libs.kotlinx.coroutines.core)
            // On Android these resolve to the androidx.compose 1.11.2 / material3
            // 1.4.0 / material-icons-extended 1.7.6 artifacts :app already uses.
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
            implementation(libs.jb.compose.material.icons.extended)
            implementation(libs.jb.compose.resources)
            implementation(libs.coil3.compose)
            // Calendar dates in common code (reader bookmark and quote dates); api because
            // relativeDateLabel takes a kotlinx.datetime.TimeZone.
            api(libs.kotlinx.datetime)
            // Multiplatform ViewModel (`viewModel { }`) for the shared screens (stage 8b+).
            implementation(libs.jb.lifecycle.viewmodel.compose)
        }
        androidMain.dependencies {
            // WindowCompat (system bar appearance) and cover colours (Palette),
            // the same artifacts :app used for them before.
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.palette.ktx)
            implementation(libs.coil3.network.okhttp)
            // BackHandler behind PlatformBackHandler (:app already uses it).
            implementation(libs.androidx.activity.compose)
        }
        iosMain.dependencies {
            implementation(libs.coil3.network.ktor3)
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // JUnit 4 for the JVM run of the tests (testDebugUnitTest), as in :shared.
        androidUnitTest.dependencies {
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "com.lumina.reader.sharedui"
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

// The UI fonts (Onest, Lora) are Compose Multiplatform resources, so Android
// (assets of this library) and iOS (the app bundle, copied by
// embedAndSignAppleFrameworkForXcode) load the same files.
compose.resources {
    packageOfResClass = "com.lumina.reader.sharedui.resources"
    generateResClass = always
}
