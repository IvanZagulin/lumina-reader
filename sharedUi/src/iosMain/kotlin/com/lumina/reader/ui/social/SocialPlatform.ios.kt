package com.lumina.reader.ui.social

import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.library.IosLibrary
import com.lumina.reader.core.preferences.SocialPreferences

/** The iPhone builds the circle's services from the app's own database and settings on first use. */
internal actual fun defaultSocialServices(): SocialServices = SocialServices(
    preferences = SocialPreferences(),
    bookDao = AppDatabase.getDatabase().bookDao(),
    // The container's one pipeline, the same one imports and downloads use.
    importPipeline = IosLibrary.importPipeline
)
