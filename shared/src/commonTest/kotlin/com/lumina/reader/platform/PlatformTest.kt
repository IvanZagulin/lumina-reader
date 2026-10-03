package com.lumina.reader.platform

import com.lumina.reader.core.tts.TtsLanguageDetector
import com.lumina.reader.core.tts.TtsPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlatformTest {

    @Test
    fun clocks() {
        val now = AppClock.nowMillis()
        assertTrue(now > 1_700_000_000_000L, "epoch millis: $now")
        val mark = AppClock.monotonic()
        assertTrue(mark.elapsedNow().inWholeMilliseconds >= 0)
    }

    @Test
    fun randomUuids() {
        val first = Ids.randomUuid()
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(first), first)
        assertNotEquals(first, Ids.randomUuid())
    }

    @Test
    fun languages() {
        assertEquals(LanguageTag("ru-RU"), TtsLanguageDetector.RUSSIAN)
        assertEquals("en", TtsLanguageDetector.ENGLISH.bcp47)
        assertTrue(systemLanguageTag().bcp47.isNotBlank())
        val english = TtsLanguageDetector.ENGLISH.displayLanguage(LanguageTag("ru"))
        assertTrue(english.isNotBlank())
        assertEquals(
            "Нет голоса для языка «$english». Используется голос по умолчанию",
            TtsPlayer.missingVoiceMessage(TtsLanguageDetector.ENGLISH)
        )
        assertEquals(PlatformKind.entries.size, 2)
        assertTrue(AppInfo.platform in PlatformKind.entries)
    }
}
