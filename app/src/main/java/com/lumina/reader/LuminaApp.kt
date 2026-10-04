package com.lumina.reader

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.library.AndroidLibraryImports
import com.lumina.reader.core.library.AppServices
import com.lumina.reader.core.library.BookImporter
import com.lumina.reader.core.library.LibraryRepository
import com.lumina.reader.core.library.LibraryServices
import com.lumina.reader.core.library.ReaderServices
import com.lumina.reader.core.preferences.AppUiPreferences
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.preferences.ReaderPreferences
import com.lumina.reader.core.preferences.get
import com.lumina.reader.core.tts.TtsControllerReadAloud
import com.lumina.reader.platform.AppInfo

class LuminaApp : Application() {
    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        // Compose 1.10+ widens a long-press selection to a whole "entity"
        // (address, date, phone number) through the TextClassifier. The reader
        // selects words in book text the way Compose 1.7 did, so keep that.
        // Must be set before any Compose code runs.
        ComposeFoundationFlags.isSmartSelectionEnabled = false
        super.onCreate()
        // Common code (:shared) reads the version from here.
        AppInfo.init(BuildConfig.VERSION_NAME)
        // Initialize Room Database
        AppDatabase.getDatabase(this)
        // The shared library screen reads its services from here; on Android
        // every one of them needs a Context, and the importer lives in :app.
        // Built on first use, not now: the importer and the preference files
        // must not be opened before a screen asks for them.
        AppServices.install {
            LibraryServices(
                bookDao = AppDatabase.getDatabase(this).bookDao(),
                repository = LibraryRepository(this),
                libraryPreferences = LibraryPreferences(this),
                uiPreferences = AppUiPreferences.get(this),
                imports = AndroidLibraryImports(BookImporter.get(this))
            )
        }
        // The reader's services, built when the first book opens (the moment
        // the reader used to open them itself, read-aloud included).
        AppServices.installReader {
            val db = AppDatabase.getDatabase(this)
            ReaderServices(
                bookDao = db.bookDao(),
                bookmarkDao = db.bookmarkDao(),
                statsDao = db.readingStatsDao(),
                preferences = ReaderPreferences(this),
                readAloud = TtsControllerReadAloud(this)
            )
        }
    }
}
