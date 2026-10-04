package com.lumina.reader.core.parser

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.epub.EpubParser
import com.lumina.reader.core.parser.epub.EpubPaths
import com.lumina.reader.core.parser.epub.PortableEpubArchive
import com.lumina.reader.core.parser.epub.StreamedArchive
import com.lumina.reader.core.parser.epub.openEpubArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** What only the Android readers do, and the parts of common code that replaced JVM calls. */
class AndroidParserBehaviourTest {

    /** The archive without its central directory, as a download cut short at the end leaves it. */
    private fun withoutCentralDirectory(zip: ByteArray): ByteArray {
        val eocd = zip.size - 22
        val offset = (zip[eocd + 16].toInt() and 0xFF) or ((zip[eocd + 17].toInt() and 0xFF) shl 8) or
            ((zip[eocd + 18].toInt() and 0xFF) shl 16) or ((zip[eocd + 19].toInt() and 0xFF) shl 24)
        return zip.copyOf(offset)
    }

    @Test
    fun androidReadsAnArchiveWithoutCentralDirectoryInOnePass() {
        val bytes = withoutCentralDirectory(EpubFixtures.sampleBook())
        TestFiles.withFile(bytes, ".epub") { path ->
            openEpubArchive(path).use { assertTrue(it is StreamedArchive) }
            val android = EpubParser().parse(path)
            assertEquals(listOf("Глава первая", "Глава вторая", "Примечания"), android.chapters.map { it.title })
            // Okio needs the central directory: the iPhone shows the "no text" chapter for such a file.
            val portable = EpubParser(PortableEpubArchive::open).parse(path)
            assertEquals(listOf("Не удалось извлечь текст из книги"), portable.chapters.single().paragraphs)
        }
    }

    @Test
    fun fileAndInputStreamExtensionsBehaveLikeTheFormerOverloads() {
        val bytes = "Глава 1\n\nТекст первой главы.\n\nГлава 2\n\nТекст второй главы.".toByteArray(Charsets.UTF_8)
        val file = File.createTempFile("lumina_ext_", ".txt")
        try {
            file.writeBytes(bytes)
            // parse(File): the title comes from the file's own name.
            val fromFile = BookParserFactory.getParser(BookFormat.TXT).parse(file)
            assertEquals(file.name.substringBeforeLast("."), fromFile.title)
            // parse(InputStream, name): the given name, and the stream is closed.
            var closed = false
            val stream = object : ByteArrayInputStream(bytes) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
            val fromStream = BookParserFactory.getParser(BookFormat.TXT).parse(stream, "Книга.txt")
            assertEquals("Книга", fromStream.title)
            assertTrue(closed)
            assertEquals(fromFile.chapters, fromStream.chapters)
            assertEquals(2, fromFile.chapters.size)
        } finally {
            file.delete()
        }
    }

    /** EpubPaths.percentDecode as it was written for the JVM (ByteArrayOutputStream, String(bytes, UTF_8)). */
    private fun jvmPercentDecode(text: String): String {
        if (text.indexOf('%') < 0) return text
        val bytes = ByteArrayOutputStream(text.length)
        val pending = StringBuilder()
        fun flushPending() {
            if (pending.isNotEmpty()) {
                bytes.write(pending.toString().toByteArray(Charsets.UTF_8))
                pending.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    flushPending()
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            pending.append(c)
            i++
        }
        flushPending()
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    @Test
    fun percentDecodingMatchesTheJvmVersion() {
        val pieces = listOf(
            "%", "%2", "%20", "%25", "%D0", "%9E", "%d0%9e", "%E2%82", "%AC", "%F0%9F%98%80", "%FF", "%C0%AF", "%ED%A0%80",
            "%zz", "+", "a", "ё", "😀", "/", "#", "%%", "\uD800", "\uDC00"
        )
        val random = Random(6)
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(12)) { append(pieces[random.nextInt(pieces.size)]) } }
            assertEquals(text, jvmPercentDecode(text), EpubPaths.percentDecode(text))
        }
    }
}
