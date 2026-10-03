package com.lumina.reader.core.tts

import com.lumina.reader.core.model.ParagraphMarkup
import java.util.Locale

/** Picks the speech language for a chapter from its script: mostly Cyrillic means Russian. */
object TtsLanguageDetector {
    val RUSSIAN: Locale = Locale.forLanguageTag("ru-RU")
    val ENGLISH: Locale = Locale.ENGLISH

    private const val DEFAULT_SAMPLE_LETTERS = 4000

    fun detect(paragraphs: List<String>, sampleLetters: Int = DEFAULT_SAMPLE_LETTERS): Locale {
        var cyrillic = 0
        var latin = 0
        for (raw in paragraphs) {
            if (!TtsQueue.isSpeakable(raw)) continue
            for (c in ParagraphMarkup.plainText(raw)) {
                when {
                    c in 'Ѐ'..'ӿ' -> cyrillic++
                    c in 'a'..'z' || c in 'A'..'Z' -> latin++
                }
            }
            if (cyrillic + latin >= sampleLetters) break
        }
        return if (cyrillic == 0 && latin == 0) RUSSIAN else if (cyrillic >= latin) RUSSIAN else ENGLISH
    }
}
