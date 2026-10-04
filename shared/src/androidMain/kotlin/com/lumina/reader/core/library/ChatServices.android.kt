package com.lumina.reader.core.library

/**
 * Android cannot build these without a `Context`, and the importer behind the
 * downloads lives in :app, so `LuminaApp.onCreate` installs them.
 */
internal actual fun defaultChatServices(): ChatServices =
    error("ChatServicesHolder.install(...) must run in LuminaApp.onCreate()")
