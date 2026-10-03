package com.lumina.reader.core.tts

import com.lumina.reader.core.model.ParagraphMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsQueueTest {

    private val paragraphs = listOf(
        "",                                   // 0 blank
        "[IMG:cover]",                        // 1 image
        "Первый абзац. Второе предложение.",  // 2
        "   ",                                // 3 blank
        "* * *",                              // 4 punctuation only
        ParagraphMarkup.block(ParagraphMarkup.BlockStyle.HEADING, "Это " + ParagraphMarkup.emphasis("важно") + "!"), // 5
        "[IMG:pic1]",                         // 6
        "Последний."                          // 7
    )

    @Test
    fun seekSkipsBlankImageAndPunctuationParagraphsButKeepsOriginalIndex() {
        val queue = TtsQueue(paragraphs)
        val first = queue.seekToParagraph(0)!!
        assertEquals(2, first.paragraphIndex)
        assertEquals("Первый абзац.", first.text)

        val fromBlank = queue.seekToParagraph(3)!!
        assertEquals(5, fromBlank.paragraphIndex)
        // Markers are stripped and the range points into plainText().
        assertEquals("Это важно!", fromBlank.text)
        val plain = ParagraphMarkup.plainText(paragraphs[5])
        assertEquals(fromBlank.text, plain.substring(fromBlank.range.first, fromBlank.range.last + 1))
    }

    @Test
    fun advanceWalksSentencesThenParagraphsThenFinishes() {
        val queue = TtsQueue(paragraphs)
        queue.seekToParagraph(0)
        val spoken = mutableListOf(queue.current()!!)
        while (true) {
            val next = queue.advance() ?: break
            spoken += next
        }
        assertEquals(
            listOf("Первый абзац.", "Второе предложение.", "Это важно!", "Последний."),
            spoken.map { it.text }
        )
        assertEquals(listOf(2, 2, 5, 7), spoken.map { it.paragraphIndex })
        assertEquals(listOf(0, 1, 0, 0), spoken.map { it.sentenceIndex })
        assertTrue(queue.isFinished)
        assertNull(queue.current())
        assertNull(queue.advance())
        assertEquals(7, queue.lastParagraphIndex)
    }

    @Test
    fun seekPastLastSpeakableParagraphFinishes() {
        val queue = TtsQueue(paragraphs)
        assertNull(queue.seekToParagraph(8))
        assertTrue(queue.isFinished)
        assertNull(queue.seekToParagraph(100))
    }

    @Test
    fun negativeStartIsClamped() {
        assertEquals(2, TtsQueue(paragraphs).seekToParagraph(-5)!!.paragraphIndex)
    }

    @Test
    fun nextAndPreviousParagraphSkipUnspeakable() {
        val queue = TtsQueue(paragraphs)
        queue.seekToParagraph(2)
        queue.advance() // second sentence of paragraph 2
        assertEquals(5, queue.nextParagraph()!!.paragraphIndex)
        assertEquals(7, queue.nextParagraph()!!.paragraphIndex)
        assertNull(queue.nextParagraph())
        assertTrue(queue.isFinished)

        // From the finished state "previous" returns to the last speakable paragraph.
        assertEquals(7, queue.previousParagraph()!!.paragraphIndex)
        assertEquals(5, queue.previousParagraph()!!.paragraphIndex)
        val atFirst = queue.previousParagraph()!!
        assertEquals(2, atFirst.paragraphIndex)
        assertEquals(0, atFirst.sentenceIndex)

        // At the first speakable paragraph it restarts that paragraph.
        queue.advance()
        val restarted = queue.previousParagraph()!!
        assertEquals(2, restarted.paragraphIndex)
        assertEquals(0, restarted.sentenceIndex)
    }

    @Test
    fun chapterWithoutSpeakableTextIsFinishedImmediately() {
        val queue = TtsQueue(listOf("", "[IMG:x]", "— — —"))
        assertNull(queue.seekToParagraph(0))
        assertTrue(queue.isFinished)
        assertNull(queue.previousParagraph())
    }

    @Test
    fun speakablePreCheck() {
        assertFalse(TtsQueue.isSpeakable(""))
        assertFalse(TtsQueue.isSpeakable("  \n "))
        assertFalse(TtsQueue.isSpeakable("[IMG:abc]"))
        assertTrue(TtsQueue.isSpeakable("Текст"))
    }

    @Test
    fun longParagraphIsSplitIntoCappedSegments() {
        val long = (1..60).joinToString(", ") { "часть $it" } + "."
        val queue = TtsQueue(listOf(long), maxSentenceLength = 80)
        var segment = queue.seekToParagraph(0)
        var count = 0
        while (segment != null) {
            assertTrue(segment.text.length <= 80)
            assertEquals(0, segment.paragraphIndex)
            count++
            segment = queue.advance()
        }
        assertTrue(count > 1)
    }

    @Test
    fun languageDetection() {
        assertEquals(TtsLanguageDetector.RUSSIAN, TtsLanguageDetector.detect(listOf("Привет, мир. Hello там.")))
        assertEquals(TtsLanguageDetector.ENGLISH, TtsLanguageDetector.detect(listOf("[IMG:x]", "Hello world, это test.")))
        assertEquals(TtsLanguageDetector.RUSSIAN, TtsLanguageDetector.detect(listOf("", "123 456")))
        assertEquals(Locale.Builder().setLanguage("ru").setRegion("RU").build(), TtsLanguageDetector.RUSSIAN)
    }
}
