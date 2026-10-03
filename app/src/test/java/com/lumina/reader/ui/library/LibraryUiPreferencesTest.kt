package com.lumina.reader.ui.library

import com.lumina.reader.core.preferences.AppUiPreferences
import com.lumina.reader.core.preferences.LibrarySort
import com.lumina.reader.core.preferences.LibraryViewMode
import com.lumina.reader.ui.shell.DockDestination
import com.lumina.reader.ui.transition.OpenAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryUiPreferencesTest {

    @Test
    fun storedEnumNamesFallBackToDefaults() {
        assertEquals(OpenAnimation.FAST, AppUiPreferences.parseEnum("FAST", OpenAnimation.FULL))
        assertEquals(OpenAnimation.FULL, AppUiPreferences.parseEnum("SLOW", OpenAnimation.FULL))
        assertEquals(LibraryViewMode.SHELVES, AppUiPreferences.parseEnum(null, LibraryViewMode.SHELVES))
        assertEquals(LibrarySort.AUTHOR, AppUiPreferences.parseEnum("AUTHOR", LibrarySort.RECENT))
    }

    @Test
    fun dockDestinationsMatchTopLevelRoutes() {
        assertEquals(DockDestination.LIBRARY, DockDestination.forRoute("library"))
        assertEquals(DockDestination.CATALOG, DockDestination.forRoute("catalog"))
        assertEquals(DockDestination.ASSISTANT, DockDestination.forRoute("ai_chat"))
        assertEquals(DockDestination.STATS, DockDestination.forRoute("stats"))
        assertNull(DockDestination.forRoute("reader/{bookId}"))
        assertNull(DockDestination.forRoute(null))
        assertEquals(listOf("Полка", "Каталоги", "Помощник", "Статистика"), DockDestination.entries.map { it.label })
    }

    @Test
    fun openAnimationTitles() {
        assertEquals(listOf("Полная", "Быстрая", "Выкл"), OpenAnimation.entries.map(OpenAnimation::title))
    }
}
