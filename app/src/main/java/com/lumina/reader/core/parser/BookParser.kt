package com.lumina.reader.core.parser

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.parser.epub.EpubParser
import com.lumina.reader.core.parser.fb2.Fb2Parser
import com.lumina.reader.core.parser.pdf.PdfParser
import com.lumina.reader.core.parser.txt.TxtParser
import java.io.File
import java.io.InputStream

interface BookParser {
    fun parse(file: File): ParsedBook
    fun parse(inputStream: InputStream, fileName: String): ParsedBook
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
