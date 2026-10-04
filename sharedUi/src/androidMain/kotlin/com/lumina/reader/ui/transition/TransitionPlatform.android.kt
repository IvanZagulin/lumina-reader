package com.lumina.reader.ui.transition

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The androidx handler itself, so the back preview, its cancellation and its
 * precedence over the reader's own BackHandlers stay exactly what they were.
 */
@Composable
actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (events: Flow<PlatformBackEvent>) -> Unit
) {
    PredictiveBackHandler(enabled = enabled) { events ->
        onBack(events.map { event -> PlatformBackEvent(event.progress, event.swipeEdge == BackEventCompat.EDGE_LEFT) })
    }
}
