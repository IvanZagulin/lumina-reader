package com.lumina.reader.core.tts

import com.lumina.reader.core.model.ParagraphMarkup

/**
 * One utterance: a sentence (or a capped piece of one) of a paragraph.
 *
 * [paragraphIndex] is the index in the ORIGINAL chapter paragraph list and
 * [range] the character range inside `ParagraphMarkup.plainText(paragraph)`;
 * [text] is exactly that substring.
 */
data class TtsSegment(
    val paragraphIndex: Int,
    val sentenceIndex: Int,
    val range: IntRange,
    val text: String
)

/**
 * Cursor over the sentences of one chapter. Blank, image and
 * punctuation-only paragraphs are skipped, but every reported index refers to
 * the original paragraph list. Sentences are computed lazily, one paragraph at
 * a time.
 */
class TtsQueue(
    private val paragraphs: List<String>,
    private val maxSentenceLength: Int = TtsSentenceSplitter.DEFAULT_MAX_LENGTH
) {
    private var paragraphIndex = paragraphs.size
    private var plainText = ""
    private var sentences: List<IntRange> = emptyList()
    private var sentenceIndex = 0

    /** Last paragraph the cursor stood on; kept after the chapter is finished. */
    var lastParagraphIndex: Int = 0
        private set

    /** True when the cursor is past the last speakable sentence of the chapter. */
    val isFinished: Boolean
        get() = paragraphIndex !in paragraphs.indices || sentenceIndex !in sentences.indices

    fun current(): TtsSegment? {
        if (isFinished) return null
        val range = sentences[sentenceIndex]
        return TtsSegment(
            paragraphIndex = paragraphIndex,
            sentenceIndex = sentenceIndex,
            range = range,
            text = plainText.substring(range.first, range.last + 1)
        )
    }

    /** Moves to the first sentence of the first speakable paragraph at or after [index]. */
    fun seekToParagraph(index: Int): TtsSegment? {
        for (i in index.coerceAtLeast(0) until paragraphs.size) {
            if (load(i)) return current()
        }
        markFinished()
        return null
    }

    /** Moves to the next sentence, crossing paragraphs; null at the end of the chapter. */
    fun advance(): TtsSegment? {
        if (isFinished) return null
        if (sentenceIndex + 1 < sentences.size) {
            sentenceIndex++
            return current()
        }
        return seekToParagraph(paragraphIndex + 1)
    }

    fun nextParagraph(): TtsSegment? {
        if (isFinished) return null
        return seekToParagraph(paragraphIndex + 1)
    }

    /**
     * Moves to the start of the previous speakable paragraph. At the first
     * speakable paragraph it restarts the current one. When the chapter is
     * finished it moves to the last speakable paragraph.
     */
    fun previousParagraph(): TtsSegment? {
        val finished = isFinished
        val from = if (finished) paragraphs.size - 1 else paragraphIndex - 1
        for (i in from downTo 0) {
            if (load(i)) return current()
        }
        if (!finished) {
            sentenceIndex = 0
            return current()
        }
        return null
    }

    private fun load(index: Int): Boolean {
        val raw = paragraphs[index]
        if (!isSpeakable(raw)) return false
        val text = ParagraphMarkup.plainText(raw)
        val ranges = TtsSentenceSplitter.split(text, maxSentenceLength)
        if (ranges.isEmpty()) return false
        paragraphIndex = index
        plainText = text
        sentences = ranges
        sentenceIndex = 0
        lastParagraphIndex = index
        return true
    }

    private fun markFinished() {
        paragraphIndex = paragraphs.size
        plainText = ""
        sentences = emptyList()
        sentenceIndex = 0
    }

    companion object {
        /** Cheap pre-check: blank paragraphs and illustrations are never spoken. */
        fun isSpeakable(raw: String): Boolean {
            if (raw.isBlank()) return false
            val trimmed = raw.trim()
            return !trimmed.startsWith("[IMG:") && !ParagraphMarkup.isImage(trimmed)
        }
    }
}
