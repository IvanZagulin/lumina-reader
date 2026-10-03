package com.lumina.reader.platform

import com.lumina.reader.core.tts.TtsLanguageDetector
import com.lumina.reader.core.tts.TtsPlayer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** LanguageTag on Android gives exactly the Locale values the TTS code used before. */
class AndroidPlatformTest {

    @Test
    fun ttsLanguagesAreTheFormerLocales() {
        assertEquals(Locale.Builder().setLanguage("ru").setRegion("RU").build(), TtsLanguageDetector.RUSSIAN.toLocale())
        assertEquals(Locale.forLanguageTag("ru-RU"), TtsLanguageDetector.RUSSIAN.toLocale())
        assertEquals(Locale.ENGLISH, TtsLanguageDetector.ENGLISH.toLocale())
        assertEquals(Locale.getDefault().language, systemLanguageTag().toLocale().language)
    }

    @Test
    fun missingVoiceMessageIsUnchanged() {
        val russian = Locale.forLanguageTag("ru")
        for (tag in listOf("en", "ru-RU", "de", "uk", "zz", "und")) {
            val locale = Locale.forLanguageTag(tag)
            val name = locale.getDisplayLanguage(russian).ifBlank { locale.language }
            assertEquals(
                "Нет голоса для языка «$name». Используется голос по умолчанию",
                TtsPlayer.missingVoiceMessage(LanguageTag(tag))
            )
        }
    }
}
