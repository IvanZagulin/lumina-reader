package com.lumina.reader.core.library

import com.lumina.reader.core.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFormatDetectorTest {

    private val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)

    private fun supported(format: BookFormat) = FormatDetection.Supported(format)

    @Test
    fun recognisesPdfByMagicBytesWhateverTheName() {
        val header = "%PDF-1.7\n%âãÏÓ".toByteArray(Charsets.ISO_8859_1)
        assertEquals(supported(BookFormat.PDF), BookFormatDetector.detect(header, fileName = "book.txt"))
    }

    @Test
    fun zipWithContainerXmlIsEpub() {
        val names = listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf")
        assertEquals(
            supported(BookFormat.EPUB),
            BookFormatDetector.detect(zipHeader, fileName = "download", zipEntryNames = names)
        )
    }

    @Test
    fun zipWithFb2InsideIsFb2Zip() {
        val names = listOf("Tolstoy_Voina_i_mir.fb2")
        assertEquals(
            supported(BookFormat.FB2_ZIP),
            BookFormatDetector.detect(zipHeader, fileName = "fb2", mimeType = "application/zip", zipEntryNames = names)
        )
    }

    @Test
    fun zipWithoutBookIsRejected() {
        val result = BookFormatDetector.detect(
            zipHeader,
            fileName = "photos.zip",
            zipEntryNames = listOf("a.jpg", "b.jpg")
        )
        assertTrue(result is FormatDetection.Unsupported)
    }

    @Test
    fun fictionBookXmlIsFb2EvenWithWrongExtension() {
        val header = """<?xml version="1.0" encoding="windows-1251"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">""".toByteArray()
        assertEquals(supported(BookFormat.FB2), BookFormatDetector.detect(header, fileName = "book.txt"))
    }

    @Test
    fun htmlErrorPageNamedLikeABookIsRejected() {
        val header = "<!DOCTYPE html><html><head><title>Ошибка</title>".toByteArray()
        val result = BookFormatDetector.detect(header, fileName = "book.fb2", mimeType = "text/html")
        assertTrue(result is FormatDetection.Unsupported)
        assertTrue((result as FormatDetection.Unsupported).message.contains("веб-страница"))
    }

    @Test
    fun videoRenamedToTxtIsRejected() {
        val header = byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray() + ByteArray(64)
        val result = BookFormatDetector.detect(header, fileName = "film.txt", mimeType = "text/plain")
        assertTrue(result is FormatDetection.Unsupported)
    }

    @Test
    fun mobiIsRejectedWithItsName() {
        val header = ByteArray(60) + "BOOKMOBI".toByteArray() + ByteArray(32)
        val result = BookFormatDetector.detect(header, fileName = "book.mobi")
        assertTrue((result as FormatDetection.Unsupported).message.contains("MOBI"))
    }

    @Test
    fun plainTextWithUnknownNameBecomesTxt() {
        val header = "Глава 1\nЖил-был кот.".toByteArray()
        assertEquals(supported(BookFormat.TXT), BookFormatDetector.detect(header, fileName = "notes"))
    }

    @Test
    fun textClaimingToBeEpubIsRejected() {
        val header = "Not found".toByteArray()
        val result = BookFormatDetector.detect(header, fileName = null, formatHint = BookFormat.EPUB)
        assertTrue(result is FormatDetection.Unsupported)
    }

    @Test
    fun unknownBinaryIsRejected() {
        val header = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x10, 0x11, 0x00, 0x7F)
        assertTrue(BookFormatDetector.detect(header, fileName = "data.bin") is FormatDetection.Unsupported)
    }

    @Test
    fun strictNameAndMimeMapping() {
        assertEquals(BookFormat.FB2_ZIP, BookFormatDetector.formatFromFileName("Книга.FB2.ZIP"))
        assertEquals(BookFormat.EPUB, BookFormatDetector.formatFromFileName("dir/book.epub"))
        assertNull(BookFormatDetector.formatFromFileName("archive.zip"))
        assertNull(BookFormatDetector.formatFromFileName("video.mp4"))
        assertEquals(BookFormat.FB2_ZIP, BookFormatDetector.formatFromMimeType("application/fb2+zip"))
        assertEquals(BookFormat.FB2, BookFormatDetector.formatFromMimeType("application/x-fictionbook+xml; charset=utf-8"))
        assertNull(BookFormatDetector.formatFromMimeType("application/octet-stream"))
    }

    @Test
    fun picksTheFb2EntryOfAnArchive() {
        assertEquals("dir/book.fb2", BookFormatDetector.fb2EntryName(listOf("dir/", "cover.jpg", "dir/book.fb2")))
        assertEquals("book.xml", BookFormatDetector.fb2EntryName(listOf("book.xml")))
        assertNull(BookFormatDetector.fb2EntryName(listOf("a.xml", "b.xml")))
    }
}
