package com.lumina.reader.ui.reader.pageturn

import kotlin.math.sqrt

/**
 * A small convex polygon in preallocated arrays, so clipping during a curl
 * allocates nothing per frame.
 */
class CurlPolygon(capacity: Int = 8) {
    val xs = FloatArray(capacity)
    val ys = FloatArray(capacity)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(x: Float, y: Float) {
        if (size < xs.size) {
            xs[size] = x
            ys[size] = y
            size++
        }
    }

    /** Area of the polygon (shoelace formula). */
    fun area(): Float {
        var sum = 0f
        for (i in 0 until size) {
            val j = (i + 1) % size
            sum += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return kotlin.math.abs(sum) / 2f
    }
}

/**
 * Geometry of a page curl (design spec §9.3), pure arithmetic and free of
 * allocations after construction.
 *
 * The page is R = (0, 0, W, H). The reader drags corner C to touch point P.
 * The fold is the perpendicular bisector of C and P: it passes through
 * M = (C + P) / 2 with unit normal n = (C − P) / |C − P|. The flap is the
 * part of R on the corner's side, `(X − M)·n > 0`; the front is the rest.
 * The flap's back side is the flap reflected across the fold:
 * `X' = X − 2((X − M)·n)n`.
 */
class CurlGeometry {
    var width = 0f
        private set
    var height = 0f
        private set

    /** Corner being dragged. */
    var cornerX = 0f
        private set
    var cornerY = 0f
        private set

    /** Touch point after clamping. */
    var touchX = 0f
        private set
    var touchY = 0f
        private set

    /** Fold midpoint M and unit normal n (pointing into the flap). */
    var midX = 0f
        private set
    var midY = 0f
        private set
    var normalX = 1f
        private set
    var normalY = 0f
        private set

    /** |C − P| after clamping. */
    var distance = 0f
        private set

    /** Page part still lying flat. */
    val front = CurlPolygon()

    /** Page part lifted off (still in page coordinates). */
    val flap = CurlPolygon()

    /** The flap reflected across the fold: where its back side is drawn. */
    val reflectedFlap = CurlPolygon()

    /** The fold line inside the page: from (foldX0, foldY0) to (foldX1, foldY1); [hasFold] tells whether it exists. */
    var foldX0 = 0f
        private set
    var foldY0 = 0f
        private set
    var foldX1 = 0f
        private set
    var foldY1 = 0f
        private set
    var hasFold = false
        private set

    private val rect = CurlPolygon(4)
    private var foldPoints = 0

    /** True when nothing is curled: P coincides with C. */
    val isFlat: Boolean get() = distance < 0.5f

    /**
     * Recomputes everything for a [w]×[h] page, corner ([cx], [cy]) and touch
     * ([px], [py]). The touch is clamped so the page never tears off the spine.
     */
    fun update(w: Float, h: Float, cx: Float, cy: Float, px: Float, py: Float) {
        width = w
        height = h
        cornerX = cx
        cornerY = cy
        clampTouch(w, h, cy, px, py)
        val dx = cornerX - touchX
        val dy = cornerY - touchY
        distance = sqrt(dx * dx + dy * dy)
        midX = (cornerX + touchX) / 2f
        midY = (cornerY + touchY) / 2f
        if (distance < 1e-3f) {
            normalX = 1f
            normalY = 0f
        } else {
            normalX = dx / distance
            normalY = dy / distance
        }
        rect.clear()
        rect.add(0f, 0f)
        rect.add(w, 0f)
        rect.add(w, h)
        rect.add(0f, h)
        hasFold = false
        foldPoints = 0
        clip(rect, keepFlap = false, out = front, recordFold = true)
        clip(rect, keepFlap = true, out = flap, recordFold = false)
        reflectedFlap.clear()
        for (i in 0 until flap.size) {
            val x = flap.xs[i]
            val y = flap.ys[i]
            val d = 2f * ((x - midX) * normalX + (y - midY) * normalY)
            reflectedFlap.add(x - d * normalX, y - d * normalY)
        }
        hasFold = foldPoints >= 2 && !isFlat
    }

    /**
     * Keeps P within W of the spine point (0, C.y) and within the page
     * diagonal of the opposite spine corner (0, H − C.y).
     */
    private fun clampTouch(w: Float, h: Float, cy: Float, px: Float, py: Float) {
        var x = px
        var y = py
        val sx = 0f
        val sy = cy
        var dx = x - sx
        var dy = y - sy
        var d = sqrt(dx * dx + dy * dy)
        if (d > w && d > 0f) {
            x = sx + dx * w / d
            y = sy + dy * w / d
        }
        val oy = h - cy
        val diagonal = sqrt(w * w + h * h)
        dx = x - sx
        dy = y - oy
        d = sqrt(dx * dx + dy * dy)
        if (d > diagonal && d > 0f) {
            x = sx + dx * diagonal / d
            y = oy + dy * diagonal / d
        }
        touchX = x
        touchY = y
    }

    /** Signed distance of (x, y) from the fold along n: positive on the flap side. */
    fun side(x: Float, y: Float): Float = (x - midX) * normalX + (y - midY) * normalY

    /** One Sutherland–Hodgman pass of [src] against the fold half-plane. */
    private fun clip(src: CurlPolygon, keepFlap: Boolean, out: CurlPolygon, recordFold: Boolean) {
        out.clear()
        val count = src.size
        if (count == 0) return
        for (i in 0 until count) {
            val ax = src.xs[i]
            val ay = src.ys[i]
            val bx = src.xs[(i + 1) % count]
            val by = src.ys[(i + 1) % count]
            val sa = side(ax, ay)
            val sb = side(bx, by)
            val aIn = if (keepFlap) sa > 0f else sa <= 0f
            val bIn = if (keepFlap) sb > 0f else sb <= 0f
            if (aIn) out.add(ax, ay)
            if (aIn != bIn) {
                val t = sa / (sa - sb)
                val ix = ax + (bx - ax) * t
                val iy = ay + (by - ay) * t
                out.add(ix, iy)
                if (recordFold) recordFoldPoint(ix, iy)
            }
        }
    }

    private fun recordFoldPoint(x: Float, y: Float) {
        if (foldPoints == 0) {
            foldX0 = x
            foldY0 = y
        } else if (foldPoints == 1) {
            foldX1 = x
            foldY1 = y
        }
        foldPoints++
    }

    /** Reflects a point across the fold into [out] (x, y). */
    fun reflect(x: Float, y: Float, out: FloatArray) {
        val d = 2f * side(x, y)
        out[0] = x - d * normalX
        out[1] = y - d * normalY
    }

    /**
     * Writes the reflection across the fold into a 4×4 column-major matrix
     * as used by Compose's Matrix (`x' = m[0,0]x + m[1,0]y + m[3,0]`).
     */
    fun writeReflectionMatrix(values: FloatArray) {
        for (i in 0 until 16) values[i] = 0f
        values[10] = 1f // [2,2]
        values[15] = 1f // [3,3]
        val nx = normalX
        val ny = normalY
        val d = 2f * (midX * nx + midY * ny)
        values[0] = 1f - 2f * nx * nx // [0,0]
        values[4] = -2f * nx * ny // [1,0]
        values[12] = d * nx // [3,0]
        values[1] = -2f * nx * ny // [0,1]
        values[5] = 1f - 2f * ny * ny // [1,1]
        values[13] = d * ny // [3,1]
    }

    /**
     * How strong the shadows are: they grow over the first quarter of the
     * page width of the curl and fade again over the last quarter before the
     * page lies turned over at x = -W, so no shadow lingers at the spine.
     */
    fun shadowStrength(): Float {
        if (width <= 0f) return 0f
        val opening = (distance / (0.25f * width)).coerceIn(0f, 1f)
        val closing = ((touchX + width) / (0.25f * width)).coerceIn(0f, 1f)
        return opening * closing
    }

    /** Fraction of the turn done, 0 (flat) to 1 (the page is turned over): |C−P| / 2W. */
    fun progress(): Float =
        if (width <= 0f) 0f else (distance / (2f * width)).coerceIn(0f, 1f)
}

/** Which corner a curl drags, chosen by where the drag starts (§9.3). */
enum class CurlCorner {
    TOP,
    BOTTOM,

    /** A vertical fold: the corner follows the finger's height. */
    MIDDLE;

    companion object {
        fun forStart(y: Float, height: Float): CurlCorner = when {
            y > height * 2f / 3f -> BOTTOM
            y < height / 3f -> TOP
            else -> MIDDLE
        }
    }
}

/** y of the dragged corner for a touch at [touchY]. */
fun curlCornerY(corner: CurlCorner, height: Float, touchY: Float): Float = when (corner) {
    CurlCorner.TOP -> 0f
    CurlCorner.BOTTOM -> height
    CurlCorner.MIDDLE -> touchY
}

/**
 * Point of a scripted (tap or volume key) turn at time fraction [t]: a
 * quadratic Bézier from the corner (W, cornerY) through (0.3W, 0.85H) — mirrored
 * for the top corner — to (−W, cornerY). Writes x and y into [out].
 */
fun curlTapPath(t: Float, width: Float, height: Float, cornerY: Float, out: FloatArray) {
    val u = t.coerceIn(0f, 1f)
    val startX = width
    val controlX = 0.3f * width
    val endX = -width
    val controlY = if (cornerY < height / 2f) 0.15f * height else 0.85f * height
    val a = (1 - u) * (1 - u)
    val b = 2 * (1 - u) * u
    val c = u * u
    out[0] = a * startX + b * controlX + c * endX
    out[1] = a * cornerY + b * controlY + c * cornerY
}

/**
 * Whether a released curl turns the page. Forward: the corner was pulled
 * past the middle of the page or flung left. Backward (the previous page
 * uncurling from x = -W): it is more than half way back or flung right.
 */
fun shouldCommitCurl(forward: Boolean, touchX: Float, width: Float, velocityX: Float): Boolean =
    if (forward) touchX < width / 2f || velocityX < -1000f
    else touchX > 0f || velocityX > 1000f
