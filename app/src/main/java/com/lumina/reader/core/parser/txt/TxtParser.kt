package com.lumina.reader.core.parser.txt

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import com.lumina.reader.core.parser.common.TextEncoding
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Plain-text parser.
 *
 * Encoding: BOM (UTF-8, UTF-16 LE/BE), else UTF-8 when the bytes are valid
 * UTF-8 (4-byte sequences such as emoji included), else windows-1251.
 *
 * Paragraph layout is detected ([TxtLayout]): blank-line separated blocks,
 * one paragraph per line, indented paragraph starts (lib.ru style) or
 * hard-wrapped lines that are joined back into paragraphs.
 *
 * Chapters start at strict headings ("Глава 1", "ГЛАВА ПЕРВАЯ", "Часть II",
 * "Chapter 3", "Пролог", markdown "#"); a text without headings is split into
 * parts of roughly 30 000 characters at paragraph boundaries, and so is a
 * chapter longer than [MAX_CHAPTER_CHARS] ("<title> (часть N)").
 */
class TxtParser : BookParser {

    override fun parse(file: File): ParsedBook =
        file.inputStream().use { parse(it, file.name) }

    override fun parse(inputStream: InputStream, fileName: String): ParsedBook {
        val bytes = inputStream.use { it.readBytes() }
        val text = decode(bytes)
        val blocks = TxtLayout.split(text)
        val (chapters, toc) = buildChapters(blocks)
        return ParsedBook(
            title = fileName.substringBeforeLast("."),
            author = "Неизвестный автор",
            description = "Текстовый документ",
            coverBytes = null,
            chapters = chapters,
            tableOfContents = toc,
            format = BookFormat.TXT
        )
    }

    private fun buildChapters(blocks: List<TxtBlock>): Pair<List<Chapter>, List<TocItem>> {
        val chapters = ArrayList<Chapter>()
        val toc = ArrayList<TocItem>()
        val hasHeadings = blocks.any { it is TxtBlock.Heading }

        if (!hasHeadings) {
            val paragraphs = blocks.filterIsInstance<TxtBlock.Paragraph>().map { it.raw }
            for ((index, part) in chunk(paragraphs).withIndex()) {
                val title = "Часть ${index + 1}"
                chapters.add(Chapter(index = index, title = title, paragraphs = part))
                toc.add(TocItem(id = "ch_$index", title = title, chapterIndex = index))
            }
        } else {
            var title = "Введение"
            var current = ArrayList<String>()
            val pending = ArrayList<Pair<String, Int>>()
            var sawPart = false

            fun commit() {
                while (current.isNotEmpty() && current.last().isEmpty()) current.removeAt(current.lastIndex)
                if (current.isEmpty()) return
                // A missed heading must not leave one enormous chapter: split it into parts.
                val parts: List<List<String>> =
                    if (current.sumOf { it.length } > MAX_CHAPTER_CHARS) chunk(current) else listOf(current)
                val index = chapters.size
                for ((partIndex, part) in parts.withIndex()) {
                    val partTitle = if (parts.size > 1) "$title (часть ${partIndex + 1})" else title
                    chapters.add(Chapter(index = chapters.size, title = partTitle, paragraphs = part))
                }
                if (pending.isEmpty()) {
                    toc.add(TocItem(id = "toc_${toc.size}", title = title, chapterIndex = index))
                }
                for ((pendingTitle, level) in pending) {
                    toc.add(TocItem(id = "toc_${toc.size}", title = pendingTitle, chapterIndex = index, level = level))
                }
                val partLevel = (pending.lastOrNull()?.second ?: 0) + 1
                for (partIndex in 1 until parts.size) {
                    val chapterIndex = index + partIndex
                    toc.add(
                        TocItem(
                            id = "toc_${toc.size}",
                            title = chapters[chapterIndex].title,
                            chapterIndex = chapterIndex,
                            level = partLevel
                        )
                    )
                }
                pending.clear()
                current = ArrayList()
            }

            for (block in blocks) {
                when (block) {
                    is TxtBlock.Heading -> {
                        commit()
                        val level = when {
                            block.markdownLevel >= 0 -> block.markdownLevel
                            block.isPart -> 0
                            sawPart -> 1
                            else -> 0
                        }
                        if (block.isPart) sawPart = true
                        title = block.title
                        pending.add(block.title to level)
                    }
                    is TxtBlock.Paragraph -> {
                        if (block.raw.isEmpty() && current.isEmpty()) continue
                        current.add(block.raw)
                    }
                }
            }
            commit()
            // Headings with no text after them (e.g. a trailing "Конец") still get a TOC entry.
            if (pending.isNotEmpty() && chapters.isNotEmpty()) {
                val last = chapters.lastIndex
                for ((pendingTitle, level) in pending) {
                    toc.add(TocItem(id = "toc_${toc.size}", title = pendingTitle, chapterIndex = last, level = level))
                }
            }
        }

        if (chapters.isEmpty()) {
            chapters.add(Chapter(index = 0, title = "Текст", paragraphs = listOf("Пустой файл")))
            toc.add(TocItem(id = "ch_0", title = "Текст", chapterIndex = 0))
        }
        return chapters to toc
    }

    companion object {
        private const val TARGET_PART_CHARS = 30_000
        private const val MIN_LAST_PART_CHARS = 8_000

        /** A chapter between two headings longer than this is split into ~30k parts. */
        internal const val MAX_CHAPTER_CHARS = 100_000

        /** Splits paragraphs into parts of ~30k characters; a short tail is merged into the previous part. */
        internal fun chunk(paragraphs: List<String>): List<List<String>> {
            val parts = ArrayList<MutableList<String>>()
            var current = ArrayList<String>()
            var size = 0
            for (paragraph in paragraphs) {
                if (paragraph.isEmpty() && current.isEmpty()) continue
                current.add(paragraph)
                size += paragraph.length
                if (size >= TARGET_PART_CHARS) {
                    parts.add(current)
                    current = ArrayList()
                    size = 0
                }
            }
            while (current.isNotEmpty() && current.last().isEmpty()) current.removeAt(current.lastIndex)
            if (current.isNotEmpty()) {
                if (parts.isNotEmpty() && size < MIN_LAST_PART_CHARS) parts.last().addAll(current) else parts.add(current)
            }
            return parts
        }

        internal fun decode(bytes: ByteArray): String {
            val bom = TextEncoding.detectBom(bytes)
            val text = if (bom != null) {
                String(bytes, bom.length, bytes.size - bom.length, bom.charset)
            } else {
                val charset: Charset =
                    if (TextEncoding.looksLikeUtf8(bytes)) Charsets.UTF_8 else TextEncoding.WINDOWS_1251
                String(bytes, charset)
            }
            return ParagraphMarkup.stripMarkers(text.replace("﻿", ""))
        }
    }
}

internal sealed class TxtBlock {
    /** [raw] may carry a block marker (scene breaks) or be "" (a gap). */
    class Paragraph(val raw: String) : TxtBlock()

    class Heading(val title: String, val isPart: Boolean, val markdownLevel: Int = -1) : TxtBlock()
}

/** Paragraph and heading detection for plain text. */
internal object TxtLayout {

    enum class Mode { BLANK_SEPARATED, LINE_PER_PARAGRAPH, INDENTED, HARD_WRAPPED }

    private val partKeywords = setOf("часть", "том", "книга", "part", "book", "volume")
    private val chapterKeywords = setOf("глава", "раздел", "эпизод", "chapter", "section", "act", "акт")
    private val standalone = setOf(
        "пролог", "эпилог", "предисловие", "послесловие", "введение", "заключение", "интерлюдия",
        "prologue", "epilogue", "preface", "foreword", "afterword", "introduction", "interlude"
    )
    private val arabic = Regex("^\\d{1,4}(?:-?[а-яa-z]{1,3})?$", RegexOption.IGNORE_CASE)
    private val roman = Regex("^[IVXLCDM]{1,8}$")
    private val wordNumber = Regex(
        "^(?:перв|втор|трет|четв[её]рт|пят|шест|седьм|восьм|девят|десят|одиннадцат|двенадцат|тринадцат|" +
            "четырнадцат|пятнадцат|шестнадцат|семнадцат|восемнадцат|девятнадцат|двадцат|тридцат|сороков|" +
            "последн)[а-яё]*$|^(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|" +
            "fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|first|second|third|fourth|" +
            "fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|last|final)$",
        RegexOption.IGNORE_CASE
    )
    private val markdown = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")
    private val sceneBreak = Regex("^(?:[*•·~]\\s*){3,}$|^\\*{3,}$|^-{3,}$|^={3,}$")
    private const val TITLE_SEPARATORS = ".:)—–-|"

    fun isHeading(line: String): Boolean = heading(line) != null

    /** Heading described by a single line, or null. */
    fun heading(line: String): TxtBlock.Heading? {
        val t = line.trim()
        if (t.isEmpty() || t.length > 80) return null
        markdown.matchEntire(t)?.let { match ->
            return TxtBlock.Heading(match.groupValues[2].trim(), isPart = false, markdownLevel = match.groupValues[1].length - 1)
        }
        val keyword = t.takeWhile { it.isLetter() }
        if (keyword.isEmpty()) return null
        val lower = keyword.lowercase()
        val rest = t.substring(keyword.length)
        if (lower in standalone) {
            val after = rest.trim()
            return if (after.isEmpty() || after[0] in TITLE_SEPARATORS) TxtBlock.Heading(t, isPart = false) else null
        }
        val isPart = lower in partKeywords
        if (!isPart && lower !in chapterKeywords) return null
        if (rest.isEmpty() || !(rest[0].isWhitespace() || rest[0] == '.')) return null
        val afterKeyword = rest.trimStart('.', ' ', '\t', ' ')
        val number = afterKeyword.takeWhile { it.isLetterOrDigit() || it == '-' }
        if (number.isEmpty()) return null
        val lowerNumber = number.lowercase()
        val isArabic = arabic.matches(lowerNumber)
        val isNumber = isArabic || roman.matches(number) || wordNumber.matches(lowerNumber)
        if (!isNumber) return null
        val after = afterKeyword.substring(number.length)
        val afterTrim = after.trim()
        val ok = when {
            afterTrim.isEmpty() -> true
            afterTrim[0] in TITLE_SEPARATORS -> true
            // "Глава 3 Возвращение", "Part I The Beginning"; but not "Часть первая была скучной"
            // or "Book I am reading", where a sentence simply starts with the keyword.
            after[0].isWhitespace() -> afterTrim.length <= 60 && afterTrim.last() !in ".,;!?…" &&
                (isArabic || !afterTrim[0].isLetter() || afterTrim[0].isUpperCase())
            else -> false
        }
        return if (ok) TxtBlock.Heading(t, isPart = isPart) else null
    }

    fun detectMode(lines: List<String>): Mode {
        val nonEmpty = lines.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return Mode.LINE_PER_PARAGRAPH
        val blank = lines.size - nonEmpty.size
        val lengths = nonEmpty.map { it.trimEnd().length }.sorted()
        val p90 = lengths[((lengths.size - 1) * 9) / 10]
        val longLines = lengths.count { it > 120 }
        // Hard-wrapped text has most lines close to the wrap width; one line per
        // paragraph gives lengths spread all over the place.
        val nearWidth = lengths.count { it * 4 >= p90 * 3 && it <= p90 + 10 }
        val hardWrapped = nonEmpty.size >= 8 && p90 in 40..110 && longLines * 50 < nonEmpty.size &&
            nearWidth * 5 >= nonEmpty.size * 2
        if (!hardWrapped) return Mode.LINE_PER_PARAGRAPH
        val indented = nonEmpty.count { isIndented(it) }
        return when {
            indented >= 3 && indented * 20 >= nonEmpty.size && indented * 10 < nonEmpty.size * 9 -> Mode.INDENTED
            blank * 12 >= nonEmpty.size -> Mode.BLANK_SEPARATED
            else -> Mode.HARD_WRAPPED
        }
    }

    private fun isIndented(line: String): Boolean =
        line.startsWith("  ") || line.startsWith("\t") || line.startsWith("　") || line.startsWith(" ")

    /** Splits [text] into headings and paragraphs according to the detected layout. */
    fun split(text: String): List<TxtBlock> {
        val lines = text.lines()
        val mode = detectMode(lines)
        val width = run {
            val lengths = lines.filter { it.isNotBlank() }.map { it.trimEnd().length }.sorted()
            if (lengths.isEmpty()) 0 else lengths[((lengths.size - 1) * 9) / 10]
        }
        val blocks = ArrayList<TxtBlock>()
        val paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks.add(TxtBlock.Paragraph(paragraph.toString()))
                paragraph.setLength(0)
            }
        }

        fun gap() {
            val last = blocks.lastOrNull()
            if (last is TxtBlock.Paragraph && last.raw.isNotEmpty()) blocks.add(TxtBlock.Paragraph(""))
        }

        var previousBlank = true
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flush()
                if (mode == Mode.LINE_PER_PARAGRAPH && !previousBlank) {
                    // Blank lines inside line-per-paragraph text separate scenes or stanzas.
                    val nextBlank = lines.getOrNull(i + 1)?.isBlank() == true
                    if (nextBlank) gap()
                }
                previousBlank = true
                continue
            }
            val heading = heading(trimmed)
            if (heading != null) {
                flush()
                blocks.add(heading)
                previousBlank = false
                continue
            }
            if (sceneBreak.matches(trimmed)) {
                flush()
                blocks.add(TxtBlock.Paragraph(ParagraphMarkup.sceneBreak()))
                previousBlank = false
                continue
            }
            val startsParagraph = when (mode) {
                Mode.LINE_PER_PARAGRAPH -> true
                Mode.BLANK_SEPARATED -> previousBlank
                Mode.INDENTED -> previousBlank || isIndented(line)
                Mode.HARD_WRAPPED -> previousBlank || startsDialogue(trimmed)
            }
            if (startsParagraph) flush()
            if (paragraph.isNotEmpty()) {
                if (paragraph.endsWith("-") && paragraph.length > 1 && paragraph[paragraph.length - 2].isLetter() &&
                    trimmed.first().isLowerCase()
                ) {
                    // Word hyphenated at the line end: "сло-\nво" -> "слово".
                    paragraph.setLength(paragraph.length - 1)
                } else {
                    paragraph.append(' ')
                }
            }
            paragraph.append(trimmed)
            if (mode == Mode.HARD_WRAPPED && width > 0 && line.trimEnd().length < width * 3 / 4) {
                // A clearly short line ends its paragraph in hard-wrapped text.
                flush()
            }
            previousBlank = false
        }
        flush()
        while (blocks.isNotEmpty()) {
            val last = blocks.last()
            if (last is TxtBlock.Paragraph && last.raw.isEmpty()) blocks.removeAt(blocks.lastIndex) else break
        }
        return blocks
    }

    private fun startsDialogue(line: String): Boolean =
        line.startsWith("—") || line.startsWith("–") || line.startsWith("- ")
}
