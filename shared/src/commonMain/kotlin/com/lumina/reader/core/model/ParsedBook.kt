package com.lumina.reader.core.model

/**
 * One readable unit of a book.
 *
 * [paragraphs] hold the text; each entry may carry [ParagraphMarkup] markers,
 * be an image placeholder `[IMG:<id>]` (bytes in [ParsedBook.images]) or be an
 * empty string, which stands for a small vertical gap (stanza break, blank
 * line). PDF pages have no paragraphs and use [pdfPageNumber] (0-based).
 */
data class Chapter(
    val index: Int,
    val title: String,
    val href: String = "",
    val paragraphs: List<String> = emptyList(),
    val pdfPageNumber: Int = 0
)

/**
 * Table-of-contents entry. [chapterIndex] and [paragraphIndex] point at the
 * position the entry opens; [level] is the nesting depth (0 = top level).
 */
data class TocItem(
    val id: String,
    val title: String,
    val chapterIndex: Int,
    val level: Int = 0,
    val paragraphIndex: Int = 0
)

data class ParsedBook(
    val title: String,
    val author: String,
    val description: String = "",
    val seriesName: String = "",
    val seriesOrder: Int = 0,
    val coverBytes: ByteArray? = null,
    val chapters: List<Chapter>,
    val tableOfContents: List<TocItem> = emptyList(),
    val images: Map<String, ByteArray> = emptyMap(),
    val format: BookFormat,
    /**
     * Footnote id -> note text. Paragraphs reference notes with
     * [ParagraphMarkup.noteRef]; the text may contain inline markers and uses
     * "\n" between the note's paragraphs.
     */
    val footnotes: Map<String, String> = emptyMap()
) {
    // Identity is the book's text: cover, images and footnotes are derived
    // data (and byte arrays have no structural equality), so they are left out
    // of both equals and hashCode.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        // A data class is final, so this is the former javaClass comparison.
        if (other !is ParsedBook) return false
        return title == other.title && author == other.author && chapters == other.chapters
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + chapters.hashCode()
        return result
    }
}
