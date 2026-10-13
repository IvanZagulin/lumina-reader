package com.lumina.reader.core.opds

import com.lumina.reader.core.model.BookFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which format the iPhone takes when a catalogue offers several.
 *
 * EPUB first: it is what the reader was built around there, and its text,
 * images and notes come through whole. Android prefers the stored FB2 and has
 * its own copy of this test, which is why it is not a common one.
 */
class OpdsFormatsPreferenceTest {

    @Test
    fun epubComesFirstAndFb2AfterIt() {
        val acquisitions = listOf(
            OpdsAcquisition("https://x.org/books/1.pdf", BookFormat.PDF),
            OpdsAcquisition("https://x.org/books/1.fb2.zip", BookFormat.FB2_ZIP),
            OpdsAcquisition("https://x.org/books/1.epub", BookFormat.EPUB)
        )

        assertEquals("https://x.org/books/1.epub", OpdsFormats.preferred(acquisitions)?.url)
        assertEquals(
            listOf(BookFormat.EPUB, BookFormat.FB2_ZIP, BookFormat.PDF),
            OpdsFormats.ranked(acquisitions).map { it.format }
        )
        assertNull(OpdsFormats.preferred(emptyList()))
    }

    @Test
    fun anEpubMadeToOrderStillWaitsBehindTheStoredFb2() {
        // Flibusta converts /b/<id>/epub on request and fails at it often enough
        // that the FB2 it keeps ready is the better first try even here, where
        // EPUB is otherwise the format of choice.
        val flibusta = listOf(
            OpdsAcquisition("https://flibusta.is/b/1/epub", BookFormat.EPUB),
            OpdsAcquisition("https://flibusta.is/b/1/fb2", BookFormat.FB2_ZIP)
        )

        assertEquals(
            listOf(BookFormat.FB2_ZIP, BookFormat.EPUB),
            OpdsFormats.ranked(flibusta).map { it.format }
        )
    }
}
