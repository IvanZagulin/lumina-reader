package com.lumina.reader.ui.transition

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * One step of a predictive back gesture: how far it has gone (0..1) and
 * whether it started at the left screen edge, which decides the side the
 * shrinking reader slides towards.
 */
class PlatformBackEvent(val progress: Float, val fromLeftEdge: Boolean)

/**
 * Handles a predictive back gesture while [enabled], with the contract of
 * `androidx.activity.compose.PredictiveBackHandler`: [onBack] collects the
 * gesture's [PlatformBackEvent]s; the flow completes when the gesture is
 * committed, and the coroutine is cancelled when it is abandoned.
 *
 * It is a seam because the progress stream is Android's (API 34+ back
 * preview): the reader shrinks over the library while the user swipes.
 * [com.lumina.reader.ui.PlatformBackHandler] has no progress, so it cannot
 * drive that preview.
 */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onBack: suspend (events: Flow<PlatformBackEvent>) -> Unit
)
