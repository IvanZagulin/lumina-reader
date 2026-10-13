package com.lumina.reader.core.opds

import com.lumina.reader.core.model.BookFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which format Android takes when a catalogue offers several.
 *
 * FB2 first: it is the format Flibusta stores, while its EPUB is converted on
 * request - slower, and sometimes a web page instead of a book. The iPhone
 * prefers EPUB and has its own test, which is why this one is not common.
 */
class OpdsFormatsPreferenceTest {

    @Test
    fun fb2ComesFirstAndEpubAfterIt() {
        val acquisitions = listOf(
            OpdsAcquisition("p", BookFormat.PDF),
            OpdsAcquisition("e", BookFormat.EPUB),
            OpdsAcquisition("f", BookFormat.FB2_ZIP)
        )
        assertEquals("f", OpdsFormats.preferred(acquisitions)?.url)
        assertEquals("e", OpdsFormats.preferred(acquisitions.take(2))?.url)
        assertNull(OpdsFormats.preferred(emptyList()))
    }

    @Test
    fun theStoredFb2BeatsTheEpubMadeToOrder() {
        val flibusta = listOf(
            OpdsAcquisition("https://flibusta.is/b/1/epub", BookFormat.EPUB),
            OpdsAcquisition("https://flibusta.is/b/1/fb2", BookFormat.FB2_ZIP)
        )
        assertEquals(listOf(BookFormat.FB2_ZIP, BookFormat.EPUB), OpdsFormats.ranked(flibusta).map { it.format })
        val file = listOf(
            OpdsAcquisition("https://x.org/books/1.epub", BookFormat.EPUB),
            OpdsAcquisition("https://x.org/books/1.fb2.zip", BookFormat.FB2_ZIP)
        )
        assertEquals("https://x.org/books/1.fb2.zip", OpdsFormats.ranked(file).first().url)
    }
}
