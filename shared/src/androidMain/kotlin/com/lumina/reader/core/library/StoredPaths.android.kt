package com.lumina.reader.core.library

import okio.Path
import okio.Path.Companion.toOkioPath
import java.io.File

/** Exactly AndroidLibraryFiles.resolve: the stored path is already absolute. */
actual fun resolveStoredLibraryPath(stored: String): Path = File(stored).toOkioPath()
