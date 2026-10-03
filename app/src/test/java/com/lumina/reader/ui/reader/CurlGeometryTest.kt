package com.lumina.reader.ui.reader

import com.lumina.reader.ui.reader.pageturn.CurlCorner
import com.lumina.reader.ui.reader.pageturn.CurlGeometry
import com.lumina.reader.ui.reader.pageturn.curlCornerY
import com.lumina.reader.ui.reader.pageturn.curlTapPath
import com.lumina.reader.ui.reader.pageturn.shouldCommitCurl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CurlGeometryTest {

    private val w = 400f
    private val h = 600f
    private val eps = 0.01f

    private fun geometry(px: Float, py: Float, cx: Float = w, cy: Float = h) =
        CurlGeometry().apply { update(w, h, cx, cy, px, py) }

    @Test
    fun reflectingTheCornerGivesTheTouchPoint() {
        val g = geometry(250f, 450f)
        val out = FloatArray(2)
        g.reflect(g.cornerX, g.cornerY, out)
        assertEquals(g.touchX, out[0], eps)
        assertEquals(g.touchY, out[1], eps)
        // And back: the reflection is its own inverse.
        g.reflect(out[0], out[1], out)
        assertEquals(w, out[0], eps)
        assertEquals(h, out[1], eps)
    }

    @Test
    fun pointsOnTheFoldStayPut() {
        val g = geometry(120f, 380f)
        val out = FloatArray(2)
        g.reflect(g.midX, g.midY, out)
        assertEquals(g.midX, out[0], eps)
        assertEquals(g.midY, out[1], eps)
        assertTrue(g.hasFold)
        g.reflect(g.foldX0, g.foldY0, out)
        assertEquals(g.foldX0, out[0], eps)
        assertEquals(g.foldY0, out[1], eps)
        g.reflect(g.foldX1, g.foldY1, out)
        assertEquals(g.foldX1, out[0], eps)
        assertEquals(g.foldY1, out[1], eps)
        assertEquals(0f, g.side(g.foldX0, g.foldY0), eps)
        assertEquals(0f, g.side(g.foldX1, g.foldY1), eps)
    }

    @Test
    fun reflectionMatrixMatchesTheReflection() {
        val g = geometry(180f, 520f)
        val m = FloatArray(16)
        g.writeReflectionMatrix(m)
        val out = FloatArray(2)
        listOf(0f to 0f, 400f to 600f, 123f to 45f, 300f to 333f).forEach { (x, y) ->
            g.reflect(x, y, out)
            // Compose Matrix.map: x' = m[0,0]x + m[1,0]y + m[3,0], y' = m[0,1]x + m[1,1]y + m[3,1].
            val mx = m[0] * x + m[4] * y + m[12]
            val my = m[1] * x + m[5] * y + m[13]
            assertEquals(out[0], mx, eps)
            assertEquals(out[1], my, eps)
        }
        assertEquals(1f, m[10], 0f)
        assertEquals(1f, m[15], 0f)
    }

    @Test
    fun frontAndFlapSplitThePage() {
        val g = geometry(220f, 470f)
        assertEquals(w * h, g.front.area() + g.flap.area(), 1f)
        assertTrue(g.flap.area() > 0f)
        assertTrue(g.front.size in 3..5)
        assertTrue(g.flap.size in 3..5)
        // Every flap vertex lies on the corner's side of the fold, every front vertex on the other.
        for (i in 0 until g.flap.size) assertTrue(g.side(g.flap.xs[i], g.flap.ys[i]) >= -eps)
        for (i in 0 until g.front.size) assertTrue(g.side(g.front.xs[i], g.front.ys[i]) <= eps)
        // The mirrored flap has the same area.
        assertEquals(g.flap.area(), g.reflectedFlap.area(), 1f)
    }

    @Test
    fun untouchedPageIsFlat() {
        val g = geometry(w, h)
        assertTrue(g.isFlat)
        assertFalse(g.hasFold)
        assertEquals(w * h, g.front.area(), 1f)
        assertEquals(0f, g.shadowStrength(), 0f)
    }

    @Test
    fun touchNeverTearsThePageOffTheSpine() {
        val g = geometry(-1500f, 900f)
        val fromSpine = hypot(g.touchX - 0f, g.touchY - h)
        assertTrue(fromSpine <= w + eps)
        val fromOppositeCorner = hypot(g.touchX - 0f, g.touchY - 0f)
        assertTrue(fromOppositeCorner <= hypot(w, h) + eps)
    }

    @Test
    fun verticalFoldFollowsTheFinger() {
        val y = 300f
        val g = geometry(px = 100f, py = y, cy = curlCornerY(CurlCorner.MIDDLE, h, y))
        assertEquals(1f, g.normalX, eps)
        assertEquals(0f, g.normalY, eps)
        assertEquals(250f, g.midX, eps)
        // Flap is the strip right of the fold: (400 - 250) × 600.
        assertEquals(150f * h, g.flap.area(), 1f)
    }

    @Test
    fun fullyTurnedPageHasNoFrontAndNoShadow() {
        val g = geometry(px = -w, py = h)
        assertEquals(0f, g.front.area(), 1f)
        assertEquals(w * h, g.flap.area(), 1f)
        assertEquals(0f, g.shadowStrength(), eps)
        assertEquals(1f, g.progress(), eps)
    }

    @Test
    fun cornerDependsOnWhereTheDragStarts() {
        assertEquals(CurlCorner.BOTTOM, CurlCorner.forStart(500f, h))
        assertEquals(CurlCorner.TOP, CurlCorner.forStart(100f, h))
        assertEquals(CurlCorner.MIDDLE, CurlCorner.forStart(300f, h))
        assertEquals(h, curlCornerY(CurlCorner.BOTTOM, h, 10f), 0f)
        assertEquals(0f, curlCornerY(CurlCorner.TOP, h, 10f), 0f)
        assertEquals(10f, curlCornerY(CurlCorner.MIDDLE, h, 10f), 0f)
    }

    @Test
    fun tapPathRunsFromTheCornerToTheSpine() {
        val out = FloatArray(2)
        curlTapPath(0f, w, h, h, out)
        assertEquals(w, out[0], eps)
        assertEquals(h, out[1], eps)
        curlTapPath(1f, w, h, h, out)
        assertEquals(-w, out[0], eps)
        assertEquals(h, out[1], eps)
        // Halfway the bottom corner has lifted towards (0.3W, 0.85H).
        curlTapPath(0.5f, w, h, h, out)
        assertTrue(out[1] < h)
        // The top corner bends downwards instead.
        curlTapPath(0.5f, w, h, 0f, out)
        assertTrue(out[1] > 0f)
    }

    @Test
    fun releaseCommitsPastTheMiddleOrOnAFling() {
        assertTrue(shouldCommitCurl(forward = true, touchX = 150f, width = w, velocityX = 0f))
        assertFalse(shouldCommitCurl(forward = true, touchX = 300f, width = w, velocityX = 0f))
        assertTrue(shouldCommitCurl(forward = true, touchX = 300f, width = w, velocityX = -1500f))
        assertTrue(shouldCommitCurl(forward = false, touchX = 50f, width = w, velocityX = 0f))
        assertFalse(shouldCommitCurl(forward = false, touchX = -50f, width = w, velocityX = 0f))
        assertTrue(shouldCommitCurl(forward = false, touchX = -300f, width = w, velocityX = 1500f))
    }
}
