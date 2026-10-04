package com.lumina.reader.ui.transition

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * Deliberately empty, like [com.lumina.reader.ui.PlatformBackHandler] on iOS:
 * the reader closes through its own back arrow (with the same fly-back
 * animation). Compose's own `PredictiveBackHandler` would turn on an edge
 * swipe here, but the reader's inner back handlers (sheets, search, the
 * selection menu) are no-ops on iOS, so that swipe would close the whole
 * reader even with one of them open. Both seams have to switch together.
 */
@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (events: Flow<PlatformBackEvent>) -> Unit
) {
    // Deliberately empty.
}
