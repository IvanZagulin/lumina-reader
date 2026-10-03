package com.lumina.reader.ui.transition

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.lumina.reader.ui.theme.LoraFamily
import com.lumina.reader.ui.components.BookCover
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaShape

/** One frame of the transition geometry, derived from the animatables (draw phase only). */
internal class TransitionFrame(
    /** The cover's bounds. */
    val page: Rect,
    /** The reader snapshot's bounds while closing. */
    val readerPage: Rect,
    val cornerPx: Float,
    val coverRotationY: Float,
    val coverShading: Float,
    val coverAlpha: Float,
    val pageAlpha: Float,
    /** Elevation of the flying book in dp (4 → 24 over the flight). */
    val shadowDp: Float,
    val hinge: Float,
    val expand: Float,
    /** «Приятного чтения» on the page: appears as the cover opens, never while closing. */
    val greetingAlpha: Float
)

internal fun BookTransitionState.frame(arcPx: Float, closedCornerPx: Float): TransitionFrame {
    val f = flight.value
    val e = expand.value
    val v = vanish.value
    val fade = readerFade.value
    val source = if (sourceFollowsLibraryScale) {
        TransitionGeometry.scaleAbout(sourceRect, fullRect.center, librarySourceScale())
    } else {
        sourceRect
    }
    // The cover grows straight (no arc, no tilt) from its slot to the showcase.
    var book = TransitionGeometry.lerpRect(source, stageRect, f)
    if (v > 0f) book = TransitionGeometry.scaleAbout(book, book.center, 1f - 0.4f * v)
    // Only closing draws a page: the reader snapshot, cross-fading into the cover.
    val page = TransitionGeometry.lerpRect(book, endRect, e)
    return TransitionFrame(
        page = book,
        readerPage = page,
        cornerPx = TransitionGeometry.lerp(closedCornerPx, endCornerPx, e),
        coverRotationY = 0f,
        coverShading = 0f,
        coverAlpha = (if (closing) 1f - fade else 1f) * (1f - v),
        pageAlpha = if (closing) fade * (1f - v) else 0f,
        shadowDp = TransitionGeometry.lerp(4f, 24f, f) * (1f - v),
        hinge = 0f,
        expand = e,
        greetingAlpha = if (closing) 0f else TransitionGeometry.window(f, 0.6f, 1f) * (1f - v)
    )
}

/**
 * The topmost root layer (spec §3.1, §6.4–§6.5). Draws nothing while idle.
 * Opening: the library dims behind the cover, which grows straight to half the
 * screen with «Приятного чтения» under it, then everything fades onto the
 * reader. Closing: the reader snapshot cross-fades into the cover, which flies
 * back to its shelf slot. Touches and back presses are swallowed meanwhile.
 */
@Composable
fun BookTransitionOverlay(state: BookTransitionState, modifier: Modifier = Modifier) {
    if (state.phase == BookTransitionState.Phase.Idle) return
    val cover = state.cover ?: return

    // The animation is short; back is ignored until it ends. While only waiting for a
    // slow reader, back stops waiting: the overlay fades and the next back closes the reader.
    BackHandler(enabled = true) {
        if (state.phase == BookTransitionState.Phase.WaitingReader) state.skipWaiting()
    }

    val colors = Lumina.colors
    val localDensity = LocalDensity.current
    val arcPx = with(localDensity) { 32.dp.toPx() }
    val closedCornerPx = with(localDensity) { 4.dp.toPx() }
    val greetingGapPx = with(localDensity) { 28.dp.toPx() }
    val stage = state.stageRect
    val stageWidth = with(localDensity) { stage.width.toDp() }
    val stageHeight = with(localDensity) { stage.height.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = state.overlayAlpha.value }
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        // Backdrop, scrim and glow; while closing also the reader snapshot.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val glow = Brush.radialGradient(
                        colors = listOf(colors.lampGlow, Color.Transparent),
                        center = stage.center,
                        radius = size.maxDimension * 0.6f
                    )
                    val wallGlow = Brush.radialGradient(
                        colors = listOf(colors.lampGlow, Color.Transparent),
                        center = Offset(size.width * 0.72f, -40.dp.toPx()),
                        radius = 420.dp.toPx()
                    )
                    val clip = Path()
                    onDrawBehind {
                        val b = state.libraryBackdrop.value
                        if (!state.closing && state.backdropReady) {
                            val shot = state.libraryShot
                            scale(1f - BookTransitionState.LIBRARY_SCALE_DEPTH * b) {
                                if (shot != null) {
                                    drawImage(
                                        image = shot,
                                        srcOffset = IntOffset.Zero,
                                        srcSize = IntSize(shot.width, shot.height),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                    )
                                } else {
                                    drawRect(colors.wall)
                                    drawRect(wallGlow)
                                }
                            }
                        }
                        drawRect(colors.scrim, alpha = 0.55f * b)
                        if (!state.closing) drawRect(glow, alpha = b)

                        val fr = state.frame(arcPx, closedCornerPx)
                        drawReaderSnapshot(state, fr, clip)
                    }
                }
        )

        // «Приятного чтения» under the cover.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val fr = state.frame(arcPx, closedCornerPx)
                    translationX = fr.page.center.x - size.width / 2f
                    translationY = fr.page.bottom + greetingGapPx
                    alpha = fr.greetingAlpha
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Greeting(ink = GreetingInk, waiting = state.waitingVisible, reducedMotion = state.reducedMotion)
        }

        // The cover, scaled uniformly onto its current bounds.
        Box(
            modifier = Modifier
                .size(stageWidth, stageHeight)
                .graphicsLayer {
                    val fr = state.frame(arcPx, closedCornerPx)
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = fr.page.left
                    translationY = fr.page.top
                    scaleX = if (stage.width > 0f) fr.page.width / stage.width else 1f
                    scaleY = if (stage.height > 0f) fr.page.height / stage.height else 1f
                    alpha = fr.coverAlpha
                    shadowElevation = fr.shadowDp.dp.toPx()
                    shape = LuminaShape.Book
                    clip = false
                }
        ) {
            BookCover(model = cover, width = stageWidth, modifier = Modifier.fillMaxSize(), large = true)
        }
    }
}

/** The reader as it was when closing began, cross-fading into the cover. */
private fun DrawScope.drawReaderSnapshot(state: BookTransitionState, fr: TransitionFrame, clip: Path) {
    val shot = state.readerShot
    if (!state.closing || fr.pageAlpha <= 0f) return
    val page = fr.readerPage
    clip.rewind()
    clip.addRoundRect(RoundRect(page, CornerRadius(fr.cornerPx)))
    clipPath(clip) {
        drawRect(state.paper, topLeft = page.topLeft, size = page.size, alpha = fr.pageAlpha)
        if (shot != null) {
            val dst = TransitionGeometry.fitHeightCentered(Size(shot.width.toFloat(), shot.height.toFloat()), page)
            drawImage(
                image = shot,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(shot.width, shot.height),
                dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
                dstSize = IntSize(dst.width.toInt().coerceAtLeast(1), dst.height.toInt().coerceAtLeast(1)),
                alpha = fr.pageAlpha
            )
        }
    }
}

/** Warm white: the line sits on the dimmed library in both app themes. */
private val GreetingInk = Color(0xFFFFF8EE)

/**
 * «Приятного чтения» under the cover. If the reader is still loading when the
 * cover has grown, three soft dots breathe under it instead of a progress bar.
 */
@Composable
private fun Greeting(ink: Color, waiting: Boolean, reducedMotion: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Приятного чтения",
            color = ink.copy(alpha = 0.82f),
            fontFamily = LoraFamily,
            fontWeight = FontWeight.SemiBold,
            fontStyle = FontStyle.Italic,
            fontSize = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        val dotsAlpha = if (waiting && !reducedMotion) {
            rememberInfiniteTransition(label = "reader-wait").animateFloat(
                initialValue = 0.2f,
                targetValue = 0.6f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                label = "dots"
            ).value
        } else if (waiting) {
            0.4f
        } else {
            0f
        }
        Text(
            text = "• • •",
            color = ink.copy(alpha = dotsAlpha),
            fontSize = 12.sp
        )
    }
}
