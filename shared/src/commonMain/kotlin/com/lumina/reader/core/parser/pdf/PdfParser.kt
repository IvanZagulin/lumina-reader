package com.lumina.reader.core.parser.pdf

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import com.lumina.reader.core.parser.common.withTempCopy
import okio.Path
import okio.Source

/**
 * PDF "parser": one chapter per page titled "Страница N" with no paragraphs
 * (pages are rendered as images by the reader via [Chapter.pdfPageNumber]),
 * plus a cover rendered from the first page. The platform's PDF engine
 * ([inspectPdf]: PdfRenderer on Android, PDFKit on iOS) counts the pages and
 * renders the cover.
 */
class PdfParser internal constructor(
    private val inspect: (Path) -> PdfSummary?
) : BookParser {

    constructor() : this(::inspectPdf)

    override fun parse(path: Path, fileName: String): ParsedBook {
        val title = fileName.substringBeforeLast(".")
        val summary = inspect(path)
        val pageCount = summary?.pageCount ?: 0

        val chapters = ArrayList<Chapter>(pageCount.coerceAtLeast(1))
        val toc = ArrayList<TocItem>()
        if (summary == null || pageCount == 0) {
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
            coverBytes = summary?.coverBytes,
            chapters = chapters,
            tableOfContents = toc,
            format = BookFormat.PDF
        )
    }

    override fun parse(source: Source, fileName: String): ParsedBook =
        withTempCopy(source, "temp_pdf_", ".pdf") { temp -> parse(temp, fileName) }

    internal companion object {
        /** Longest side of the rendered cover, in pixels. */
        const val MAX_COVER_SIDE = 1200
    }
}

/** What the platform's PDF engine reports about a document. */
internal class PdfSummary(
    val pageCount: Int,
    /** The first page as a JPEG (at most [PdfParser.MAX_COVER_SIDE] pixels a side), or null. */
    val coverBytes: ByteArray?
)

/**
 * Opens the PDF at [path]: its page count and, when it has pages, a cover
 * rendered from the first page (null when only that fails). Null when the
 * document cannot be opened at all.
 */
internal expect fun inspectPdf(path: Path): PdfSummary?
