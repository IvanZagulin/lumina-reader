package com.lumina.reader.ui.shell

import com.lumina.reader.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dock spells its routes out so it can live in shared code without the
 * Android navigation graph; they must stay the NavHost's, or a tab would
 * navigate nowhere and lose its highlight.
 */
class DockDestinationRoutesTest {

    @Test
    fun routesAreTheNavHostRoutes() {
        assertEquals(Screen.Library.route, DockDestination.LIBRARY.route)
        assertEquals(Screen.Catalog.route, DockDestination.CATALOG.route)
        assertEquals(Screen.AiChat.route, DockDestination.ASSISTANT.route)
        assertEquals(Screen.Stats.route, DockDestination.STATS.route)
        assertEquals(Screen.TopLevelRoutes, DockDestination.entries.map { it.route }.toSet())
    }
}
