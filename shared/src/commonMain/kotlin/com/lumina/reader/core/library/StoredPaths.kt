package com.lumina.reader.core.library

import okio.Path

/**
 * The file a stored `Book.filePath` / `Book.coverPath` refers to, for code
 * that only has the stored string — the shared UI turning a cover into an
 * image, the reader opening its book.
 *
 * Android stores absolute paths, so this is the path itself. The iPhone stores
 * paths relative to the app container, whose absolute location changes between
 * app updates (see IosLibraryFiles): handing such a string to Coil or to the
 * file system as it is finds nothing, so it must be resolved first.
 */
expect fun resolveStoredLibraryPath(stored: String): Path
