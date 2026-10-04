package com.lumina.reader.ui.library

/**
 * Whether `Modifier.blur` actually paints something. Android renders it only
 * from API 31 (below that the modifier is a no-op that still costs a layer),
 * every iOS version this app supports does.
 *
 * Used by the «Продолжить чтение» card for the blurred cover glow.
 */
internal expect val coverGlowBlurSupported: Boolean
