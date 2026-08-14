package com.lumina.reader.core.parser.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class PdfParser : BookParser {

    override fun parse(file: File): ParsedBook {
        val fileName = file.name
        val title = fileName.substringBeforeLast(".")
        val chapters = mutableListOf<Chapter>()
        val tocList = mutableListOf<TocItem>()
        var coverBytes: ByteArray? = null

        try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fileDescriptor)
            val pageCount = renderer.pageCount

            // Extract cover from first page
            if (pageCount > 0) {
                val firstPage = renderer.openPage(0)
                val width = (firstPage.width * 1.5).toInt().coerceAtLeast(300)
                val height = (firstPage.height * 1.5).toInt().coerceAtLeast(400)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                firstPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                firstPage.close()

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                coverBytes = stream.toByteArray()
            }

            for (pageIndex in 0 until pageCount) {
                val pageNum = pageIndex + 1
                val pageTitle = "Страница $pageNum"
                val chapter = Chapter(
                    index = pageIndex,
                    title = pageTitle,
                    content = "PDF Документ — $pageTitle из $pageCount",
                    paragraphs = listOf("PDF Документ — $pageTitle из $pageCount"),
                    pdfPageNumber = pageIndex
                )
                chapters.add(chapter)
                if (pageIndex % 5 == 0 || pageIndex == 0 || pageIndex == pageCount - 1) {
                    tocList.add(TocItem(id = "pdf_page_$pageIndex", title = pageTitle, chapterIndex = pageIndex))
                }
            }

            renderer.close()
            fileDescriptor.close()
        } catch (e: Exception) {
            e.printStackTrace()
            chapters.add(
                Chapter(
                    index = 0,
                    title = "Страница 1",
                    content = "Не удалось открыть PDF документ",
                    paragraphs = listOf("Ошибка чтения PDF файла")
                )
            )
        }

        return ParsedBook(
            title = title,
            author = "PDF Документ",
            description = "Файл формата PDF (${chapters.size} стр.)",
            coverBytes = coverBytes,
            chapters = chapters,
            tableOfContents = tocList,
            format = BookFormat.PDF
        )
    }

    override fun parse(inputStream: InputStream, fileName: String): ParsedBook {
        val tempFile = File.createTempFile("temp_pdf_", ".pdf")
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { out ->
            inputStream.copyTo(out)
        }
        val result = parse(tempFile)
        tempFile.delete()
        return result
    }
}
