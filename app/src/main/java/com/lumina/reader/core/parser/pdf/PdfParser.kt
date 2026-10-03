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

/**
 * PDF "parser": one chapter per page titled "Страница N" with no paragraphs
 * (pages are rendered as images by the reader via [Chapter.pdfPageNumber]),
 * plus a cover rendered from the first page.
 */
class PdfParser : BookParser {

    override fun parse(file: File): ParsedBook {
        val title = file.name.substringBeforeLast(".")
        var coverBytes: ByteArray? = null
        var pageCount = 0
        var failed = false

        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            val openedDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = openedDescriptor
            val openedRenderer = PdfRenderer(openedDescriptor)
            renderer = openedRenderer
            pageCount = openedRenderer.pageCount
            if (pageCount > 0) {
                coverBytes = try {
                    renderCover(openedRenderer)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            failed = true
        } finally {
            try {
                renderer?.close()
            } catch (e: Exception) {
                // already closed or never opened
            }
            try {
                descriptor?.close()
            } catch (e: Exception) {
                // ignore
            }
        }

        val chapters = ArrayList<Chapter>(pageCount.coerceAtLeast(1))
        val toc = ArrayList<TocItem>()
        if (failed || pageCount == 0) {
            chapters.add(
                Chapter(
                    index = 0,
                    title = "Страница 1",
                    paragraphs = listOf("Не удалось открыть PDF документ")
                )
            )
        } else {
            for (pageIndex in 0 until pageCount) {
                val pageTitle = "Страница ${pageIndex + 1}"
                chapters.add(Chapter(index = pageIndex, title = pageTitle, paragraphs = emptyList(), pdfPageNumber = pageIndex))
                if (pageIndex % 5 == 0 || pageIndex == pageCount - 1) {
                    toc.add(TocItem(id = "pdf_page_$pageIndex", title = pageTitle, chapterIndex = pageIndex))
                }
            }
        }

        return ParsedBook(
            title = title,
            author = "PDF Документ",
            description = "Файл формата PDF (${chapters.size} стр.)",
            coverBytes = coverBytes,
            chapters = chapters,
            tableOfContents = toc,
            format = BookFormat.PDF
        )
    }

    private fun renderCover(renderer: PdfRenderer): ByteArray {
        val page = renderer.openPage(0)
        var bitmap: Bitmap? = null
        try {
            val pageWidth = page.width.coerceAtLeast(1)
            val pageHeight = page.height.coerceAtLeast(1)
            val scale = minOf(1.5f, MAX_COVER_SIDE.toFloat() / maxOf(pageWidth, pageHeight))
            val width = (pageWidth * scale).toInt().coerceIn(1, MAX_COVER_SIDE)
            val height = (pageHeight * scale).toInt().coerceIn(1, MAX_COVER_SIDE)
            val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = created
            created.eraseColor(Color.WHITE)
            page.render(created, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val stream = ByteArrayOutputStream()
            created.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            return stream.toByteArray()
        } finally {
            page.close()
            bitmap?.recycle()
        }
    }

    override fun parse(inputStream: InputStream, fileName: String): ParsedBook {
        val tempFile = File.createTempFile("temp_pdf_", ".pdf")
        try {
            inputStream.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }
            val parsed = parse(tempFile)
            return parsed.copy(title = fileName.substringBeforeLast("."))
        } finally {
            tempFile.delete()
        }
    }

    private companion object {
        const val MAX_COVER_SIDE = 1200
    }
}
