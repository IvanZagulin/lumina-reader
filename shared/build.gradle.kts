import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-neutral core of Lumina Reader, shared by the Android app (:app) and
// the iPhone app. Packages stay com.lumina.reader.*, so moving a file here from
// :app does not change any import.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    // Stability metadata for the classes that composables in :app / :sharedUi
    // take as parameters (books, reader settings, download and TTS states);
    // without the Compose compiler they would be treated as unstable.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Room: the database, its entities and DAOs live in commonMain; KSP
    // generates the implementation for each target (see dependencies below).
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
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
            // On Android these resolve to androidx.compose runtime / ui-graphics /
            // ui-text 1.11.2, the versions :app already uses.
            implementation(libs.jb.compose.runtime)
            // api: public signatures of :shared use these types (Flow in the DAOs
            // and preferences; Color in ReadingTheme; AnnotatedString in
            // BionicReadingHelper; okio Source / BufferedSource in TextEncoding and
            // DecodingCharReader; RoomDatabase, the DAOs' annotations and
            // DataStore<Preferences> in the data layer). Same artifacts at
            // runtime as implementation.
            api(libs.kotlinx.coroutines.core)
            api(libs.jb.compose.ui.graphics)
            api(libs.jb.compose.ui.text)
            api(libs.okio)
            api(libs.androidx.room.runtime)
            api(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.serialization.json)
            // api: luminaHttpClient and the constructors of OpdsRepository, BookDownloader
            // and AiClient expose io.ktor.client.HttpClient to :app and :sharedUi.
            api(libs.ktor.client.core)
        }
        androidMain.dependencies {
            // The Android engine is OkHttp, as the OPDS client and downloader always used.
            implementation(libs.ktor.client.okhttp)
            // The Context.preferencesDataStore delegates that have always created
            // the app's settings files (filesDir/datastore/<name>.preferences_pb).
            implementation(libs.androidx.datastore.preferences)
            // RoomDatabase.withTransaction for LibraryRepository, as :app has
            // always used it (same artifact :app already ships).
            implementation(libs.androidx.room.ktx)
        }
        iosMain.dependencies {
            // Android uses the framework SQLite (Room compatibility mode, no
            // driver); the iPhone app bundles SQLite.
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.serialization.protobuf)
            // NSURLSession through Ktor: OPDS feeds, downloads and the assistant on iPhone.
            implementation(libs.ktor.client.darwin)
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

// Exported schemas (one JSON per database version). CI uploads the folder as
// the "room-schemas" artifact; commit it so later schema changes are reviewed
// against it.
room {
    schemaDirectory("$projectDir/schemas")
}

// Room's annotation processor for every target that compiles the database:
// Android (the app) and both iOS targets (device and simulator).
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
