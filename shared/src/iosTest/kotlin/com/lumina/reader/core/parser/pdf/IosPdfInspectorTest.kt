package com.lumina.reader.core.parser.pdf

import com.lumina.reader.core.parser.TestFiles
import com.lumina.reader.core.parser.utf8
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CoreGraphics reads the page count and renders the cover, on the simulator. */
class IosPdfInspectorTest {

    /** A small valid PDF: [pages] pages of [width] x [height] points with a filled rectangle. */
    private fun pdf(pages: Int, width: Int, height: Int): ByteArray {
        val content = "0 0 1 rg 20 20 ${width - 40} ${height - 40} re f"
        val pageIds = (0 until pages).map { 4 + it }
        val objects = buildList {
            add("<< /Type /Catalog /Pages 2 0 R >>")
            add("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count $pages >>")
            add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
            repeat(pages) { add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Contents 3 0 R >>") }
        }
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { index, body ->
            offsets.add(out.length)
            out.append("${index + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n").append("0000000000 65535 f \n")
        for (offset in offsets) out.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().utf8()
    }

    /** Width and height from the JPEG's start-of-frame marker. */
    private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
        fun u(i: Int) = bytes[i].toInt() and 0xFF
        var i = 2
        while (i + 9 < bytes.size) {
            if (u(i) != 0xFF) return null
            val marker = u(i + 1)
            val length = (u(i + 2) shl 8) or u(i + 3)
            if (marker == 0xC0 || marker == 0xC2) {
                val height = (u(i + 5) shl 8) or u(i + 6)
                val width = (u(i + 7) shl 8) or u(i + 8)
                return width to height
            }
            i += 2 + length
        }
        return null
    }

    @Test
    fun countsPagesAndRendersTheFirstOneAsJpeg() {
        val summary = TestFiles.withFile(pdf(pages = 3, width = 300, height = 400), ".pdf") { inspectPdf(it) }
        assertNotNull(summary)
        assertEquals(3, summary.pageCount)
        val cover = assertNotNull(summary.coverBytes)
        assertTrue(cover.size > 100 && cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte())
        // 1.5x, as Android's PdfRenderer cover, at one pixel per point.
        assertEquals(450 to 600, jpegSize(cover))
    }

    @Test
    fun largePagesAreScaledDownToTheCoverLimit() {
        val summary = TestFiles.withFile(pdf(pages = 1, width = 2000, height = 1000), ".pdf") { inspectPdf(it) }
        assertEquals(1200 to 600, jpegSize(assertNotNull(assertNotNull(summary).coverBytes)))
    }

    @Test
    fun unreadableFilesGiveNull() {
        assertNull(TestFiles.withFile("not a pdf".utf8(), ".pdf") { inspectPdf(it) })
        assertNull(inspectPdf(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "missing-lumina-test.pdf"))
    }

    @Test
    fun parserBuildsOneChapterPerPage() {
        val book = TestFiles.withFile(pdf(pages = 7, width = 200, height = 300), ".pdf") { PdfParser().parse(it, "Атлас.pdf") }
        assertEquals("Атлас", book.title)
        assertEquals(7, book.chapters.size)
        assertEquals(listOf(0, 5, 6), book.tableOfContents.map { it.chapterIndex })
        assertNotNull(book.coverBytes)
    }
}
