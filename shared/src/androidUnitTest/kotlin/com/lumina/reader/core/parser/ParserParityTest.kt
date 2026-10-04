package com.lumina.reader.core.parser

import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.epub.EpubParser
import com.lumina.reader.core.parser.epub.PortableEpubArchive
import com.lumina.reader.core.parser.epub.ZipFileArchive
import com.lumina.reader.core.parser.epub.openEpubArchive
import com.lumina.reader.core.parser.fb2.Fb2Parser
import com.lumina.reader.core.parser.fb2.PortableFb2Files
import okio.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android reads books with java.util.zip and java.io exactly as before the
 * parsers moved to common code; the iPhone reads them with the Okio code of
 * commonMain. Both run here on the JVM over the parser test books and must
 * give the same [ParsedBook], down to every image byte.
 */
class ParserParityTest {

    @Test
    fun epubContainerReadersGiveTheSameBooks() {
        for ((name, bytes) in EpubFixtures.all()) {
            TestFiles.withFile(bytes, ".epub") { path ->
                val android = EpubParser().parse(path, "$name.epub")
                val portable = EpubParser(PortableEpubArchive::open).parse(path, "$name.epub")
                assertSameBook(name, android, portable)
            }
        }
    }

    @Test
    fun epubArchivesListAndReadTheSameEntries() {
        for ((name, bytes) in EpubFixtures.all()) {
            TestFiles.withFile(bytes, ".epub") { path ->
                openEpubArchive(path).use { android ->
                    assertTrue(name, android is ZipFileArchive)
                    PortableEpubArchive.open(path).use { portable ->
                        // Okio lists entries sorted by path; java.util.zip in archive order.
                        assertEquals(name, android.entryNames.sorted(), portable.entryNames.sorted())
                        for (entry in android.entryNames) {
                            assertEquals("$name/$entry", android.size(entry), portable.size(entry))
                            assertArrayEquals("$name/$entry", android.read(entry, Long.MAX_VALUE), portable.read(entry, Long.MAX_VALUE))
                            assertArrayEquals("$name/$entry", android.read(entry, 100), portable.read(entry, 100))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun fb2FileReadersGiveTheSameBooks() {
        for ((name, bytes) in Fb2Fixtures.all()) {
            TestFiles.withFile(bytes, ".bin") { path ->
                val android = Fb2Parser().parse(path, name)
                val portable = Fb2Parser { file: Path, displayName: String -> PortableFb2Files.parse(file, displayName) }
                    .parse(path, name)
                assertSameBook(name, android, portable)
            }
        }
    }

    private fun assertSameBook(name: String, expected: ParsedBook, actual: ParsedBook) {
        assertEquals("$name: title", expected.title, actual.title)
        assertEquals("$name: author", expected.author, actual.author)
        assertEquals("$name: description", expected.description, actual.description)
        assertEquals("$name: series", expected.seriesName, actual.seriesName)
        assertEquals("$name: series order", expected.seriesOrder, actual.seriesOrder)
        assertEquals("$name: format", expected.format, actual.format)
        assertArrayEquals("$name: cover", expected.coverBytes, actual.coverBytes)
        assertEquals("$name: chapters", expected.chapters, actual.chapters)
        assertEquals("$name: toc", expected.tableOfContents, actual.tableOfContents)
        assertEquals("$name: footnotes", expected.footnotes.toList(), actual.footnotes.toList())
        assertEquals("$name: image ids", expected.images.keys.toList(), actual.images.keys.toList())
        for ((id, image) in expected.images) {
            assertArrayEquals("$name: image $id", image, actual.images[id])
        }
    }
}
