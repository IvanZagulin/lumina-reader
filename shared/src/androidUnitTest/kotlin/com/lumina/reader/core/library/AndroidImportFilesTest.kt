package com.lumina.reader.core.library

import okio.Buffer
import okio.Path.Companion.toOkioPath
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * The Android side of the import helpers: the java.io / java.util.zip code the
 * app has always used, and the portable versions (what the iPhone runs)
 * checked against it.
 */
class AndroidImportFilesTest {

    @Test
    fun inputStreamCopyHashesContentAndEnforcesTheLimit() {
        val output = ByteArrayOutputStream()
        val result = StreamCopier.copy(ByteArrayInputStream("abc".toByteArray()), output, maxBytes = 10)
        assertEquals(3L, result.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
        assertEquals("abc", output.toString("UTF-8"))

        try {
            StreamCopier.copy(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), maxBytes = 99, tooLargeMessage = "big")
            fail("limit not enforced")
        } catch (e: ImportException) {
            assertEquals("big", e.userMessage)
        }
    }

    @Test
    fun portableCopyMatchesTheJavaIoCopy() {
        val random = Random(7)
        for (size in listOf(0, 1, 100, 65_535, 65_536, 65_537, 300_001)) {
            val bytes = random.nextBytes(size)
            val javaOut = ByteArrayOutputStream()
            val java = StreamCopier.copy(ByteArrayInputStream(bytes), javaOut, maxBytes = Long.MAX_VALUE)
            val portableOut = Buffer()
            val portable = StreamCopier.copy(ByteArrayInputStream(bytes).source(), portableOut, maxBytes = Long.MAX_VALUE)
            assertEquals("size $size", java, portable)
            assertArrayEquals(javaOut.toByteArray(), portableOut.readByteArray())

            for (limit in listOf(0L, size - 1L, size.toLong())) {
                if (limit < 0) continue
                val javaFails = runCatching { StreamCopier.copy(ByteArrayInputStream(bytes), ByteArrayOutputStream(), limit) }
                val portableFails = runCatching { StreamCopier.copy(ByteArrayInputStream(bytes).source(), Buffer(), limit) }
                assertEquals("size $size limit $limit", javaFails.getOrNull(), portableFails.getOrNull())
                assertEquals(
                    (javaFails.exceptionOrNull() as? ImportException)?.userMessage,
                    (portableFails.exceptionOrNull() as? ImportException)?.userMessage
                )
            }
        }
    }

    @Test
    fun fileHashesAndHeadersMatchThePortableReaders() {
        val dir = createTempDir()
        try {
            val random = Random(11)
            for (size in listOf(0, 5, 8192, 8193, 200_000)) {
                val file = File(dir, "f$size").apply { writeBytes(random.nextBytes(size)) }
                val path = file.toOkioPath()
                assertEquals(StreamCopier.sha256(file), StreamCopier.sha256(path))
                assertEquals(StreamCopier.sha256(file), PortableStreams.sha256(path))
                for (count in listOf(1, 8, 8192, 300_000)) {
                    assertArrayEquals(StreamCopier.readHeader(file, count), StreamCopier.readHeader(path, count))
                    assertArrayEquals(StreamCopier.readHeader(file, count), PortableStreams.readHeader(path, count))
                }
            }
            assertEquals(null, StreamCopier.sha256(File(dir, "missing")))
            assertEquals(null, PortableStreams.sha256(File(dir, "missing").toOkioPath()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun archivesAreListedInArchiveOrder() {
        val dir = createTempDir()
        try {
            val zip = File(dir, "book.zip")
            writeZip(zip, mapOf("cover.jpg" to ByteArray(10), "book.fb2" to ByteArray(4096) { 'a'.code.toByte() }))

            val summary = ZipInspector.inspect(zip.toOkioPath())
            assertEquals(listOf("cover.jpg", "book.fb2"), summary.entryNames)
            assertEquals(4106L, summary.declaredUncompressedBytes)

            val extracted = File(dir, "out.fb2")
            val result = ZipInspector.extractEntry(zip.toOkioPath(), "book.fb2", extracted.toOkioPath(), maxBytes = 10_000)
            assertEquals(4096L, result.bytes)
            assertEquals(4096L, extracted.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun portableUrlDecodingMatchesUrlDecoder() {
        val charsets = listOf("UTF-8", "utf-8", "windows-1251", "ISO-8859-1", "KOI8-R", "UTF-16BE", "no-such-charset", "")
        val pieces = listOf(
            "%", "+", "-", "a", "Z", "0", "9", "f", "F", "g", "%2", "%41", "%D0", "%9C", "%CC", "%zz", "%+5", "%-0",
            "%-F", "%٣٣", "%ＡＡ", " ", "ё", "😀", "\uD800", "%%", ".fb2", "%E2%82%AC"
        )
        val random = Random(2024)
        var compared = 0
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(0, 8)) { append(pieces[random.nextInt(pieces.size)]) } }
            val charset = charsets[random.nextInt(charsets.size)]
            val expected = runCatching { URLDecoder.decode(text, charset) }
            val actual = runCatching { UrlDecoding.decode(text, charset) }
            assertEquals("\"$text\" in $charset", expected.getOrNull(), actual.getOrNull())
            if (expected.isSuccess) compared++
        }
        assertTrue("too few successful decodings: $compared", compared > 3_000)
        // The Android actual is the JDK class itself.
        assertEquals(URLDecoder.decode("%D0%9C+x", "UTF-8"), decodeUrlComponent("%D0%9C+x", "UTF-8"))
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "lumina-test-${System.nanoTime()}").apply { mkdirs() }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
