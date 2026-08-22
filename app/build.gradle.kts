plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val ciVersionCode = providers.gradleProperty("versionCodeOverride").orNull?.toIntOrNull()
val ciVersionName = providers.gradleProperty("versionNameOverride").orNull
val ciSigningStoreFile = providers.gradleProperty("signingStoreFile").orNull
val ciSigningStorePassword = providers.gradleProperty("signingStorePassword").orNull
val ciSigningKeyAlias = providers.gradleProperty("signingKeyAlias").orNull
val ciSigningKeyPassword = providers.gradleProperty("signingKeyPassword").orNull

android {
    namespace = "com.lumina.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumina.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 2
        versionName = ciVersionName ?: "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        getByName("debug") {
            if (ciSigningStoreFile != null) {
                storeFile = file(ciSigningStoreFile)
                storePassword = requireNotNull(ciSigningStorePassword)
                keyAlias = requireNotNull(ciSigningKeyAlias)
                keyPassword = requireNotNull(ciSigningKeyPassword)
            }
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isDebuggable = false
            if (ciSigningStoreFile != null) {
                // CI uses the same update key as previous APKs so Android accepts
                // the optimized release build as an in-place application update.
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room DB
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Coil
    implementation(libs.coil.compose)

    // Gson
    implementation(libs.gson)

    // Network
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Debug & Test
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
