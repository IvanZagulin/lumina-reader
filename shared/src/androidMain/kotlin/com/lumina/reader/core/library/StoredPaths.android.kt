package com.lumina.reader.core.library

import okio.Path
import okio.Path.Companion.toPath

/**
 * The stored path is already absolute on Android, so it is the file itself —
 * exactly what coverImageOf did before stored paths needed resolving.
 */
actual fun resolveStoredLibraryPath(stored: String): Path = stored.toPath()
