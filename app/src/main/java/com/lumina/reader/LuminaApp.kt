package com.lumina.reader

import android.app.Application
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import com.lumina.reader.core.database.AppDatabase
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
    }
}
