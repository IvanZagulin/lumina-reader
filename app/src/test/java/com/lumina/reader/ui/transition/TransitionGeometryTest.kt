package com.lumina.reader.ui.transition

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionGeometryTest {

    private val eps = 0.01f

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    @Test
    fun stageIsCentredAndCapped() {
        // 360 x 800 dp at density 1: w = 0.44 * 360 = 158.4, h = 237.6, centre y = 368.
        val stage = TransitionGeometry.stageRect(360f, 800f, maxWidthPx = 260f)
        assertEquals(158.4f, stage.width, eps)
        assertEquals(237.6f, stage.height, eps)
        assertEquals(180f, stage.center.x, eps)
        assertEquals(368f, stage.center.y, eps)

        val tablet = TransitionGeometry.stageRect(1000f, 1600f, maxWidthPx = 260f)
        assertEquals(260f, tablet.width, eps)
        assertEquals(390f, tablet.height, eps)
    }

    @Test
    fun centeredSourceIsASmallerStage() {
        val stage = Rect(100f, 200f, 300f, 500f)
        val source = TransitionGeometry.centeredSource(stage)
        assertEquals(stage.center.x, source.center.x, eps)
        assertEquals(stage.center.y, source.center.y, eps)
        assertEquals(120f, source.width, eps)
        assertEquals(180f, source.height, eps)
    }

    @Test
    fun lerpAndWindow() {
        val a = Rect(0f, 0f, 10f, 10f)
        val b = Rect(10f, 20f, 30f, 60f)
        assertRect(a, TransitionGeometry.lerpRect(a, b, 0f))
        assertRect(b, TransitionGeometry.lerpRect(a, b, 1f))
        assertRect(Rect(5f, 10f, 20f, 35f), TransitionGeometry.lerpRect(a, b, 0.5f))

        assertEquals(0f, TransitionGeometry.window(0.2f, 0.5f, 1f), eps)
        assertEquals(0.5f, TransitionGeometry.window(0.75f, 0.5f, 1f), eps)
        assertEquals(1f, TransitionGeometry.window(1.2f, 0.5f, 1f), eps)
    }

    @Test
    fun arcAndWobbleVanishAtBothEnds() {
        assertEquals(0f, TransitionGeometry.arcOffset(0f, 32f), eps)
        assertEquals(-32f, TransitionGeometry.arcOffset(0.5f, 32f), eps)
        assertEquals(0f, TransitionGeometry.arcOffset(1f, 32f), eps)
        assertEquals(-10f, TransitionGeometry.flightRotation(0.5f, hingeSign = -1f), eps)
        assertEquals(0f, TransitionGeometry.flightRotation(1f, hingeSign = -1f), eps)
    }

    @Test
    fun openSpreadIsCentredOnTheClosedBook() {
        val book = Rect(100f, 0f, 200f, 150f)
        assertRect(book, TransitionGeometry.pageRect(book, hinge = 0f))
        val open = TransitionGeometry.pageRect(book, hinge = 1f)
        // Spine at the old centre; the cover (mirrored to the left) and page span 100..200 ± 50.
        assertEquals(150f, open.left, eps)
        assertEquals(250f, open.right, eps)
        val spreadLeft = open.left - book.width
        assertEquals(book.center.x, (spreadLeft + open.right) / 2f, eps)
    }

    @Test
    fun readerShotKeepsItsAspectRatio() {
        val page = Rect(0f, 0f, 100f, 200f)
        val dst = TransitionGeometry.fitHeightCentered(Size(1080f, 2400f), page)
        assertEquals(200f, dst.height, eps)
        assertEquals(90f, dst.width, eps)
        assertEquals(page.center.x, dst.center.x, eps)
    }

    @Test
    fun scaleAboutPivot() {
        val rect = Rect(0f, 0f, 100f, 100f)
        assertRect(Rect(25f, 25f, 75f, 75f), TransitionGeometry.scaleAbout(rect, Offset(50f, 50f), 0.5f))
    }

    @Test
    fun onScreenChecks() {
        assertTrue(TransitionGeometry.isOnScreen(Rect(-10f, 10f, 5f, 20f), 100f, 100f))
        assertFalse(TransitionGeometry.isOnScreen(Rect(-30f, 10f, -1f, 20f), 100f, 100f))
        assertFalse(TransitionGeometry.isOnScreen(Rect(10f, 120f, 20f, 140f), 100f, 100f))
        assertFalse(TransitionGeometry.isOnScreen(Rect.Zero, 100f, 100f))
    }

    @Test
    fun closeTargetPrefersOriginThenHeroThenAnySlot() {
        val visible = Rect(10f, 10f, 50f, 70f)
        val offscreen = Rect(-200f, 10f, -150f, 70f)
        val origin = SlotCandidate("shelf:new:7", 7, visible)
        val hero = SlotCandidate("hero", 7, visible)
        val other = SlotCandidate("shelf:main:7", 7, visible)
        val foreign = SlotCandidate("shelf:main:8", 8, visible)

        assertEquals(
            origin,
            TransitionGeometry.pickCloseTarget(listOf(other, hero, origin), "shelf:new:7", 7, 400f, 800f)
        )
        assertEquals(
            hero,
            TransitionGeometry.pickCloseTarget(listOf(other, hero, origin.copy(rect = offscreen)), "shelf:new:7", 7, 400f, 800f)
        )
        assertEquals(other, TransitionGeometry.pickCloseTarget(listOf(foreign, other), null, 7, 400f, 800f))
        // The hero showing another book is not a target.
        assertNull(TransitionGeometry.pickCloseTarget(listOf(foreign, hero.copy(bookId = 8)), "x", 7, 400f, 800f))
    }
}
