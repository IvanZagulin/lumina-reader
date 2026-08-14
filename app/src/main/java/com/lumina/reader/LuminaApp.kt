package com.lumina.reader

import android.app.Application
import com.lumina.reader.core.database.AppDatabase

class LuminaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Room Database
        AppDatabase.getDatabase(this)
    }
}
