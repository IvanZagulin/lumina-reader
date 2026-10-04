package com.lumina.reader.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaExtendedColors
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberLuminaHaptics
import com.lumina.reader.ui.theme.rememberReducedMotion
import com.lumina.reader.ui.transition.LocalBookSlotHost
import com.lumina.reader.ui.transition.bookSlot
import kotlin.math.max
import kotlinx.coroutines.launch

/** What a shelf book shows; built once per book (`Book.toShelfBookUi()` in :app). */
@Immutable
data class ShelfBookUi(
    val id: Long,
    val cover: BookCoverModel,
    val title: String,
    val author: String,
    /** 0..1 */
    val progress: Float,
    val isFavorite: Boolean,
    val isFinished: Boolean,
    /** Never opened. */
    val isNew: Boolean,
    /** Position in its series, or null. */
    val seriesNumber: Int?
)

/** «"Title", author, прочитано N%» plus the status markers (spec §11). */
fun shelfBookDescription(book: ShelfBookUi): String = buildString {
    append("«").append(book.title).append("», ").append(book.author)
    if (book.isFinished) {
        append(", прочитана")
    } else {
        append(", прочитано ").append((book.progress * 100).toInt()).append("%")
    }
    if (book.isFavorite) append(", в избранном")
    book.seriesNumber?.let { append(", книга ").append(it).append(" в серии") }
}

/**
 * Coverflow position of a LazyRow item: −1 at the viewport's start edge, 0 in
 * the centre, +1 at the end edge (spec §4.1). Offsets are LazyList item
 * offsets; the viewport may start below 0 because of content padding.
 */
fun coverflowFraction(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Float {
    val half = (viewportEnd - viewportStart) / 2f
    if (half <= 0f) return 0f
    val center = (viewportStart + viewportEnd) / 2f
    return (((itemOffset + itemSize / 2f) - center) / half).coerceIn(-1f, 1f)
}

/** Where a shelf book gets its coverflow tilt from. */
@Immutable
class ShelfTilt(val rowState: LazyListState, val itemKey: Any, val sway: () -> Float = { 0f })

/**
 * A 3D shelf book (spec §4.1): contact shadow and page block drawn outside the
 * bounds, the [BookCover], and the status markers (reading ribbon and progress
 * line, finished seal, favourite heart, «new» dot, series number). Tilt,
 * press lift and the hidden-during-transition alpha are read only inside
 * `graphicsLayer`, so scrolling and animations never recompose it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookOnShelf(
    book: ShelfBookUi,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    slotKey: String? = null,
    tilt: ShelfTilt? = null,
    baseRotationY: Float = 0f,
    showNewDot: Boolean = false,
    dimmed: Boolean = false,
    castShadow: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val transition = LocalBookSlotHost.current
    val reducedMotion = rememberReducedMotion()
    val haptics = rememberLuminaHaptics()
    val colors = Lumina.colors
    val primary = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(0f) }
    val longLift = remember { Animatable(0f) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed, reducedMotion) {
        val target = if (pressed) 1f else 0f
        if (reducedMotion) {
            press.snapTo(target)
        } else {
            press.animateTo(target, if (pressed) LuminaMotion.lift() else LuminaMotion.settle())
        }
    }
    val description = remember(book) { shelfBookDescription(book) }
    val thickness = book.cover.thicknessDp

    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onLongClickLabel = if (onLongClick != null) "Действия с книгой" else null,
            onLongClick = onLongClick?.let { action ->
                {
                    haptics.longPress()
                    scope.launch {
                        if (reducedMotion) {
                            longLift.snapTo(1f)
                        } else {
                            longLift.animateTo(1f, tween(150, easing = LuminaMotion.EmphasizedDecelerate))
                        }
                        action()
                        longLift.animateTo(0f, LuminaMotion.settle())
                    }
                    Unit
                }
            },
            onClick = {
                haptics.tick()
                onClick?.invoke()
            }
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(width, height)
            .then(if (slotKey != null) Modifier.bookSlot(transition, slotKey, book.id) else Modifier)
            // Contact shadow stays on the shelf while the book lifts.
            .drawWithCache {
                val w = size.width
                val h = size.height
                val cast = Path().apply {
                    moveTo(0f, h)
                    lineTo(w, h)
                    lineTo(w + 12.dp.toPx(), 8.dp.toPx())
                    lineTo(12.dp.toPx(), 8.dp.toPx())
                    close()
                }
                val ovalHeight = 10.dp.toPx()
                val ovalWidth = 1.1f * w
                val ovalCenter = Offset(w / 2f, h)
                val contact = Brush.radialGradient(
                    listOf(colors.bookShadow, Color.Transparent),
                    center = ovalCenter,
                    radius = ovalWidth / 2f
                )
                val dim = if (dimmed) 0.7f else 1f
                onDrawBehind {
                    if (!castShadow || transition?.hiddenBookId == book.id) return@onDrawBehind
                    val lifted = max(press.value, longLift.value)
                    drawPath(cast, Color.Black, alpha = 0.15f * (1f - 0.5f * lifted) * dim)
                    scale(scaleX = 1f, scaleY = ovalHeight / ovalWidth, pivot = ovalCenter) {
                        drawCircle(contact, radius = ovalWidth / 2f, center = ovalCenter, alpha = (1f - 0.6f * lifted) * dim)
                    }
                }
            }
            .graphicsLayer {
                cameraDistance = 16f * density
                var rotation = baseRotationY
                // Books stand straight on the shelf. A position-based coverflow
                // tilt made the books at the row edges look crooked at rest; only
                // the short sway after a fling remains.
                if (tilt != null && !reducedMotion) rotation += tilt.sway()
                rotationY = rotation
                val p = press.value
                val l = longLift.value
                translationY = -6.dp.toPx() * max(p, l)
                val s = 1f + 0.03f * p + 0.05f * l
                scaleX = s
                scaleY = s
                shadowElevation = 14.dp.toPx() * max(p, l)
                spotShadowColor = colors.bookShadow
                shape = LuminaShape.Book
                clip = false
                val hidden = transition?.hiddenBookId == book.id
                alpha = if (hidden) 0f else if (dimmed) 0.7f else 1f
            }
            // Page block to the right of the cover.
            .drawBehind {
                val t = thickness.dp.toPx()
                val left = size.width - 2.dp.toPx()
                val top = 3.dp.toPx()
                val bottom = size.height - 2.dp.toPx()
                drawRect(colors.pageEdge, topLeft = Offset(left, top), size = Size(t + 2.dp.toPx(), bottom - top))
                for (i in 1..3) {
                    val x = left + 2.dp.toPx() + t * i / 4f
                    drawLine(colors.pageEdgeLine, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                }
            }
            .then(clickModifier)
            .semantics { contentDescription = description }
    ) {
        BookCover(model = book.cover, width = width, modifier = Modifier.size(width, height))
        Spacer(
            modifier = Modifier
                .size(width, height)
                .shelfMarkers(book, showNewDot, primary, colors)
        )
        val number = book.seriesNumber
        if (number != null) {
            Text(
                text = "№$number",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 5.dp, bottom = 7.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .clearAndSetSemantics { }
            )
        }
    }
}

/** Ribbon + progress line, finished seal, favourite heart and the «new» dot. */
private fun Modifier.shelfMarkers(
    book: ShelfBookUi,
    showNewDot: Boolean,
    newDotColor: Color,
    colors: LuminaExtendedColors
): Modifier =
    drawWithCache {
        val w = size.width
        val h = size.height
        val reading = !book.isFinished && book.progress > 0f
        val ribbonWidth = 6.dp.toPx()
        val ribbonLength = 20.dp.toPx()
        val ribbonRight = w - 14.dp.toPx()
        val ribbon = Path().apply {
            moveTo(ribbonRight - ribbonWidth, 0f)
            lineTo(ribbonRight, 0f)
            lineTo(ribbonRight, ribbonLength)
            lineTo(ribbonRight - ribbonWidth / 2f, ribbonLength - 3.dp.toPx())
            lineTo(ribbonRight - ribbonWidth, ribbonLength)
            close()
        }
        val sealSize = 20.dp.toPx()
        val inset = 6.dp.toPx()
        val sealCenter = Offset(w - inset - sealSize / 2f, h - inset - sealSize / 2f)
        val sealBrush = Brush.radialGradient(
            listOf(colors.foilLight, colors.foilDark),
            center = Offset(sealCenter.x - sealSize * 0.15f, sealCenter.y - sealSize * 0.15f),
            radius = sealSize * 0.65f
        )
        val check = Path().apply {
            val left = sealCenter.x - sealSize / 2f
            val top = sealCenter.y - sealSize / 2f
            moveTo(left + 0.28f * sealSize, top + 0.52f * sealSize)
            lineTo(left + 0.44f * sealSize, top + 0.68f * sealSize)
            lineTo(left + 0.74f * sealSize, top + 0.36f * sealSize)
        }
        val checkStroke = Stroke(width = 0.09f * sealSize, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val heartSize = 14.dp.toPx()
        val heart = heartPath(Offset(inset, inset), heartSize)
        val heartStroke = Stroke(width = 1.dp.toPx())
        val progressHeight = 3.dp.toPx()
        onDrawBehind {
            if (reading) {
                drawPath(ribbon, colors.ribbon)
                drawRect(Color.White.copy(alpha = 0.3f), topLeft = Offset(0f, h - progressHeight), size = Size(w, progressHeight))
                drawRect(colors.ribbon, topLeft = Offset(0f, h - progressHeight), size = Size(w * book.progress, progressHeight))
            }
            if (book.isFinished) {
                drawCircle(sealBrush, radius = sealSize / 2f, center = sealCenter)
                drawPath(check, SEAL_INK, style = checkStroke)
            }
            if (book.isFavorite) {
                drawPath(heart, colors.favorite)
                drawPath(heart, Color.White, style = heartStroke)
            }
            if (showNewDot && book.isNew) {
                drawCircle(newDotColor, radius = 3.dp.toPx(), center = Offset(w - inset - 3.dp.toPx(), inset + 3.dp.toPx()))
            }
        }
    }

private val SEAL_INK = Color(0xFF3A2A06)

/** A heart in a [size]×[size] box at [origin]. */
internal fun heartPath(origin: Offset, size: Float): Path = Path().apply {
    fun x(v: Float) = origin.x + v * size
    fun y(v: Float) = origin.y + v * size
    moveTo(x(0.5f), y(0.9f))
    cubicTo(x(0.1f), y(0.65f), x(0f), y(0.4f), x(0.12f), y(0.22f))
    cubicTo(x(0.25f), y(0.04f), x(0.45f), y(0.08f), x(0.5f), y(0.25f))
    cubicTo(x(0.55f), y(0.08f), x(0.75f), y(0.04f), x(0.88f), y(0.22f))
    cubicTo(x(1f), y(0.4f), x(0.9f), y(0.65f), x(0.5f), y(0.9f))
    close()
}
