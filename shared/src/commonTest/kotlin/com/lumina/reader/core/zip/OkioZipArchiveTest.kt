package com.lumina.reader.core.zip

import com.lumina.reader.core.parser.TestFiles
import com.lumina.reader.core.parser.TestZip
import com.lumina.reader.core.parser.epub.PortableEpubArchive
import com.lumina.reader.core.parser.utf8
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkioZipArchiveTest {

    private val entries = linkedMapOf(
        "mimetype" to "application/epub+zip".utf8(),
        "OEBPS/Text/Глава 1.xhtml" to "<p>Один</p>".utf8(),
        "OEBPS/Images/pic.bin" to ByteArray(70_000) { (it * 31 % 256).toByte() },
        "META-INF/container.xml" to "<container/>".utf8()
    )

    @Test
    fun listsFilesWithSizesAndReadsThem() {
        for (deflate in listOf(true, false)) {
            TestFiles.withFile(TestZip.build(entries, deflate), ".zip") { path ->
                openOkioZipArchive(path).use { zip ->
                    // Okio lists entries sorted by path, without the implied directories.
                    assertEquals(entries.keys.sorted(), zip.names.sorted())
                    assertEquals(entries.size, zip.names.size)
                    for ((name, bytes) in entries) {
                        assertEquals(bytes.size.toLong(), zip.size(name), name)
                        assertContentEquals(bytes, zip.open(name).buffer().use { it.readByteArray() }, name)
                    }
                    assertEquals(-1L, zip.size("missing"))
                    assertFailsWith<okio.IOException> { zip.open("missing") }
                }
            }
        }
    }

    @Test
    fun epubArchiveResolvesNamesCaseInsensitivelyAndCapsReads() {
        TestFiles.withFile(TestZip.build(entries), ".epub") { path ->
            PortableEpubArchive.open(path).use { archive ->
                assertTrue(archive.entryNames.containsAll(entries.keys))
                assertEquals("OEBPS/Text/Глава 1.xhtml", archive.resolveName("oebps/text/глава 1.XHTML"))
                assertNull(archive.resolveName("OEBPS/Text/none.xhtml"))
                assertEquals(70_000L, archive.size("OEBPS/Images/pic.bin"))
                assertContentEquals(entries.getValue("mimetype"), archive.read("MIMETYPE", 100))
                assertNull(archive.read("OEBPS/Images/pic.bin", 69_999))
                assertEquals(70_000, archive.read("OEBPS/Images/pic.bin", 70_000)?.size)
            }
        }
    }

    @Test
    fun aFileThatIsNotAZipOpensAsAnEmptyEpubArchive() {
        TestFiles.withFile("not a zip".utf8(), ".epub") { path ->
            assertFailsWith<okio.IOException> { openOkioZipArchive(path) }
            PortableEpubArchive.open(path).use { archive ->
                assertEquals(emptyList(), archive.entryNames)
                assertNull(archive.read("mimetype", 100))
            }
        }
    }
}
