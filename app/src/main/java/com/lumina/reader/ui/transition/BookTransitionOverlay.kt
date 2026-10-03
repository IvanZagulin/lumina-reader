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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
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
import com.lumina.reader.ui.components.Endpaper
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.LuminaShape
import kotlin.math.PI
import kotlin.math.sin

/** One frame of the transition geometry, derived from the animatables (draw phase only). */
internal class TransitionFrame(
    /** The right-hand page; the cover is pinned to its left edge. */
    val page: Rect,
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
    val h = hinge.value
    val e = expand.value
    val v = vanish.value
    val source = if (sourceFollowsLibraryScale) {
        TransitionGeometry.scaleAbout(sourceRect, fullRect.center, librarySourceScale())
    } else {
        sourceRect
    }
    val book = TransitionGeometry.lerpRect(source, stageRect, f)
        .translate(Offset(0f, TransitionGeometry.arcOffset(f, arcPx)))
    // As the cover swings open the camera moves onto the right-hand page: it
    // grows and slides to the screen centre while the open cover slips off
    // the left edge, so the book visibly opens wide before the page fills the screen.
    val focus = TransitionGeometry.smoothstep(TransitionGeometry.window(h, BookTransitionState.FOCUS_START, 1f))
    val openPage = TransitionGeometry.lerpRect(
        TransitionGeometry.pageRect(book, h),
        TransitionGeometry.focusedPageRect(book, fullRect.center.x, BookTransitionState.OPEN_ZOOM),
        focus
    )
    var page = TransitionGeometry.lerpRect(openPage, endRect, e)
    if (v > 0f) page = TransitionGeometry.scaleAbout(page, page.center, 1f - 0.4f * v)
    val sinH = sin(PI * h).toFloat()
    return TransitionFrame(
        page = page,
        cornerPx = TransitionGeometry.lerp(closedCornerPx, endCornerPx, e),
        coverRotationY = LuminaMotion.HingeSign * 180f * h + TransitionGeometry.flightRotation(f, LuminaMotion.HingeSign),
        coverShading = 0.35f * sinH,
        coverAlpha = (1f - TransitionGeometry.window(e, 0.5f, 1f)) * (1f - v),
        pageAlpha = 1f - v,
        shadowDp = TransitionGeometry.lerp(4f, 24f, f) * (1f - e) * (1f - v),
        hinge = h,
        expand = e,
        greetingAlpha = if (closing) 0f else TransitionGeometry.window(h, 0.55f, 0.9f) * (1f - v)
    )
}

/**
 * The topmost root layer (spec §3.1, §6.4–§6.5). Draws nothing while idle; while
 * a book opens or closes it draws the library backdrop (or only the scrim when
 * the live library is underneath), the page, the hinged cover and the
 * «Открываю книгу…» line, and swallows touches and back presses.
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
    val stage = state.stageRect
    val stageWidth = with(localDensity) { stage.width.toDp() }
    val stageHeight = with(localDensity) { stage.height.toDp() }
    val showEndpaper by remember(state) { derivedStateOf { state.hinge.value >= 0.5f } }
    val breath: State<Float>? = if (state.waitingVisible && !state.reducedMotion) {
        rememberInfiniteTransition(label = "reader-wait").animateFloat(
            initialValue = 1f,
            targetValue = 1.008f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
            label = "breath"
        )
    } else {
        null
    }

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
        // Backdrop, scrim, glow and the page.
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
                    val hingeShadowMax = 40.dp.toPx()
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
                        drawPageShadow(fr)
                        val s = breath?.value ?: 1f
                        scale(s, pivot = fr.page.center) {
                            drawPage(state, fr, clip, hingeShadowMax)
                        }
                    }
                }
        )

        // «Приятного чтения» centred on the page, under the swinging cover.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val fr = state.frame(arcPx, closedCornerPx)
                    translationX = fr.page.center.x - size.width / 2f
                    translationY = fr.page.center.y - size.height / 2f
                    alpha = fr.greetingAlpha * fr.pageAlpha
                },
            contentAlignment = Alignment.Center
        ) {
            Greeting(ink = state.paperInk, waiting = state.waitingVisible, reducedMotion = state.reducedMotion)
        }

        // The cover: placed and scaled onto the page rect, hinged at its left edge.
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
                    // The closed book casts a real shadow; once it opens the page draws its own.
                    shadowElevation = fr.shadowDp.dp.toPx() * (1f - fr.hinge)
                    shape = LuminaShape.Book
                    clip = false
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val fr = state.frame(arcPx, closedCornerPx)
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        cameraDistance = 16f * density
                        rotationY = fr.coverRotationY
                    }
                    .drawWithContent {
                        drawContent()
                        val shading = 0.35f * sin(PI * state.hinge.value).toFloat()
                        if (shading > 0f) drawRect(Color.Black, alpha = shading)
                    }
            ) {
                if (showEndpaper) {
                    Endpaper(cloth = state.cloth, modifier = Modifier.fillMaxSize())
                } else {
                    BookCover(model = cover, width = stageWidth, modifier = Modifier.fillMaxSize(), large = true)
                }
            }
        }

    }
}

private fun DrawScope.drawPageShadow(fr: TransitionFrame) {
    if (fr.shadowDp <= 0f || fr.hinge <= 0f || fr.expand >= 1f) return
    val radius = fr.shadowDp.dp.toPx()
    val dy = radius * 0.35f
    val steps = 4
    for (i in steps downTo 1) {
        val grow = radius * i / steps
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(fr.page.left - grow * 0.5f, fr.page.top + dy - grow * 0.3f),
            size = Size(fr.page.width + grow, fr.page.height + grow * 0.8f),
            cornerRadius = CornerRadius(fr.cornerPx + grow),
            alpha = 0.05f * fr.pageAlpha * fr.hinge
        )
    }
}

private fun DrawScope.drawPage(state: BookTransitionState, fr: TransitionFrame, clip: Path, hingeShadowMax: Float) {
    // A closed book is all cover: the page only shows once the hinge or the expand starts.
    if (fr.hinge <= 0f && fr.expand <= 0f) return
    val page = fr.page
    val shot = state.readerShot
    if (state.closing && shot != null) {
        clip.rewind()
        clip.addRoundRect(RoundRect(page, CornerRadius(fr.cornerPx)))
        clipPath(clip) {
            drawRect(state.paper, topLeft = page.topLeft, size = page.size, alpha = fr.pageAlpha)
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
    } else {
        drawRoundRect(
            color = state.paper,
            topLeft = page.topLeft,
            size = page.size,
            cornerRadius = CornerRadius(fr.cornerPx),
            alpha = fr.pageAlpha
        )
    }
    // Shadow of the lifting cover on the page, from the spine.
    val h = fr.hinge
    if (h > 0f && h < 1f) {
        val width = (1f - h) * hingeShadowMax
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.3f),
                1f to Color.Transparent,
                startX = page.left,
                endX = page.left + width
            ),
            topLeft = page.topLeft,
            size = Size(width, page.height),
            alpha = fr.pageAlpha
        )
    }
}

/**
 * «Приятного чтения» with a small ornament. If the reader is still loading
 * when the page has opened, three soft dots breathe under it instead of a
 * progress bar.
 */
@Composable
private fun Greeting(ink: Color, waiting: Boolean, reducedMotion: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "❦",
            color = ink.copy(alpha = 0.45f),
            fontSize = 20.sp
        )
        Spacer(Modifier.height(10.dp))
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
