package com.lumina.reader.core.opds

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.platform.AppInfo
import com.lumina.reader.platform.PlatformKind

/** Classification of OPDS acquisition links into formats the reader can open. */
object OpdsFormats {

    /** Android's download order: FB2 first (smallest, best structure), then EPUB. */
    private val ANDROID_PREFERENCE = listOf(BookFormat.FB2_ZIP, BookFormat.FB2, BookFormat.EPUB, BookFormat.PDF, BookFormat.TXT)

    /** The iPhone's: EPUB reads best there (its text, images and notes come through whole). */
    private val IOS_PREFERENCE = listOf(BookFormat.EPUB, BookFormat.FB2_ZIP, BookFormat.FB2, BookFormat.PDF, BookFormat.TXT)

    private val PREFERENCE: List<BookFormat>
        get() = if (AppInfo.platform == PlatformKind.IOS) IOS_PREFERENCE else ANDROID_PREFERENCE

    private val THUMBNAIL_RELS = setOf(
        "http://opds-spec.org/image/thumbnail",
        "http://opds-spec.org/thumbnail",
        "x-stanza-cover-image-thumbnail"
    )
    private val COVER_RELS = setOf(
        "http://opds-spec.org/image",
        "http://opds-spec.org/cover",
        "x-stanza-cover-image"
    )
    private val BOOK_MIME_HINTS = listOf("epub", "fb2", "fictionbook", "pdf", "mobipocket", "djvu", "x-mobi")

    fun label(format: BookFormat): String = when (format) {
        BookFormat.EPUB -> "EPUB"
        BookFormat.FB2 -> "FB2"
        BookFormat.FB2_ZIP -> "FB2"
        BookFormat.PDF -> "PDF"
        BookFormat.TXT -> "TXT"
    }

    fun preferred(acquisitions: List<OpdsAcquisition>): OpdsAcquisition? = ranked(acquisitions).firstOrNull()

    /** All offered formats, best first: what to try next when a download turns out not to be a book. */
    fun ranked(acquisitions: List<OpdsAcquisition>): List<OpdsAcquisition> {
        val order = PREFERENCE
        return acquisitions.sortedBy { order.indexOf(it.format).let { index -> if (index < 0) Int.MAX_VALUE else index } }
    }

    fun isThumbnailRel(rel: String?): Boolean = rel != null && rel.lowercase() in THUMBNAIL_RELS
    fun isCoverRel(rel: String?): Boolean = rel != null && rel.lowercase() in COVER_RELS

    /**
     * True for links that download the publication itself. Paid, borrowing,
     * subscription and sample links are not offered.
     */
    fun isAcquisitionLink(rel: String?, type: String?): Boolean {
        val lowerRel = rel?.lowercase()?.trim()
        if (lowerRel == null || lowerRel == "alternate" || lowerRel.isEmpty()) {
            // Some small catalogues omit rel on direct book links.
            val lowerType = type?.lowercase().orEmpty()
            return BOOK_MIME_HINTS.any { lowerType.contains(it) }
        }
        if (!lowerRel.contains("acquisition")) return false
        return listOf("buy", "subscribe", "borrow", "sample", "preview").none { lowerRel.contains(it) }
    }

    /**
     * Format of an acquisition link, or null when the reader cannot open it
     * (MOBI, DjVu, RTF, zipped TXT...).
     */
    fun formatOf(type: String?, href: String): BookFormat? {
        val lowerType = type?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val path = href.lowercase().substringBefore('#').substringBefore('?').trimEnd('/')
        return when {
            lowerType.contains("epub") || path.endsWith(".epub") || path.endsWith("/epub") -> BookFormat.EPUB
            (lowerType.contains("fb2") || lowerType.contains("fictionbook")) && lowerType.contains("zip") ->
                BookFormat.FB2_ZIP
            path.endsWith(".fb2.zip") || path.endsWith(".fbz") || path.endsWith("/fb2") -> BookFormat.FB2_ZIP
            lowerType.contains("fb2") || lowerType.contains("fictionbook") || path.endsWith(".fb2") -> BookFormat.FB2
            lowerType == "application/pdf" || path.endsWith(".pdf") || path.endsWith("/pdf") -> BookFormat.PDF
            lowerType == "text/plain" || path.endsWith(".txt") -> BookFormat.TXT
            else -> null
        }
    }

    /** Human label for an unsupported offered format ("MOBI", "DJVU"...). */
    fun unsupportedLabel(type: String?, href: String): String {
        val lowerType = type?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        val path = href.lowercase().substringBefore('?').trimEnd('/')
        return when {
            lowerType.contains("mobi") || path.endsWith("mobi") -> "MOBI"
            lowerType.contains("djvu") || path.endsWith("djvu") -> "DJVU"
            lowerType.contains("rtf") || path.endsWith("rtf") -> "RTF"
            lowerType.contains("html") || path.endsWith("html") -> "HTML"
            lowerType.contains("msword") || path.endsWith("doc") || path.endsWith("docx") -> "DOC"
            lowerType.contains("txt") || path.endsWith("txt") -> "TXT.ZIP"
            else -> path.substringAfterLast('/').substringAfterLast('.').uppercase().take(8).ifBlank { "?" }
        }
    }
}
