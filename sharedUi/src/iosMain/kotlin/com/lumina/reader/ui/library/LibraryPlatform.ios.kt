package com.lumina.reader.ui.library

/** Skia blurs on every iOS version the app supports (deployment target 15.0). */
internal actual val coverGlowBlurSupported: Boolean
    get() = true
