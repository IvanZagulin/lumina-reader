package com.lumina.reader.core.library

import com.lumina.reader.core.model.Book

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

/**
 * Normalises a title for comparison: lower case, "ё" as "е", punctuation and
 * quotes removed, whitespace collapsed. "«Дюна» (Книга 1)" -> "дюна книга 1".
 */
fun normalizeTitleForMatch(value: String): String =
    value.lowercase()
        .replace('ё', 'е')
        .replace(NON_WORD, " ")
        .trim()

/**
 * Finds library books for the titles of a series, in the requested order.
 *
 * For every wanted title: a book that was just downloaded for exactly that
 * title ([downloadedIds], keyed by [normalizeTitleForMatch]) wins; otherwise an
 * exact normalised title match; only then a book whose title contains the
 * wanted one (the shortest such title, so "Дюна" prefers "Дюна" over
 * "Дети Дюны"). Each book is used once; unmatched titles are skipped.
 */
fun matchSeriesBooks(
    wantedTitles: List<String>,
    library: List<Book>,
    downloadedIds: Map<String, Long> = emptyMap()
): List<Book> {
    val byId = library.associateBy(Book::id)
    val normalizedLibrary = library.map { it to normalizeTitleForMatch(it.title) }
    val used = mutableSetOf<Long>()
    val result = mutableListOf<Book>()

    for (wanted in wantedTitles) {
        val key = normalizeTitleForMatch(wanted)
        if (key.isEmpty()) continue

        val downloaded = downloadedIds[key]?.let(byId::get)?.takeIf { it.id !in used }
        val exact = downloaded ?: normalizedLibrary
            .firstOrNull { (book, title) -> book.id !in used && title == key }
            ?.first
        val match = exact ?: normalizedLibrary
            .filter { (book, title) -> book.id !in used && title.contains(key) }
            .minByOrNull { (_, title) -> title.length }
            ?.first

        if (match != null) {
            used += match.id
            result += match
        }
    }
    return result
}
