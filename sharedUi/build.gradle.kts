// Compose Multiplatform UI of the iPhone app. iOS targets only for now: the
// Android app keeps its own UI in :app until the UI moves here (stage 8), so
// this module adds nothing to the Android build. It produces the only Kotlin
// framework ("LuminaUI") that the Xcode host app links; :shared is embedded.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
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
            implementation(project(":shared"))
            implementation(libs.jb.compose.runtime)
            implementation(libs.jb.compose.foundation)
            implementation(libs.jb.compose.ui)
            implementation(libs.jb.compose.material3)
        }
    }
}
