package com.lumina.reader.ui.transition

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.preferences.AppUiPreferences
import com.lumina.reader.core.preferences.get
import com.lumina.reader.ui.components.BookCoverModel
import com.lumina.reader.ui.components.toCoverModel
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.rememberReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wraps the reader (spec §6.3). It records the reader into a layer so the
 * close can start from a snapshot, hands the reader a `requestClose` for its
 * back arrow, and drives the predictive-back preview (API 34+): the reader
 * shrinks to 0.9 with rounded corners over the quarter-resolution library.
 * Only seams S1–S3 of the reader are used: the reader's own BackHandlers
 * (composed later, inside [content]) win while its overlays are open.
 */
@Composable
fun ReaderTransitionHost(
    bookId: Long,
    onExit: () -> Unit,
    content: @Composable (requestClose: () -> Unit) -> Unit
) {
    val transition = LocalBookTransition.current
    val context = LocalContext.current
    val uiPreferences = remember(context) { AppUiPreferences.get(context) }
    val openAnimation by uiPreferences.openAnimation.collectAsState()
    val reducedMotion = rememberReducedMotion()
    val mode = if (reducedMotion) OpenAnimation.OFF else openAnimation

    // Title, author and cover for the closing book; independent of reader internals.
    val cover by produceState<BookCoverModel?>(initialValue = null, bookId) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppDatabase.getDatabase(context).bookDao().getBookById(bookId)?.toCoverModel() }
                .getOrNull()
        }
    }

    val layer = rememberGraphicsLayer()
    val back = remember { Animatable(0f) }
    var swipeFromLeft by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val shiftPx = with(LocalDensity.current) { 16.dp.toPx() }
    val cornerPx = with(LocalDensity.current) { 28.dp.toPx() }

    val currentOnExit by rememberUpdatedState(onExit)
    val currentMode by rememberUpdatedState(mode)
    // Stable across recompositions, so the reader is not recomposed by phase changes.
    val requestClose: () -> Unit = remember(transition, layer, bookId) {
        {
            if (transition == null) {
                currentOnExit()
            } else {
                transition.close(layer, bookId, cover, currentMode, currentOnExit)
            }
        }
    }

    val predictiveEnabled = transition != null &&
        mode != OpenAnimation.OFF &&
        transition.phase == BookTransitionState.Phase.Idle
    PredictiveBackHandler(enabled = predictiveEnabled) { events ->
        try {
            events.collect { event ->
                swipeFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                back.snapTo(event.progress)
            }
            val p = back.value
            val start = CloseStart(
                scale = 1f - 0.1f * p,
                translationX = (if (swipeFromLeft) 1f else -1f) * shiftPx * p,
                cornerPx = cornerPx * p
            )
            if (transition != null) {
                transition.close(layer, bookId, cover, currentMode, currentOnExit, start)
            } else {
                currentOnExit()
            }
        } catch (cancelled: CancellationException) {
            scope.launch { back.animateTo(0f, LuminaMotion.settle()) }
            throw cancelled
        }
    }

    val peeking by remember { derivedStateOf { back.value > 0f } }
    val wall = Lumina.colors.wall
    val scrim = Lumina.colors.scrim
    Box(modifier = Modifier.fillMaxSize()) {
        if (peeking) {
            // The library behind the shrinking reader (quarter-resolution snapshot).
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val p = back.value
                        val shot = transition?.libraryShotSmall
                        scale(0.94f + 0.03f * p) {
                            if (shot != null) {
                                drawImage(
                                    image = shot,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(shot.width, shot.height),
                                    dstOffset = IntOffset.Zero,
                                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                )
                            } else {
                                drawRect(wall)
                            }
                        }
                        drawRect(scrim, alpha = 0.55f * (1f - 0.3f * p))
                    }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = back.value
                    scaleX = 1f - 0.1f * p
                    scaleY = scaleX
                    translationX = (if (swipeFromLeft) 1f else -1f) * 16.dp.toPx() * p
                    shape = RoundedCornerShape(28.dp * p)
                    clip = p > 0f
                }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        ) {
            content(requestClose)
        }
    }
}
