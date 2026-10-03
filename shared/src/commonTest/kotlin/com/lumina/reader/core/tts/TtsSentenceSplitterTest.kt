package com.lumina.reader.core.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsSentenceSplitterTest {

    private fun sentences(text: String, max: Int = TtsSentenceSplitter.DEFAULT_MAX_LENGTH): List<String> =
        TtsSentenceSplitter.split(text, max).map { text.substring(it.first, it.last + 1) }

    @Test
    fun splitsSimpleSentences() {
        assertEquals(
            listOf("Привет.", "Как дела?", "Хорошо!"),
            sentences("Привет. Как дела? Хорошо!")
        )
    }

    @Test
    fun keepsThatIsAbbreviationInsideSentence() {
        assertEquals(
            listOf("Это был он, т. е. мой брат.", "Потом он ушёл."),
            sentences("Это был он, т. е. мой брат. Потом он ушёл.")
        )
        assertEquals(
            listOf("Он ушёл, т.е. Иван Петрович остался один."),
            sentences("Он ушёл, т.е. Иван Петрович остался один.")
        )
    }

    @Test
    fun etceteraMayEndSentence() {
        assertEquals(
            listOf("Купили яблоки, груши и т.д.", "Потом пошли домой."),
            sentences("Купили яблоки, груши и т.д. Потом пошли домой.")
        )
        assertEquals(
            listOf("Книги, журналы и т. д.", "Всё унесли."),
            sentences("Книги, журналы и т. д. Всё унесли.")
        )
        assertEquals(
            listOf("Столы, стулья и т. п. мы продали."),
            sentences("Столы, стулья и т. п. мы продали.")
        )
    }

    @Test
    fun keepsInitialsTogether() {
        assertEquals(
            listOf("А. С. Пушкин родился в 1799 г. в Москве.", "Он был поэтом."),
            sentences("А. С. Пушкин родился в 1799 г. в Москве. Он был поэтом.")
        )
        assertEquals(
            listOf("Стихи читал А.С. Пушкин."),
            sentences("Стихи читал А.С. Пушкин.")
        )
    }

    @Test
    fun cityAbbreviationDoesNotBreakButYearCan() {
        assertEquals(
            listOf("Он жил в г. Москве.", "Потом уехал."),
            sentences("Он жил в г. Москве. Потом уехал.")
        )
        assertEquals(
            listOf("Это случилось в 1812 г.", "Война началась."),
            sentences("Это случилось в 1812 г. Война началась.")
        )
    }

    @Test
    fun englishTitlesAreNotSentenceEnds() {
        assertEquals(
            listOf("Mr. Smith went home.", "He slept."),
            sentences("Mr. Smith went home. He slept.")
        )
    }

    @Test
    fun handlesEllipsis() {
        assertEquals(listOf("Подожди...", "Я сейчас."), sentences("Подожди... Я сейчас."))
        assertEquals(listOf("Подожди... и я тоже пойду."), sentences("Подожди... и я тоже пойду."))
        assertEquals(listOf("Ну…", "Ладно."), sentences("Ну… Ладно."))
    }

    @Test
    fun dialogueDashAndQuotesStayWithAuthorWords() {
        assertEquals(
            listOf("— Привет! — сказал он.", "— Как дела?"),
            sentences("— Привет! — сказал он. — Как дела?")
        )
        assertEquals(
            listOf("«Нет!» — крикнул он.", "Все замолчали."),
            sentences("«Нет!» — крикнул он. Все замолчали.")
        )
        assertEquals(
            listOf("Он сказал: «Уходи.»", "Я ушёл."),
            sentences("Он сказал: «Уходи.» Я ушёл.")
        )
    }

    @Test
    fun decimalsAndLowercaseContinuationDoNotSplit() {
        assertEquals(listOf("Число 3.14 больше трёх."), sentences("Число 3.14 больше трёх."))
        assertEquals(listOf("Что? спросил он тихо."), sentences("Что? спросил он тихо."))
    }

    @Test
    fun combinedTerminatorsAreOneBoundary() {
        assertEquals(listOf("Что?!", "Не может быть."), sentences("Что?! Не может быть."))
    }

    @Test
    fun newlinesSeparateLines() {
        assertEquals(listOf("Мороз и солнце", "день чудесный"), sentences("Мороз и солнце\nдень чудесный"))
    }

    @Test
    fun dropsPunctuationOnlyPieces() {
        assertTrue(TtsSentenceSplitter.split("* * *").isEmpty())
        assertTrue(TtsSentenceSplitter.split("   ").isEmpty())
        assertTrue(TtsSentenceSplitter.split("").isEmpty())
    }

    @Test
    fun rangesAreTrimmedAndPointIntoText() {
        val text = "  Первое предложение.   Второе!  "
        val ranges = TtsSentenceSplitter.split(text)
        assertEquals(2, ranges.size)
        assertEquals("Первое предложение.", text.substring(ranges[0].first, ranges[0].last + 1))
        assertEquals("Второе!", text.substring(ranges[1].first, ranges[1].last + 1))
        assertTrue(ranges[0].last < ranges[1].first)
    }

    @Test
    fun capsLongSentenceAtCommas() {
        val text = (1..40).joinToString(", ") { "слово номер $it" } + "."
        val max = 60
        val pieces = sentences(text, max)
        assertTrue(pieces.size > 1)
        pieces.forEach { assertTrue(it.length <= max, "too long: ${it.length}") }
        // Every non-final piece ends at a comma, never mid-word.
        pieces.dropLast(1).forEach { assertTrue(it.endsWith(","), "bad cut: $it") }
        assertEquals(text.filterNot { it.isWhitespace() }, pieces.joinToString("").filterNot { it.isWhitespace() })
    }

    @Test
    fun capsLongSentenceWithoutPunctuationAtSpaces() {
        val text = (1..80).joinToString(" ") { "слово$it" }
        val max = 50
        val pieces = sentences(text, max)
        pieces.forEach { assertTrue(it.length <= max) }
        pieces.forEach { piece -> piece.split(' ').forEach { assertTrue(it.startsWith("слово")) } }
        assertEquals(text.split(' '), pieces.flatMap { it.split(' ') })
    }

    @Test
    fun hardCutsAWordLongerThanTheLimit() {
        val text = "a".repeat(95)
        val pieces = sentences(text, 40)
        assertEquals(listOf(40, 40, 15), pieces.map { it.length })
    }
}
