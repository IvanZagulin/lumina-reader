package com.lumina.reader.ui.reader

import com.lumina.reader.core.model.DEFAULT_PARAGRAPH_SPACING_EM
import com.lumina.reader.core.model.LINE_SPACING_OPTIONS
import com.lumina.reader.core.model.MARGIN_OPTIONS_DP
import com.lumina.reader.core.model.PARAGRAPH_SPACING_OPTIONS_EM
import com.lumina.reader.core.model.PageTurnAnimation
import com.lumina.reader.core.model.ReaderFontIds
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderTapZones
import com.lumina.reader.core.model.ReaderThemeMode
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.core.model.effectiveTheme
import com.lumina.reader.core.model.legacyAutoThemes
import com.lumina.reader.core.model.legacyParagraphSpacingEm
import com.lumina.reader.core.model.nearestOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSettingsModelTest {

    @Test
    fun legacyFontNamesMapOntoTheCatalogue() {
        assertEquals(ReaderFontIds.LITERATA, ReaderFontIds.migrate("Serif"))
        assertEquals(ReaderFontIds.GOLOS, ReaderFontIds.migrate("SansSerif"))
        assertEquals(ReaderFontIds.MONO, ReaderFontIds.migrate("Monospace"))
        assertEquals(ReaderFontIds.LITERATA, ReaderFontIds.migrate("Cursive"))
        assertEquals(ReaderFontIds.LITERATA, ReaderFontIds.migrate(null))
        assertEquals(ReaderFontIds.LITERATA, ReaderFontIds.migrate(""))
        assertEquals(ReaderFontIds.LITERATA, ReaderFontIds.migrate("Comic Sans"))
        ReaderFontIds.all.forEach { id -> assertEquals(id, ReaderFontIds.migrate(id)) }
        assertEquals(ReaderFontIds.LITERATA, ReaderSettings().fontFamily)
    }

    @Test
    fun themesFollowTheSpecPalette() {
        assertEquals(
            listOf("Светлая", "Кремовая", "Сепия", "Сумерки", "Янтарь", "Чёрная"),
            ReadingTheme.entries.map { it.title }
        )
        assertEquals(
            listOf(false, false, false, true, true, true),
            ReadingTheme.entries.map { it.isDark }
        )
        assertEquals(0xFF6B6E76, ReadingTheme.LIGHT.secondaryTextColor)
        assertEquals(0xFF6F5E4B, ReadingTheme.SEPIA.secondaryTextColor)
        assertEquals(0xFFFFB45E, ReadingTheme.WARM_AMBER.accentColor)
        // onAccent: white on light pages, the page colour on dark ones.
        assertEquals(androidx.compose.ui.graphics.Color.White, ReadingTheme.CREAM.onAccentComposeColor)
        assertEquals(ReadingTheme.OLED_BLACK.bgComposeColor, ReadingTheme.OLED_BLACK.onAccentComposeColor)
        ReadingTheme.entries.forEach { theme -> assertTrue(theme.isDark != theme.counterpart.isDark) }
    }

    @Test
    fun autoThemeUsesTheDayAndNightChoice() {
        val manual = ReaderSettings(theme = ReadingTheme.SEPIA)
        assertEquals(ReadingTheme.SEPIA, manual.effectiveTheme(systemInDarkMode = true))

        val auto = manual.copy(
            themeMode = ReaderThemeMode.SYSTEM,
            dayTheme = ReadingTheme.CREAM,
            nightTheme = ReadingTheme.DARK_SLATE
        )
        assertEquals(ReadingTheme.CREAM, auto.effectiveTheme(systemInDarkMode = false))
        assertEquals(ReadingTheme.DARK_SLATE, auto.effectiveTheme(systemInDarkMode = true))

        // Defaults of the pair: «Днём: Кремовая · Ночью: Чёрная».
        assertEquals(ReadingTheme.CREAM, ReaderSettings().dayTheme)
        assertEquals(ReadingTheme.OLED_BLACK, ReaderSettings().nightTheme)
    }

    @Test
    fun oldSystemModeKeepsItsPair() {
        assertEquals(ReadingTheme.SEPIA to ReadingTheme.WARM_AMBER, legacyAutoThemes(ReadingTheme.SEPIA))
        assertEquals(ReadingTheme.SEPIA to ReadingTheme.WARM_AMBER, legacyAutoThemes(ReadingTheme.WARM_AMBER))
        assertEquals(ReadingTheme.LIGHT to ReadingTheme.OLED_BLACK, legacyAutoThemes(ReadingTheme.OLED_BLACK))
    }

    @Test
    fun paragraphSpacingMigratesFromDp() {
        assertEquals(0f, legacyParagraphSpacingEm(0, 18))
        assertEquals(0.35f, legacyParagraphSpacingEm(6, 18))
        assertEquals(0.7f, legacyParagraphSpacingEm(12, 18))
        assertEquals(1f, legacyParagraphSpacingEm(30, 18))
        assertEquals(DEFAULT_PARAGRAPH_SPACING_EM, ReaderSettings().paragraphSpacingEm)
        assertEquals(listOf(0f, 0.35f, 0.7f, 1f), PARAGRAPH_SPACING_OPTIONS_EM)
    }

    @Test
    fun storedValuesSnapToTheNearestChoice() {
        assertEquals(1.45f, nearestOption(1.5f, LINE_SPACING_OPTIONS))
        assertEquals(1.2f, nearestOption(1.25f, LINE_SPACING_OPTIONS))
        assertEquals(2.0f, nearestOption(1.8f + 0.1f, LINE_SPACING_OPTIONS))
        assertEquals(32, nearestOption(30, MARGIN_OPTIONS_DP))
        assertEquals(12, nearestOption(0, MARGIN_OPTIONS_DP))
    }

    @Test
    fun newSettingsHaveTheDocumentedDefaults() {
        val defaults = ReaderSettings()
        assertTrue(defaults.hyphenation)
        assertEquals(1.5f, defaults.firstLineIndentEm)
        assertTrue(defaults.showTimeLeft)
        assertFalse(defaults.showProgressLine)
        assertFalse(defaults.tapZonesInverted)
        assertEquals(ReaderTapZones.CLASSIC, defaults.tapZones)
        assertEquals(ReaderThemeMode.MANUAL, defaults.themeMode)
        assertEquals(PageTurnAnimation.SLIDE, defaults.pageTurnAnimation)
        assertEquals(
            listOf("SLIDE", "FLIP", "CURL", "NONE"),
            PageTurnAnimation.entries.map { it.name }
        )
    }
}
