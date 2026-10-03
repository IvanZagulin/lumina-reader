package com.lumina.reader.ui.reader.navigation

import com.lumina.reader.core.model.ReadingHighlight
import java.util.Calendar
import java.util.TimeZone

private val MonthsShort = arrayOf(
    "янв.", "февр.", "марта", "апр.", "мая", "июня",
    "июля", "авг.", "сент.", "окт.", "нояб.", "дек."
)

/** «сегодня», «вчера», «12 сент.», «12 сент. 2024» for a past moment. */
internal fun relativeDateLabel(
    timestamp: Long,
    now: Long,
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val then = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
    val today = Calendar.getInstance(timeZone).apply { timeInMillis = now }
    val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    if (sameYear && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "сегодня"
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (then.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        then.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) {
        return "вчера"
    }
    val day = then.get(Calendar.DAY_OF_MONTH)
    val month = MonthsShort[then.get(Calendar.MONTH)]
    return if (sameYear) "$day $month" else "$day $month ${then.get(Calendar.YEAR)}"
}

/** «Закладки 4» style tab titles: the count only when there is something. */
internal fun tabTitle(title: String, count: Int): String = if (count > 0) "$title $count" else title

/**
 * Quotes and notes as Markdown for «Экспорт в Markdown»: a heading with the
 * book, then each quote as a block quote with its chapter and note.
 */
internal fun highlightsMarkdown(
    bookTitle: String,
    author: String,
    highlights: List<ReadingHighlight>,
    chapterTitle: (Int) -> String
): String = buildString {
    append("# ").append(bookTitle.ifBlank { "Цитаты" }).append('\n')
    if (author.isNotBlank()) append("_").append(author).append("_\n")
    var lastChapter = -1
    highlights
        .sortedWith(compareBy({ it.chapterIndex }, { it.paragraphIndex }, { it.startOffset }))
        .forEach { highlight ->
            if (highlight.chapterIndex != lastChapter) {
                lastChapter = highlight.chapterIndex
                append('\n').append("## ").append(chapterTitle(highlight.chapterIndex)).append('\n')
            }
            append('\n')
            highlight.selectedText.trim().lines().forEach { line -> append("> ").append(line.trim()).append('\n') }
            val note = highlight.note?.trim().orEmpty()
            if (note.isNotEmpty()) append('\n').append(note).append('\n')
        }
}

/** ««цитата» — Название, Автор» for sharing a quote. */
internal fun quoteShareText(quote: String, bookTitle: String, author: String): String {
    val source = listOf(bookTitle, author).filter { it.isNotBlank() }.joinToString(", ")
    val body = "«${quote.trim()}»"
    return if (source.isEmpty()) body else "$body — $source"
}
