package com.lumina.reader.core.library

import com.lumina.reader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportFilesTest {

    @Test
    fun storedNamesAreRandomAndCannotEscapeTheBooksDirectory() {
        val first = BookFileNames.newStoredName(BookFormat.FB2_ZIP)
        val second = BookFileNames.newStoredName(BookFormat.FB2_ZIP)
        assertNotEquals(first, second)
        assertTrue(first.endsWith(".fb2.zip"))
        assertTrue(BookFileNames.isSafeStoredName(first))
        assertFalse(BookFileNames.isSafeStoredName("../databases/lumina_reader.db"))
        assertFalse(first.contains('/'))
    }

    @Test
    fun displayNamesLoseDirectoriesAndControlCharacters() {
        assertEquals("lumina_reader.db", BookFileNames.sanitizeDisplayName("../../databases/lumina_reader.db"))
        assertEquals("book.fb2", BookFileNames.sanitizeDisplayName("C:\\Users\\me\\book.fb2"))
        assertEquals("Война и мир.epub", BookFileNames.sanitizeDisplayName("  Война\u0000 и мир.epub "))
        assertNull(BookFileNames.sanitizeDisplayName(".."))
        assertNull(BookFileNames.sanitizeDisplayName("folder/"))
    }

    @Test
    fun titleFromFileNameDropsBookExtensions() {
        assertEquals("Толстой Война и мир", BookFileNames.titleFromFileName("Толстой_Война_и_мир.fb2.zip"))
        assertEquals("Dune", BookFileNames.titleFromFileName("/sdcard/Dune.epub"))
        assertNull(BookFileNames.titleFromFileName(".epub"))
    }

    @Test
    fun readsFileNamesFromContentDisposition() {
        assertEquals(
            "Толстой.fb2.zip",
            BookFileNames.fileNameFromContentDisposition(
                "attachment; filename=\"fallback.zip\"; filename*=UTF-8''%D0%A2%D0%BE%D0%BB%D1%81%D1%82%D0%BE%D0%B9.fb2.zip"
            )
        )
        assertEquals("Book One.epub", BookFileNames.fileNameFromContentDisposition("attachment; filename=\"Book One.epub\""))
        assertEquals("book.fb2", BookFileNames.fileNameFromContentDisposition("attachment; filename=book.fb2"))
        assertEquals("evil.fb2", BookFileNames.fileNameFromContentDisposition("attachment; filename=\"../../evil.fb2\""))
        assertNull(BookFileNames.fileNameFromContentDisposition("inline"))
        assertNull(BookFileNames.fileNameFromContentDisposition(null))
    }

    @Test
    fun readsFileNamesFromUrls() {
        assertEquals("fb2", BookFileNames.fileNameFromUrl("https://flibusta.is/b/12345/fb2"))
        assertEquals("Мир.epub", BookFileNames.fileNameFromUrl("https://host/files/%D0%9C%D0%B8%D1%80.epub?x=1"))
        assertNull(BookFileNames.fileNameFromUrl("https://host/"))
    }

    @Test
    fun replacesTitlesDerivedFromTheRandomFileName() {
        val stored = "4f1c2d3e-aaaa-bbbb-cccc-123456789abc.fb2.zip"
        assertEquals(
            "Каталожное название",
            ImportMetadata.resolveTitle("4f1c2d3e-aaaa-bbbb-cccc-123456789abc.fb2", stored, "Каталожное название", "x.fb2")
        )
        assertEquals(
            "Мой файл",
            ImportMetadata.resolveTitle("4f1c2d3e-aaaa-bbbb-cccc-123456789abc", stored, null, "Мой_файл.txt")
        )
        assertEquals("Настоящее название", ImportMetadata.resolveTitle("Настоящее название", stored, "Другое", null))
        assertEquals("Без названия", ImportMetadata.resolveTitle("", stored, null, null))
    }

    @Test
    fun replacesPlaceholderAuthorsWithCatalogAuthor() {
        assertEquals("Лев Толстой", ImportMetadata.resolveAuthor("Неизвестный автор", "Лев Толстой"))
        assertEquals("Фрэнк Герберт", ImportMetadata.resolveAuthor("Фрэнк Герберт", "Другой"))
        assertEquals("Неизвестный автор", ImportMetadata.resolveAuthor("", null))
    }

    @Test
    fun copyHashesContentAndEnforcesTheLimit() {
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
    fun inspectsAndExtractsArchivesWithAByteCap() {
        val dir = createTempDir()
        try {
            val zip = File(dir, "book.zip")
            writeZip(zip, mapOf("cover.jpg" to ByteArray(10), "book.fb2" to ByteArray(4096) { 'a'.code.toByte() }))

            val summary = ZipInspector.inspect(zip)
            assertEquals(listOf("cover.jpg", "book.fb2"), summary.entryNames)
            assertEquals(4106L, summary.declaredUncompressedBytes)

            val extracted = File(dir, "out.fb2")
            val result = ZipInspector.extractEntry(zip, "book.fb2", extracted, maxBytes = 10_000)
            assertEquals(4096L, result.bytes)
            assertEquals(4096L, extracted.length())

            // A highly compressible entry larger than the cap is cut off (zip bomb guard).
            try {
                ZipInspector.extractEntry(zip, "book.fb2", File(dir, "bomb.fb2"), maxBytes = 1000)
                fail("cap not enforced")
            } catch (e: ImportException) {
                assertTrue(e.userMessage.contains("слишком большая"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchivesWithTooManyEntriesAndBrokenArchives() {
        val dir = createTempDir()
        try {
            val zip = File(dir, "many.zip")
            writeZip(zip, (1..20).associate { "f$it.txt" to ByteArray(1) })
            try {
                ZipInspector.inspect(zip, maxEntries = 10)
                fail("entry cap not enforced")
            } catch (e: ImportException) {
                assertTrue(e.userMessage.contains("много"))
            }

            val broken = File(dir, "broken.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)) }
            try {
                ZipInspector.inspect(broken)
                fail("broken archive accepted")
            } catch (e: ImportException) {
                assertTrue(e.userMessage.contains("повреждён"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readHeaderReturnsAtMostTheRequestedBytes() {
        val dir = createTempDir()
        try {
            val file = File(dir, "f").apply { writeBytes(ByteArray(20) { it.toByte() }) }
            assertEquals(8, StreamCopier.readHeader(file, 8).size)
            assertEquals(20, StreamCopier.readHeader(file, 64).size)
        } finally {
            dir.deleteRecursively()
        }
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
