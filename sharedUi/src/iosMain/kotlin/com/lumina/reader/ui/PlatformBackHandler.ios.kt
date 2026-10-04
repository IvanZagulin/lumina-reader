package com.lumina.reader.ui

import androidx.compose.runtime.Composable

/** iPhone has no system back button; screens close through their own controls. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Deliberately empty.
}
