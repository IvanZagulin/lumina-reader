package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.Chapter

/** Shortest query the in-book search runs for. */
internal const val MIN_SEARCH_QUERY_LENGTH = 2

/** Search stops after this many matches. */
internal const val MAX_SEARCH_RESULTS = 500

/** Characters of context shown on each side of a match. */
internal const val SEARCH_SNIPPET_CONTEXT = 40

/**
 * One match of the in-book search. [matchStart]/[matchEnd] are offsets in
 * the paragraph's plain text; [snippetMatchStart]/[snippetMatchEnd] locate
 * the match inside [snippet] for highlighting.
 */
data class SearchResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val matchStart: Int,
    val matchEnd: Int,
    val snippet: String,
    val snippetMatchStart: Int,
    val snippetMatchEnd: Int
)

/** State of the in-book search shown by the search dialog. */
data class SearchState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    /** True when the search stopped at [MAX_SEARCH_RESULTS]. */
    val truncated: Boolean = false
)

/** A search match to emphasise on the page after jumping to it. */
data class SearchMatch(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int
)

internal data class SearchOutcome(
    val results: List<SearchResult>,
    val truncated: Boolean
)

/**
 * Folds case and treats "ё" as "е". Every character maps to exactly one
 * character, so offsets found in the normalized text are valid in the
 * original text.
 */
internal fun normalizeForSearch(text: String): String {
    val chars = CharArray(text.length)
    for (index in text.indices) {
        val lower = Character.toLowerCase(text[index])
        chars[index] = if (lower == 'ё') 'е' else lower
    }
    return String(chars)
}

/** Snippet of [text] around [matchStart, matchEnd) with the match located inside it. */
internal data class Snippet(
    val text: String,
    val matchStart: Int,
    val matchEnd: Int
)

internal fun buildSnippet(
    text: String,
    matchStart: Int,
    matchEnd: Int,
    context: Int = SEARCH_SNIPPET_CONTEXT
): Snippet {
    val start = matchStart.coerceIn(0, text.length)
    val end = matchEnd.coerceIn(start, text.length)
    var from = (start - context).coerceAtLeast(0)
    var to = (end + context).coerceAtMost(text.length)
    // Do not cut words in half at the edges when a word boundary is close.
    if (from > 0) {
        val space = text.indexOf(' ', from)
        if (space in from until start && space - from <= 12) from = space + 1
    }
    if (to < text.length) {
        val space = text.lastIndexOf(' ', to - 1)
        if (space >= end && to - space <= 12) to = space
    }
    val prefix = if (from > 0) "…" else ""
    val suffix = if (to < text.length) "…" else ""
    val body = text.substring(from, to).replace('\n', ' ').replace('\t', ' ')
    return Snippet(
        text = prefix + body + suffix,
        matchStart = prefix.length + (start - from),
        matchEnd = prefix.length + (end - from)
    )
}

/**
 * Case- and ё/е-insensitive search over the plain text of every chapter.
 * Illustrations and empty (PDF) chapters are skipped. [checkCancelled] is
 * called between paragraphs so a newer query can stop an older search.
 */
internal fun searchChapters(
    chapters: List<Chapter>,
    query: String,
    maxResults: Int = MAX_SEARCH_RESULTS,
    checkCancelled: () -> Unit = {}
): SearchOutcome {
    val needle = normalizeForSearch(query.trim())
    if (needle.length < MIN_SEARCH_QUERY_LENGTH) return SearchOutcome(emptyList(), truncated = false)
    val results = ArrayList<SearchResult>()
    // The chapter index is the position in the list, as everywhere in the
    // reader; Chapter.index is whatever the parser numbered.
    chapters.forEachIndexed { chapterIndex, chapter ->
        val title = displayChapterTitle(chapter.title, chapterIndex)
        chapter.paragraphs.forEachIndexed { paragraphIndex, raw ->
            checkCancelled()
            val text = paragraphPlainText(raw)
            if (text.length < needle.length) return@forEachIndexed
            val haystack = normalizeForSearch(text)
            var from = 0
            while (true) {
                val index = haystack.indexOf(needle, from)
                if (index < 0) break
                if (results.size >= maxResults) return SearchOutcome(results, truncated = true)
                val snippet = buildSnippet(text, index, index + needle.length)
                results += SearchResult(
                    chapterIndex = chapterIndex,
                    chapterTitle = title,
                    paragraphIndex = paragraphIndex,
                    matchStart = index,
                    matchEnd = index + needle.length,
                    snippet = snippet.text,
                    snippetMatchStart = snippet.matchStart,
                    snippetMatchEnd = snippet.matchEnd
                )
                from = index + needle.length
            }
        }
    }
    return SearchOutcome(results, truncated = false)
}

/** Chapter titles such as "Раздел 3" produced by some parsers read better as "Глава N". */
internal fun displayChapterTitle(title: String, chapterIndex: Int): String =
    if (title.isBlank() || title.matches(Regex("Раздел\\s+\\d+", RegexOption.IGNORE_CASE))) {
        "Глава ${chapterIndex + 1}"
    } else {
        title
    }

/** Plain text of a paragraph on screen and the part of it that is visible. */
internal data class VisibleParagraphText(
    val paragraphIndex: Int,
    val text: String,
    val visibleStart: Int = 0,
    val visibleEnd: Int = text.length
)

/** Where a selection lies inside one paragraph. */
internal data class ParagraphTextRange(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int
)

/**
 * Finds copied [selected] text among the paragraphs on screen. An exact match
 * that overlaps the visible part of a paragraph wins; then any exact match.
 * A selection that spans paragraphs (copied without separators) is mapped to
 * the part inside its first paragraph.
 */
internal fun locateSelection(
    selected: String,
    paragraphs: List<VisibleParagraphText>
): ParagraphTextRange? {
    val needle = selected.trim()
    if (needle.isEmpty()) return null

    var fallback: ParagraphTextRange? = null
    for (paragraph in paragraphs) {
        var from = 0
        while (true) {
            val index = paragraph.text.indexOf(needle, from)
            if (index < 0) break
            val range = ParagraphTextRange(paragraph.paragraphIndex, index, index + needle.length)
            if (index < paragraph.visibleEnd && index + needle.length > paragraph.visibleStart) {
                return range
            }
            if (fallback == null) fallback = range
            from = index + 1
        }
    }
    if (fallback != null) return fallback

    // The selection continues into the next paragraph: find the paragraph whose
    // tail is the start of the selection and whose successor continues it.
    paragraphs.forEachIndexed { position, paragraph ->
        val text = paragraph.text
        val minStart = (text.length - needle.length + 1).coerceAtLeast(0)
        for (start in minStart until text.length) {
            val tail = text.substring(start)
            if (tail.isBlank() || !needle.startsWith(tail)) continue
            val rest = needle.substring(tail.length).trimStart()
            val next = paragraphs.getOrNull(position + 1)?.text
            val continues = rest.isEmpty() || next == null ||
                next.startsWith(rest) || rest.startsWith(next)
            if (continues) return ParagraphTextRange(paragraph.paragraphIndex, start, text.length)
        }
    }
    return null
}
