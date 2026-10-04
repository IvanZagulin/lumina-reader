package com.lumina.reader.core.library

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.TestZip
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals("abc.epub", BookFileNames.newStoredName(BookFormat.EPUB, id = "abc"))
    }

    @Test
    fun displayNamesLoseDirectoriesAndControlCharacters() {
        assertEquals("lumina_reader.db", BookFileNames.sanitizeDisplayName("../../databases/lumina_reader.db"))
        assertEquals("book.fb2", BookFileNames.sanitizeDisplayName("C:\\Users\\me\\book.fb2"))
        assertEquals("Война и мир.epub", BookFileNames.sanitizeDisplayName("  Война\u0000 и мир.epub "))
        assertEquals("ab", BookFileNames.sanitizeDisplayName("a\u001F\u007Fb"))
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
    fun contentDispositionCharsetsAndBrokenEscapes() {
        // "+" stays a plus sign; the declared charset decodes the bytes.
        assertEquals(
            "Мир+.fb2",
            BookFileNames.fileNameFromContentDisposition("attachment; filename*=windows-1251''%CC%E8%F0+.fb2")
        )
        assertEquals("a b.epub", BookFileNames.fileNameFromContentDisposition("attachment; filename*=''a%20b.epub"))
        // A broken escape or an unknown charset falls back to the plain name.
        assertEquals(
            "plain.epub",
            BookFileNames.fileNameFromContentDisposition("attachment; filename*=UTF-8''bad%zz.epub; filename=plain.epub")
        )
        assertEquals(
            "plain.epub",
            BookFileNames.fileNameFromContentDisposition("attachment; filename*=no-such-charset''x%41.epub; filename=plain.epub")
        )
    }

    @Test
    fun readsFileNamesFromUrls() {
        assertEquals("fb2", BookFileNames.fileNameFromUrl("https://flibusta.is/b/12345/fb2"))
        assertEquals("Мир.epub", BookFileNames.fileNameFromUrl("https://host/files/%D0%9C%D0%B8%D1%80.epub?x=1"))
        assertEquals("a+b.epub", BookFileNames.fileNameFromUrl("https://host/a+b.epub#part"))
        assertEquals("100%.txt", BookFileNames.fileNameFromUrl("https://host/dir/100%.txt"))
        assertNull(BookFileNames.fileNameFromUrl("https://host/"))
        assertNull(BookFileNames.fileNameFromUrl(""))
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
        val output = Buffer()
        val result = StreamCopier.copy(Buffer().writeUtf8("abc"), output, maxBytes = 10)
        assertEquals(3L, result.bytes)
        assertEquals(ABC_SHA256, result.sha256)
        assertEquals("abc", output.readUtf8())

        val error = assertFailsWith<ImportException> {
            StreamCopier.copy(Buffer().write(ByteArray(100)), Buffer(), maxBytes = 99, tooLargeMessage = "big")
        }
        assertEquals("big", error.userMessage)
        assertEquals(100L, StreamCopier.copy(Buffer().write(ByteArray(100)), Buffer(), maxBytes = 100).bytes)
    }

    @Test
    fun copyChecksBeforeEveryReadAndReportsRunningTotals() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val totals = mutableListOf<Long>()
        var checks = 0
        val output = Buffer()
        val result = StreamCopier.copy(
            source = Buffer().write(bytes),
            sink = output,
            maxBytes = Long.MAX_VALUE,
            beforeChunk = { checks++ },
            onProgress = { totals += it }
        )
        assertEquals(bytes.size.toLong(), result.bytes)
        assertEquals(bytes.toByteString().sha256().hex(), result.sha256)
        assertContentEquals(bytes, output.readByteArray())
        assertEquals(bytes.size.toLong(), totals.last())
        assertEquals(totals.sorted(), totals)
        // One check per read, including the final read that reports the end.
        assertEquals(totals.size + 1, checks)

        // A cancellation check that throws stops the copy.
        assertFailsWith<IllegalStateException> {
            StreamCopier.copy(Buffer().write(bytes), Buffer(), Long.MAX_VALUE, beforeChunk = { error("cancelled") })
        }
    }

    @Test
    fun hashesAndHeadersOfFiles() {
        withTempDir { dir ->
            val file = dir / "f"
            FileSystem.SYSTEM.write(file) { write(ByteArray(20) { it.toByte() }) }
            assertContentEquals(ByteArray(8) { it.toByte() }, StreamCopier.readHeader(file, 8))
            assertEquals(20, StreamCopier.readHeader(file, 64).size)
            assertEquals(20, StreamCopier.readHeader(file).size)

            val abc = dir / "abc"
            FileSystem.SYSTEM.write(abc) { writeUtf8("abc") }
            assertEquals(ABC_SHA256, StreamCopier.sha256(abc))
            assertNull(StreamCopier.sha256(dir / "missing"))
            assertFailsWith<Exception> { StreamCopier.readHeader(dir / "missing") }
        }
    }

    @Test
    fun inspectsAndExtractsArchivesWithAByteCap() {
        withTempDir { dir ->
            val zip = dir / "book.zip"
            val text = ByteArray(4096) { 'a'.code.toByte() }
            // Names in sorted order: Android lists archive order, iOS sorted order.
            write(zip, TestZip.build(linkedMapOf("book.fb2" to text, "cover.jpg" to ByteArray(10))))

            val summary = ZipInspector.inspect(zip)
            assertEquals(listOf("book.fb2", "cover.jpg"), summary.entryNames)
            assertEquals(4106L, summary.declaredUncompressedBytes)

            val extracted = dir / "out.fb2"
            val result = ZipInspector.extractEntry(zip, "book.fb2", extracted, maxBytes = 10_000)
            assertEquals(4096L, result.bytes)
            assertEquals(text.toByteString().sha256().hex(), result.sha256)
            assertContentEquals(text, FileSystem.SYSTEM.read(extracted) { readByteArray() })

            // A highly compressible entry larger than the cap is cut off (zip bomb guard).
            val tooLarge = assertFailsWith<ImportException> {
                ZipInspector.extractEntry(zip, "book.fb2", dir / "bomb.fb2", maxBytes = 1000)
            }
            assertTrue(tooLarge.userMessage.contains("слишком большая"), tooLarge.userMessage)

            val missing = assertFailsWith<ImportException> {
                ZipInspector.extractEntry(zip, "other.fb2", dir / "other.fb2", maxBytes = 10_000)
            }
            assertEquals("В архиве не найден файл книги", missing.userMessage)
        }
    }

    @Test
    fun rejectsArchivesWithTooManyEntriesAndBrokenArchives() {
        withTempDir { dir ->
            val zip = dir / "many.zip"
            write(zip, TestZip.build((1..20).associate { "f$it.txt" to ByteArray(1) }))
            val many = assertFailsWith<ImportException> { ZipInspector.inspect(zip, maxEntries = 10) }
            assertTrue(many.userMessage.contains("много"), many.userMessage)
            assertEquals(20, ZipInspector.inspect(zip, maxEntries = 20).entryNames.size)

            val broken = dir / "broken.zip"
            write(broken, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3))
            val error = assertFailsWith<ImportException> { ZipInspector.inspect(broken) }
            assertTrue(error.userMessage.contains("повреждён"), error.userMessage)

            val missing = assertFailsWith<ImportException> { ZipInspector.inspect(dir / "missing.zip") }
            assertTrue(missing.userMessage.contains("повреждён"), missing.userMessage)
        }
    }

    private fun write(path: okio.Path, bytes: ByteArray) {
        FileSystem.SYSTEM.write(path) { write(bytes) }
    }

    private companion object {
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
