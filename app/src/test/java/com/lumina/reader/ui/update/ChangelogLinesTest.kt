package com.lumina.reader.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogLinesTest {

    @Test
    fun bulletsHeadingsAndPlainLines() {
        val notes = "## Что нового\n\n- Живая полка\n* Новый читатель\n• Загрузки\nСпасибо!\n-\n"
        assertEquals(
            listOf(
                false to "Что нового",
                true to "Живая полка",
                true to "Новый читатель",
                true to "Загрузки",
                false to "Спасибо!",
                false to "-"
            ),
            changelogLines(notes)
        )
    }

    @Test
    fun emptyNotesGiveNoLines() {
        assertEquals(emptyList<Pair<Boolean, String>>(), changelogLines("  \n\n"))
    }
}
