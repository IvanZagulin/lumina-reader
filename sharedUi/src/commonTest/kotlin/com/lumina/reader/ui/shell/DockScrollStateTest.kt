package com.lumina.reader.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule the dock follows while a list is scrolled.
 *
 * The animation itself needs a frame clock and is not what went wrong: the dock
 * was driven by how far the finger moved rather than how far the content did, so
 * at the end of a list it collapsed and expanded in a loop while the overscroll
 * stretched and sprang back. Here only the decision is checked, which is where
 * that mistake lived.
 */
class DockScrollStateTest {

    @Test
    fun contentMovingUpCollapsesTheDockAndMovingDownRestoresIt() {
        assertEquals(1f, DockScrollState.dockTargetFor(-40f))
        assertEquals(0f, DockScrollState.dockTargetFor(40f))
    }

    @Test
    fun aListWithNowhereLeftToGoLeavesTheDockAlone() {
        // The end of a list: the finger keeps dragging, the content consumes
        // nothing. Reacting to that is what made the dock jump up and down.
        assertNull(DockScrollState.dockTargetFor(0f))
    }

    @Test
    fun jitterIsNotADirection() {
        assertNull(DockScrollState.dockTargetFor(DockScrollState.THRESHOLD))
        assertNull(DockScrollState.dockTargetFor(-DockScrollState.THRESHOLD))
        assertNull(DockScrollState.dockTargetFor(0.5f))
    }
}
