package com.lumina.reader.ui.social

/**
 * Android opens both the database and the circle's preference file through a
 * `Context`, so `LuminaApp.onCreate` installs them. A missing install is a
 * wiring bug, not a reason to show the circle as empty.
 */
internal actual fun defaultSocialServices(): SocialServices =
    error("SocialServicesHolder.install(...) must run in LuminaApp.onCreate()")
