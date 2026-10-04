package com.lumina.reader.core.library

import okio.Path

/** Relative to the current app container, or absolute paths as they are. */
actual fun resolveStoredLibraryPath(stored: String): Path = IosLibrary.files.resolve(stored)
