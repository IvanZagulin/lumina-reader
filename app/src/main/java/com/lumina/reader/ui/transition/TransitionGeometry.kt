package com.lumina.reader.ui.transition

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** A registered book slot as the close animation sees it (root coordinates, px). */
internal data class SlotCandidate(val key: String, val bookId: Long, val rect: Rect)

/**
 * Pure geometry of the book-open transition (spec §6.4–§6.5). All values are
 * in root pixels; the overlay converts dp before calling.
 */
internal object TransitionGeometry {

    const val HERO_SLOT_KEY = "hero"

    /**
     * The closed book on the stage: width `min(0.44·W, maxWidth)`, height
     * `1.5·width`, horizontally centred, vertically centred at `0.46·H`.
     */
    fun stageRect(rootWidth: Float, rootHeight: Float, maxWidthPx: Float): Rect {
        val w = min(0.44f * rootWidth, maxWidthPx).coerceAtLeast(1f)
        val h = 1.5f * w
        val left = rootWidth / 2f - w / 2f
        val top = 0.46f * rootHeight - h / 2f
        return Rect(left, top, left + w, top + h)
    }

    /** Where a book without a visible slot starts: a smaller copy of the stage. */
    fun centeredSource(stage: Rect, scale: Float = 0.6f): Rect = scaleAbout(stage, stage.center, scale)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
        lerp(a.left, b.left, t),
        lerp(a.top, b.top, t),
        lerp(a.right, b.right, t),
        lerp(a.bottom, b.bottom, t)
    )

    /** Vertical arc of the flight: `−sin(π·p)·arc` (negative = up). */
    fun arcOffset(progress: Float, arcPx: Float): Float = -sin(PI * progress).toFloat() * arcPx

    /** Flight wobble around the Y axis, `sign·10°·sin(π·p)`. */
    fun flightRotation(progress: Float, hingeSign: Float): Float =
        hingeSign * 10f * sin(PI * progress).toFloat()

    /**
     * The right-hand page of a book whose closed bounds are [book] while the
     * cover is open by [hinge] (0..1). The whole book shifts right by
     * `width/2·hinge`, so the open spread ends up centred where the closed
     * book was.
     */
    fun pageRect(book: Rect, hinge: Float): Rect {
        val spine = book.left + book.width / 2f * hinge
        return Rect(spine, book.top, spine + book.width, book.bottom)
    }

    fun scaleAbout(rect: Rect, pivot: Offset, scale: Float): Rect = Rect(
        pivot.x + (rect.left - pivot.x) * scale,
        pivot.y + (rect.top - pivot.y) * scale,
        pivot.x + (rect.right - pivot.x) * scale,
        pivot.y + (rect.bottom - pivot.y) * scale
    )

    /**
     * Destination of an image of [image] size drawn into [rect] scaled
     * uniformly to the rect height and centred horizontally (no squash; the
     * caller clips to [rect]).
     */
    fun fitHeightCentered(image: Size, rect: Rect): Rect {
        if (image.height <= 0f || image.width <= 0f) return rect
        val scale = rect.height / image.height
        val w = image.width * scale
        val left = rect.center.x - w / 2f
        return Rect(left, rect.top, left + w, rect.bottom)
    }

    /** Linear progress of [t] inside the window [start, end], clamped to 0..1. */
    fun window(t: Float, start: Float, end: Float): Float =
        if (end <= start) (if (t >= end) 1f else 0f) else ((t - start) / (end - start)).coerceIn(0f, 1f)

    /** True when at least a sliver of [rect] is inside the root bounds. */
    fun isOnScreen(rect: Rect, rootWidth: Float, rootHeight: Float): Boolean =
        rect.width > 0f && rect.height > 0f &&
            rect.right > 0f && rect.bottom > 0f && rect.left < rootWidth && rect.top < rootHeight

    /**
     * The slot the closing book flies back to (spec §6.5): the slot it was
     * opened from, then the hero when it shows this book, then any slot with
     * this book. Offscreen slots are ignored; null when nothing fits.
     */
    fun pickCloseTarget(
        candidates: List<SlotCandidate>,
        originKey: String?,
        bookId: Long,
        rootWidth: Float,
        rootHeight: Float
    ): SlotCandidate? {
        val visible = candidates.filter { it.bookId == bookId && isOnScreen(it.rect, rootWidth, rootHeight) }
        return visible.firstOrNull { originKey != null && it.key == originKey }
            ?: visible.firstOrNull { it.key == HERO_SLOT_KEY }
            ?: visible.firstOrNull()
    }
}
