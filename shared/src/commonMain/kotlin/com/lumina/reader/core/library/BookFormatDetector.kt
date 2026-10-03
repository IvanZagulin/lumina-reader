package com.lumina.reader.core.library

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.text.charset.TextCharset

/** Outcome of recognising an incoming file. */
sealed interface FormatDetection {
    data class Supported(val format: BookFormat) : FormatDetection
    data class Unsupported(val message: String) : FormatDetection
}

/**
 * Recognises the real format of an incoming book from its leading bytes, the
 * entries of a zip archive, the file name and the MIME type. Content wins over
 * names: a server may call an HTML error page "book.fb2", and a video renamed
 * to ".txt" must not be parsed as text.
 */
object BookFormatDetector {

    private const val UNSUPPORTED_ARCHIVE = "Архив не содержит книгу FB2 или EPUB"
    private const val HTML_INSTEAD_OF_BOOK = "Получена веб-страница вместо книги"

    fun detect(
        header: ByteArray,
        fileName: String? = null,
        mimeType: String? = null,
        zipEntryNames: List<String>? = null,
        formatHint: BookFormat? = null
    ): FormatDetection {
        val nameFormat = formatFromFileName(fileName)
        val mimeFormat = formatFromMimeType(mimeType)
        val claimed = nameFormat ?: mimeFormat ?: formatHint
        val damaged = "Файл не похож на книгу ${claimed?.let(::formatLabel).orEmpty()}: возможно, он повреждён"

        if (isPdf(header)) return FormatDetection.Supported(BookFormat.PDF)

        if (isZip(header)) {
            val names = zipEntryNames
                ?: return when (claimed) {
                    BookFormat.EPUB -> FormatDetection.Supported(BookFormat.EPUB)
                    BookFormat.FB2_ZIP -> FormatDetection.Supported(BookFormat.FB2_ZIP)
                    else -> FormatDetection.Unsupported(UNSUPPORTED_ARCHIVE)
                }
            return classifyZip(names, claimed)
        }

        binaryKind(header)?.let { kind ->
            return FormatDetection.Unsupported("Формат не поддерживается: $kind")
        }

        val text = decodeHeaderText(header)
        if (text != null) {
            val trimmed = text.trimStart()
            val lower = trimmed.take(4096).lowercase()
            if (lower.contains("<fictionbook")) return FormatDetection.Supported(BookFormat.FB2)
            if (lower.startsWith("<!doctype html") || lower.startsWith("<html") ||
                (lower.startsWith("<") && lower.contains("<html") && !lower.contains("<fictionbook"))
            ) {
                return FormatDetection.Unsupported(HTML_INSTEAD_OF_BOOK)
            }
            if (lower.startsWith("<?xml") || lower.startsWith("<")) {
                return when (claimed) {
                    BookFormat.FB2, BookFormat.FB2_ZIP -> FormatDetection.Supported(BookFormat.FB2)
                    BookFormat.TXT -> FormatDetection.Supported(BookFormat.TXT)
                    else -> FormatDetection.Unsupported("Неизвестный XML-файл: это не книга FB2")
                }
            }
            return if (claimed == null || claimed == BookFormat.TXT) {
                FormatDetection.Supported(BookFormat.TXT)
            } else {
                FormatDetection.Unsupported(damaged)
            }
        }

        if (header.isEmpty()) return FormatDetection.Unsupported("Файл пустой")

        return if (claimed == null) {
            FormatDetection.Unsupported("Неизвестный формат файла")
        } else {
            FormatDetection.Unsupported(damaged)
        }
    }

    /** Decides what a zip archive holds from the names of its entries. */
    fun classifyZip(entryNames: List<String>, claimed: BookFormat? = null): FormatDetection {
        val lowerNames = entryNames.map { it.replace('\\', '/').lowercase() }
        if (lowerNames.any { it == "meta-inf/container.xml" }) {
            return FormatDetection.Supported(BookFormat.EPUB)
        }
        if (fb2EntryName(entryNames) != null) {
            return FormatDetection.Supported(BookFormat.FB2_ZIP)
        }
        if (claimed == BookFormat.EPUB && lowerNames.any { it.endsWith(".opf") }) {
            return FormatDetection.Supported(BookFormat.EPUB)
        }
        if ((claimed == BookFormat.FB2_ZIP || claimed == BookFormat.FB2) &&
            lowerNames.any { it.endsWith(".xml") && !it.endsWith("/") }
        ) {
            return FormatDetection.Supported(BookFormat.FB2_ZIP)
        }
        return FormatDetection.Unsupported(UNSUPPORTED_ARCHIVE)
    }

    /** The entry that holds the FB2 text of an `.fb2.zip` archive. */
    fun fb2EntryName(entryNames: List<String>): String? {
        val files = entryNames.filterNot { it.endsWith("/") }
        return files.firstOrNull { it.lowercase().endsWith(".fb2") }
            ?: files.firstOrNull { it.lowercase().endsWith(".fb2.xml") }
            ?: files.singleOrNull { it.lowercase().endsWith(".xml") }
    }

    fun isZip(header: ByteArray): Boolean =
        header.startsWith(0x50, 0x4B, 0x03, 0x04) ||
            // An empty archive starts with the end-of-central-directory record.
            header.startsWith(0x50, 0x4B, 0x05, 0x06)

    fun isPdf(header: ByteArray): Boolean {
        // "%PDF-" may be preceded by a few junk bytes in real-world files.
        val limit = minOf(header.size, 1024)
        val window = TextCharset.ISO_8859_1.decode(header, 0, limit)
        return window.indexOf("%PDF-") in 0..1018
    }

    /** A short Russian description of a recognised but unsupported binary type. */
    fun binaryKind(header: ByteArray): String? = when {
        header.startsWith(0x41, 0x54, 0x26, 0x54, 0x46, 0x4F, 0x52, 0x4D) -> "DjVu"
        header.hasAscii(60, "BOOKMOBI") -> "MOBI/AZW"
        header.startsWith(0x52, 0x61, 0x72, 0x21) -> "архив RAR"
        header.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "архив 7z"
        header.startsWith(0x1F, 0x8B) -> "архив GZIP"
        header.startsWith(0xD0, 0xCF, 0x11, 0xE0) -> "документ Microsoft Office"
        header.startsWith(0x7B, 0x5C, 0x72, 0x74, 0x66) -> "документ RTF"
        header.startsWith(0x89, 0x50, 0x4E, 0x47) -> "изображение"
        header.startsWith(0xFF, 0xD8, 0xFF) -> "изображение"
        header.startsWith(0x47, 0x49, 0x46, 0x38) -> "изображение"
        header.hasAscii(0, "RIFF") && header.hasAscii(8, "WEBP") -> "изображение"
        header.hasAscii(4, "ftyp") -> "видео или аудио"
        header.startsWith(0x1A, 0x45, 0xDF, 0xA3) -> "видео"
        header.hasAscii(0, "RIFF") -> "видео или аудио"
        header.hasAscii(0, "ID3") || header.startsWith(0xFF, 0xFB) -> "аудио"
        header.hasAscii(0, "OggS") || header.hasAscii(0, "fLaC") -> "аудио"
        header.startsWith(0x4D, 0x5A) -> "программа"
        header.startsWith(0x7F, 0x45, 0x4C, 0x46) -> "программа"
        header.hasAscii(0, "dex\n") -> "программа"
        else -> null
    }

    /**
     * Returns the header as text when it looks like a text document (any common
     * Unicode or single-byte Cyrillic encoding), otherwise null.
     */
    fun decodeHeaderText(header: ByteArray): String? {
        if (header.isEmpty()) return null
        if (header.startsWith(0xFF, 0xFE)) return TextCharset.UTF_16LE.decode(header)
        if (header.startsWith(0xFE, 0xFF)) return TextCharset.UTF_16BE.decode(header)
        var controls = 0
        for (byte in header) {
            val value = byte.toInt() and 0xFF
            if (value == 0) return null
            if (value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D && value != 0x0C) {
                controls++
            }
        }
        if (controls * 100 > header.size) return null
        val body = if (header.startsWith(0xEF, 0xBB, 0xBF)) header.copyOfRange(3, header.size) else header
        // Single-byte encodings (cp1251) decode to readable text as ISO-8859-1 too;
        // only markup detection uses the result, so the exact charset is irrelevant.
        return TextCharset.ISO_8859_1.decode(body)
    }

    /** Strict mapping of a file name to a format; unknown extensions give null. */
    fun formatFromFileName(fileName: String?): BookFormat? {
        val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase()?.trim()
            ?: return null
        return when {
            name.endsWith(".fb2.zip") || name.endsWith(".fbz") || name.endsWith(".fb2_zip") -> BookFormat.FB2_ZIP
            name.endsWith(".epub") -> BookFormat.EPUB
            name.endsWith(".fb2") -> BookFormat.FB2
            name.endsWith(".pdf") -> BookFormat.PDF
            name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".text") -> BookFormat.TXT
            else -> null
        }
    }

    /** Strict mapping of a MIME type to a format; generic types give null. */
    fun formatFromMimeType(mimeType: String?): BookFormat? {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            type == "application/epub+zip" -> BookFormat.EPUB
            type == "application/pdf" -> BookFormat.PDF
            (type.contains("fb2") || type.contains("fictionbook")) && type.contains("zip") -> BookFormat.FB2_ZIP
            type.contains("fb2") || type.contains("fictionbook") -> BookFormat.FB2
            type == "text/plain" || type == "text/markdown" -> BookFormat.TXT
            else -> null
        }
    }

    fun formatLabel(format: BookFormat): String = when (format) {
        BookFormat.EPUB -> "EPUB"
        BookFormat.FB2 -> "FB2"
        BookFormat.FB2_ZIP -> "FB2.ZIP"
        BookFormat.PDF -> "PDF"
        BookFormat.TXT -> "TXT"
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean {
        if (size < bytes.size) return false
        for (i in bytes.indices) {
            if ((this[i].toInt() and 0xFF) != bytes[i]) return false
        }
        return true
    }

    private fun ByteArray.hasAscii(offset: Int, value: String): Boolean {
        if (offset < 0 || size < offset + value.length) return false
        for (i in value.indices) {
            if ((this[offset + i].toInt() and 0xFF) != value[i].code) return false
        }
        return true
    }
}
