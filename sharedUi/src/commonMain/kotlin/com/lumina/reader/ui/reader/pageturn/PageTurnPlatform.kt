package com.lumina.reader.ui.reader.pageturn

import androidx.compose.runtime.Composable

/**
 * Whether the page-curl turn may be offered. Android drops it on devices that
 * report low RAM (it renders two page layers per frame); every iPhone the app
 * runs on can draw it.
 */
@Composable
expect fun rememberCurlSupported(): Boolean
