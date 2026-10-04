package com.lumina.reader.core.library

/**
 * Android cannot build these without a `Context`, and the importer lives in
 * :app, so `LuminaApp.onCreate` installs them before any screen runs.
 */
internal actual fun defaultLibraryServices(): LibraryServices =
    error("AppServices.install(...) must run in LuminaApp.onCreate()")

/** As for the library: read-aloud (`TtsController`) lives in :app, which installs these. */
internal actual fun defaultReaderServices(): ReaderServices =
    error("AppServices.installReader(...) must run in LuminaApp.onCreate()")
