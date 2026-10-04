package com.lumina.reader.core.parser

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.epub.EpubParser
import com.lumina.reader.core.parser.fb2.Fb2Parser
import com.lumina.reader.core.parser.pdf.PdfParser
import com.lumina.reader.core.parser.txt.TxtParser
import okio.Path
import okio.Source

/**
 * Turns a book file into a [ParsedBook]. Android code keeps calling
 * `parse(file: File)` and `parse(inputStream, fileName)` (extensions in
 * androidMain that forward here).
 */
interface BookParser {
    /**
     * Parses the book at [path]. [fileName] stands in for a missing title
     * (without its extension) and tells zipped formats apart; it defaults to
     * the file's own name.
     */
    fun parse(path: Path, fileName: String = path.name): ParsedBook

    /** Parses a book read from [source], which is closed afterwards; [fileName] as for [parse]. */
    fun parse(source: Source, fileName: String): ParsedBook
}

object BookParserFactory {
    fun getParser(format: BookFormat): BookParser {
        return when (format) {
            BookFormat.EPUB -> EpubParser()
            BookFormat.FB2, BookFormat.FB2_ZIP -> Fb2Parser()
            BookFormat.PDF -> PdfParser()
            BookFormat.TXT -> TxtParser()
        }
    }
}
