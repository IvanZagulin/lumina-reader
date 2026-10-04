package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.TocItem
import com.lumina.reader.ui.reader.navigation.currentTocIndex

/**
 * Chapter names as the BOOK numbers them.
 *
 * The reader's own chapters are the book's files, and an EPUB usually starts with a
 * few that are no chapters (cover, title page, copyright). Counting those made the
 * footer say «Гл. 17» where the table of contents, the book's own numbering, says 15.
 * Everything the reader shows about the current chapter now comes from the table of
 * contents entry the reader is in, so the footer, the scrubber and the contents agree.
 */

private val CHAPTER_WORDS = listOf("глава", "гл.", "гл", "chapter", "ch.", "часть", "part", "раздел")

/**
 * The number a table of contents entry carries: «15», «Глава 15. Название», «XV»,
 * «Chapter 3»; null for «Пролог», «Эпилог», «Об авторе». Roman numerals count only
 * with a chapter word or on their own (so a title like «MIX» is not read as 1009).
 */
fun bookChapterNumber(title: String): String? {
    var rest = title.trim()
    val lower = rest.lowercase()
    val word = CHAPTER_WORDS.firstOrNull { lower.startsWith(it) }
    if (word != null) rest = rest.substring(word.length).trimStart()
    val token = rest.takeWhile { it.isLetterOrDigit() }
    if (token.isEmpty()) return null
    val after = rest.substring(token.length)
    return when {
        token.length <= 3 && token.all { it.isDigit() } -> token
        // I, V, X, L, C only: D and M would turn words like «MIX» into chapters.
        token.length <= 6 && token.all { it in "IVXLC" } &&
            (word != null || after.isEmpty() || after[0] == '.' || after[0] == ')') -> token
        else -> null
    }
}

/** The footer's short label for the entry the reader is in: «Гл. 15», or the entry's name when it has no number. */
fun chapterNumberLabel(items: List<TocItem>, tocIndex: Int, chapterIndex: Int): String {
    val entry = items.getOrNull(tocIndex) ?: return if (items.isEmpty()) "Гл. ${chapterIndex + 1}" else "Начало"
    bookChapterNumber(entry.title)?.let { return "Гл. $it" }
    val name = entry.title.trim()
    return when {
        name.isEmpty() -> "Гл. ${tocIndex + 1}"
        name.length <= 12 -> name
        else -> {
            // Cut at a word end when there is one, not through the middle of a word.
            var cut = name.take(12)
            if (name[12] != ' ' && ' ' in cut) cut = cut.substringBeforeLast(' ')
            cut.trimEnd() + "…"
        }
    }
}

/** The chapter number for the scrubber's spoken description. */
fun chapterNumberForSpeech(items: List<TocItem>, tocIndex: Int, chapterIndex: Int): Int =
    items.getOrNull(tocIndex)?.let { bookChapterNumber(it.title)?.toIntOrNull() } ?: (tocIndex.takeIf { it >= 0 }?.plus(1) ?: (chapterIndex + 1))

/**
 * What the scrubber shows while it is dragged over [chapterIndex]: the contents entry
 * that starts there, named as the book names it («Глава 15» when the entry is only «15»).
 */
fun scrubberChapterTitle(items: List<TocItem>, chapterIndex: Int, fallbackTitle: String): String {
    val entry = items.getOrNull(currentTocIndex(items, chapterIndex, 0))
        ?: return "Глава ${chapterIndex + 1} · $fallbackTitle"
    val name = entry.title.trim()
    val number = bookChapterNumber(name)
    return if (number != null && name == number) "Глава $number" else name.ifEmpty { "Глава ${chapterIndex + 1}" }
}
