package com.lumina.reader.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BookEnumsTest {

    @Test
    fun formatFromFileNameIgnoresCaseAndPrefersDoubleExtension() {
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("Война и мир.EPUB"))
        assertEquals(BookFormat.FB2_ZIP, BookFormat.fromFileName("book.fb2.zip"))
        assertEquals(BookFormat.FB2, BookFormat.fromFileName("book.Fb2"))
        assertEquals(BookFormat.PDF, BookFormat.fromFileName("scan.pdf"))
        assertEquals(BookFormat.TXT, BookFormat.fromFileName("notes.md"))
        assertEquals(BookFormat.TXT, BookFormat.fromFileName("unknown.bin"))
    }

    @Test
    fun formatFromExtensionFallsBackToText() {
        assertEquals(BookFormat.FB2_ZIP, BookFormat.fromExtension("ZIP"))
        assertEquals(BookFormat.TXT, BookFormat.fromExtension("text"))
        assertEquals(BookFormat.TXT, BookFormat.fromExtension("djvu"))
    }

    @Test
    fun shelvesKeepTheirOrderAndTitles() {
        assertEquals(
            listOf("Непрочитанные", "Все", "Читаю", "Избранное", "Прочитано", "По полкам"),
            ReadingStatus.entries.map { it.title }
        )
    }
}
