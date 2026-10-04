package com.lumina.reader.core.library

/**
 * Android cannot build these without a `Context`, and the importer lives in
 * :app, so `LuminaApp.onCreate` installs them before any screen runs.
 */
internal actual fun defaultLibraryServices(): LibraryServices =
    error("AppServices.install(...) must run in LuminaApp.onCreate()")
