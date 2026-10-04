package com.lumina.reader.ui

import androidx.compose.runtime.Composable

/**
 * Handles the system "back" gesture while [enabled].
 *
 * Compose Multiplatform 1.11 has no common back handler, so this is a seam:
 * Android forwards to `androidx.activity.compose.BackHandler`; on iPhone there
 * is no system back button, and screens are dismissed by their own controls,
 * so the iOS side does nothing.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
