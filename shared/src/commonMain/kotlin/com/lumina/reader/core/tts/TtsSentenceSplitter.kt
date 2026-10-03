package com.lumina.reader.core.tts

/**
 * Splits plain paragraph text (already passed through
 * `ParagraphMarkup.plainText`) into sentences for speech.
 *
 * The result is a list of character ranges into the given text, trimmed of
 * surrounding whitespace. Pieces without a single letter or digit (scene
 * breaks such as "* * *") are dropped. Sentences longer than `maxLength` are
 * cut at the last comma, semicolon, colon or spaced dash, then at a space, and
 * only as a last resort mid-word, so that the speech engine never receives an
 * over-long utterance.
 *
 * Handles Russian and English text: abbreviations ("т. е.", "и т.д.", "г.",
 * "Mr."), initials ("А. С. Пушкин"), ellipses, closing quotes and dialogue
 * dashes ("— Привет! — сказал он.").
 */
object TtsSentenceSplitter {
    const val DEFAULT_MAX_LENGTH = 400

    private const val MIN_MAX_LENGTH = 10
    private const val TERMINATORS = ".!?…"
    private const val CLOSERS = "\"'»”’)]}"
    private const val LEADING_SKIPPABLE = "\"'«„“‘([{—–-"
    private const val SOFT_BREAKS = ",;:"
    private const val DASHES = "—–"
    private const val ROMAN_OR_RANGE = "IVXLCDM–-"

    /** Abbreviations that qualify the next word, so a period after them never ends a sentence. */
    private val PREFIX_ABBREVIATIONS = setOf(
        "т", "тт", "им", "ул", "просп", "пер", "пл", "наб", "проф", "акад", "доц", "канд",
        "тов", "св", "гр", "ст", "см", "ср", "рис", "табл", "стр", "гл", "напр", "англ", "лат",
        "греч", "франц", "нем", "букв", "прим", "ред", "изд", "обл", "кн",
        "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "fig", "vol", "ch", "pp"
    )

    /** Abbreviations written with inner dots that never end a sentence. */
    private val INNER_DOT_ABBREVIATIONS = setOf("т.е", "т.к", "т.н", "т.о", "т.ч", "e.g", "i.e")

    /** "и т. д." and "и т. п." may legitimately end a sentence. */
    private val CHAIN_FINAL_LETTERS = setOf("д", "п")

    /** "1799 г." / "XIX в." may end a sentence, "в г. Москве" may not. */
    private val YEAR_OR_CENTURY = setOf("г", "гг", "в", "вв")

    fun split(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): List<IntRange> {
        val limit = maxLength.coerceAtLeast(MIN_MAX_LENGTH)
        val result = ArrayList<IntRange>()
        val n = text.length
        var sentenceStart = 0
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                emit(text, sentenceStart, i, limit, result)
                sentenceStart = i + 1
                i++
                continue
            }
            if (TERMINATORS.indexOf(c) < 0) {
                i++
                continue
            }
            val runStart = i
            var j = i
            while (j < n && TERMINATORS.indexOf(text[j]) >= 0) j++
            val runEnd = j
            while (j < n && CLOSERS.indexOf(text[j]) >= 0) j++
            val boundary = j
            if (isSentenceEnd(text, runStart, runEnd, boundary)) {
                emit(text, sentenceStart, boundary, limit, result)
                sentenceStart = boundary
            }
            i = boundary
        }
        emit(text, sentenceStart, n, limit, result)
        return result
    }

    private fun isSentenceEnd(text: String, runStart: Int, runEnd: Int, boundary: Int): Boolean {
        if (boundary < text.length && !text[boundary].isWhitespace()) return false
        val next = nextSignificantChar(text, boundary)
        if (next != null && next.isLowerCase()) return false
        if (next == null) return true
        val singlePeriod = runEnd - runStart == 1 && text[runStart] == '.'
        return !(singlePeriod && isAbbreviation(text, runStart))
    }

    private fun nextSignificantChar(text: String, from: Int): Char? {
        var k = from
        while (k < text.length && (text[k].isWhitespace() || LEADING_SKIPPABLE.indexOf(text[k]) >= 0)) k++
        return if (k < text.length) text[k] else null
    }

    private fun isAbbreviation(text: String, dotIndex: Int): Boolean {
        var s = dotIndex
        while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '.')) s--
        val token = text.substring(s, dotIndex).trimStart('.')
        if (token.isEmpty()) return false
        val lower = token.lowercase()

        if (token.indexOf('.') >= 0) {
            if (lower in INNER_DOT_ABBREVIATIONS) return true
            // Initials written together ("А.С.") or "U.S."
            return token.split('.').all { it.length == 1 && it[0].isLetter() && it[0].isUpperCase() }
        }
        if (token.length == 1 && token[0].isLetter() && token[0].isUpperCase()) return true
        if (lower in PREFIX_ABBREVIATIONS) return true

        val previous = previousWord(text, s)
        if (lower in YEAR_OR_CENTURY) return !isNumberLike(previous)
        if (token.length == 1 && token[0].isLetter() && previous.endsWith('.')) {
            // Second half of "т. е.", "т. к.", "e. g."; only "т. д." / "т. п." may end a sentence.
            return lower !in CHAIN_FINAL_LETTERS
        }
        return false
    }

    private fun previousWord(text: String, end: Int): String {
        var e = end
        while (e > 0 && text[e - 1].isWhitespace()) e--
        var b = e
        while (b > 0 && !text[b - 1].isWhitespace()) b--
        return text.substring(b, e)
    }

    private fun isNumberLike(word: String): Boolean {
        val core = word.trimEnd(',', ';', ':')
        if (core.isEmpty()) return false
        return core.all { it.isDigit() || ROMAN_OR_RANGE.indexOf(it) >= 0 } &&
            core.any { it.isDigit() || it.isLetter() }
    }

    private fun emit(text: String, start: Int, end: Int, limit: Int, out: MutableList<IntRange>) {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s >= e || !hasSpeakableContent(text, s, e)) return
        if (e - s <= limit) {
            out.add(s until e)
            return
        }
        var pieceStart = s
        while (e - pieceStart > limit) {
            val cut = findCut(text, pieceStart, pieceStart + limit)
            addPiece(text, pieceStart, cut, out)
            pieceStart = cut
        }
        addPiece(text, pieceStart, e, out)
    }

    /** Returns an exclusive end in (start, windowEnd] at which an over-long sentence is cut. */
    private fun findCut(text: String, start: Int, windowEnd: Int): Int {
        val minCut = start + (windowEnd - start) / 3
        var i = windowEnd - 1
        while (i > minCut) {
            val c = text[i]
            if (SOFT_BREAKS.indexOf(c) >= 0 && i + 1 < text.length && text[i + 1].isWhitespace()) return i + 1
            if (DASHES.indexOf(c) >= 0 && text[i - 1].isWhitespace()) return i
            i--
        }
        i = windowEnd - 1
        while (i > start) {
            if (text[i].isWhitespace()) return i
            i--
        }
        return windowEnd
    }

    private fun addPiece(text: String, start: Int, end: Int, out: MutableList<IntRange>) {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        if (s < e && hasSpeakableContent(text, s, e)) out.add(s until e)
    }

    private fun hasSpeakableContent(text: String, start: Int, end: Int): Boolean {
        for (k in start until end) {
            if (text[k].isLetterOrDigit()) return true
        }
        return false
    }
}
