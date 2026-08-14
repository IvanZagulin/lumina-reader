package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPositionTest {

    private val chapters = listOf(
        Chapter(index = 0, title = "One", content = "", paragraphs = listOf("a", "b")),
        Chapter(index = 1, title = "Two", content = "", paragraphs = listOf("c"))
    )

    @Test
    fun restoresOutOfRangePositionToLastAvailableText() {
        assertEquals(ReaderPosition(chapterIndex = 1, paragraphIndex = 0),
            restoreReaderPosition(chapters, chapterIndex = 12, paragraphIndex = 42))
    }

    @Test
    fun preservesEndOfChapterMarker() {
        assertEquals(ReaderPosition(chapterIndex = 0, paragraphIndex = Int.MAX_VALUE),
            restoreReaderPosition(chapters, chapterIndex = 0, paragraphIndex = Int.MAX_VALUE))
    }
}
