package com.lumina.reader.ui.reader.navigation

import com.lumina.reader.core.model.ReadingHighlight
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

private val MonthsShort = arrayOf(
    "янв.", "февр.", "марта", "апр.", "мая", "июня",
    "июля", "авг.", "сент.", "окт.", "нояб.", "дек."
)

/** «сегодня», «вчера», «12 сент.», «12 сент. 2024» for a past moment. */
@OptIn(ExperimentalTime::class)
fun relativeDateLabel(
    timestamp: Long,
    now: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val then = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(timeZone).date
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(timeZone).date
    if (then == today) return "сегодня"
    if (then == today.minus(1, DateTimeUnit.DAY)) return "вчера"
    val month = MonthsShort[then.month.ordinal]
    return if (then.year == today.year) "${then.day} $month" else "${then.day} $month ${then.year}"
}

/** «Закладки 4» style tab titles: the count only when there is something. */
fun tabTitle(title: String, count: Int): String = if (count > 0) "$title $count" else title

/**
 * Quotes and notes as Markdown for «Экспорт в Markdown»: a heading with the
 * book, then each quote as a block quote with its chapter and note.
 */
fun highlightsMarkdown(
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
fun quoteShareText(quote: String, bookTitle: String, author: String): String {
    val source = listOf(bookTitle, author).filter { it.isNotBlank() }.joinToString(", ")
    val body = "«${quote.trim()}»"
    return if (source.isEmpty()) body else "$body — $source"
}
