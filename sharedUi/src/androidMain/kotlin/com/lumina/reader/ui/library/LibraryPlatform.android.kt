package com.lumina.reader.ui.library

import android.os.Build

/** RenderEffect-backed blur arrived in Android 12 (API 31). */
internal actual val coverGlowBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= 31
