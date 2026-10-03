package com.lumina.reader.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumina.reader.core.model.ReaderFontIds
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderThemeMode
import com.lumina.reader.core.model.legacyAutoThemes
import com.lumina.reader.core.model.legacyParagraphSpacingEm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "reader_settings")

class ReaderPreferences(private val context: Context) {

    private object PreferencesKeys {
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val LINE_SPACING = floatPreferencesKey("line_spacing_multiplier")
        val HORIZONTAL_PADDING = intPreferencesKey("horizontal_padding_dp")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val THEME = stringPreferencesKey("reading_theme")
        val BIONIC_READING = booleanPreferencesKey("bionic_reading")
        val CONTINUOUS_SCROLL = booleanPreferencesKey("continuous_scroll")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val VOLUME_NAV = booleanPreferencesKey("volume_key_nav")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val FOOTER_BOOK_PAGES = booleanPreferencesKey("footer_book_pages")
        val TEXT_ALIGN = stringPreferencesKey("text_align")
        val HYPHENATION = booleanPreferencesKey("hyphenation")
        val FIRST_LINE_INDENT = floatPreferencesKey("first_line_indent_em")

        /** Written by versions before paragraph spacing was measured in em; read for migration only. */
        val LEGACY_PARAGRAPH_SPACING_DP = intPreferencesKey("paragraph_spacing_dp")
        val PARAGRAPH_SPACING_EM = floatPreferencesKey("paragraph_spacing_em")
        val PAGE_TURN_ANIMATION = stringPreferencesKey("page_turn_animation")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DAY_THEME = stringPreferencesKey("day_theme")
        val NIGHT_THEME = stringPreferencesKey("night_theme")
        val SHOW_TIME_LEFT = booleanPreferencesKey("show_time_left")
        val SHOW_PROGRESS_LINE = booleanPreferencesKey("footer_progress_line")
        val TAP_ZONES_INVERTED = booleanPreferencesKey("tap_zones_inverted")
        val TAP_ZONES = stringPreferencesKey("tap_zones")
    }

    val settingsFlow: Flow<ReaderSettings> = context.dataStore.data.map { readSettings(it) }

    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { preferences ->
            writeSettings(preferences, transform(readSettings(preferences)))
        }
    }

    private fun readSettings(preferences: Preferences): ReaderSettings {
        val defaults = ReaderSettings()
        val fontSize = preferences[PreferencesKeys.FONT_SIZE] ?: defaults.fontSizeSp
        val theme = enumOrDefault(preferences[PreferencesKeys.THEME], defaults.theme)
        val themeMode = enumOrDefault(preferences[PreferencesKeys.THEME_MODE], defaults.themeMode)
        // Readers who chose «Как в системе» before day and night themes existed
        // keep their old pair: the stored theme and its counterpart.
        val (legacyDay, legacyNight) = if (themeMode == ReaderThemeMode.SYSTEM) {
            legacyAutoThemes(theme)
        } else {
            defaults.dayTheme to defaults.nightTheme
        }
        return ReaderSettings(
            fontSizeSp = fontSize,
            lineSpacingMultiplier = preferences[PreferencesKeys.LINE_SPACING]
                ?: defaults.lineSpacingMultiplier,
            horizontalPaddingDp = preferences[PreferencesKeys.HORIZONTAL_PADDING]
                ?: defaults.horizontalPaddingDp,
            fontFamily = ReaderFontIds.migrate(preferences[PreferencesKeys.FONT_FAMILY]),
            theme = theme,
            isBionicReadingEnabled = preferences[PreferencesKeys.BIONIC_READING]
                ?: defaults.isBionicReadingEnabled,
            isContinuousScroll = preferences[PreferencesKeys.CONTINUOUS_SCROLL]
                ?: defaults.isContinuousScroll,
            keepScreenOn = preferences[PreferencesKeys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            volumeKeyNavigation = preferences[PreferencesKeys.VOLUME_NAV]
                ?: defaults.volumeKeyNavigation,
            ttsSpeed = preferences[PreferencesKeys.TTS_SPEED] ?: defaults.ttsSpeed,
            ttsPitch = preferences[PreferencesKeys.TTS_PITCH] ?: defaults.ttsPitch,
            showBookPagesInFooter = preferences[PreferencesKeys.FOOTER_BOOK_PAGES]
                ?: defaults.showBookPagesInFooter,
            textAlign = enumOrDefault(preferences[PreferencesKeys.TEXT_ALIGN], defaults.textAlign),
            hyphenation = preferences[PreferencesKeys.HYPHENATION] ?: defaults.hyphenation,
            firstLineIndentEm = preferences[PreferencesKeys.FIRST_LINE_INDENT]
                ?: defaults.firstLineIndentEm,
            paragraphSpacingEm = preferences[PreferencesKeys.PARAGRAPH_SPACING_EM]
                ?: preferences[PreferencesKeys.LEGACY_PARAGRAPH_SPACING_DP]
                    ?.let { legacyParagraphSpacingEm(it, fontSize) }
                ?: defaults.paragraphSpacingEm,
            // Unknown names (a newer or retired animation) fall back to SLIDE.
            pageTurnAnimation = enumOrDefault(
                preferences[PreferencesKeys.PAGE_TURN_ANIMATION],
                defaults.pageTurnAnimation
            ),
            themeMode = themeMode,
            dayTheme = enumOrDefault(preferences[PreferencesKeys.DAY_THEME], legacyDay),
            nightTheme = enumOrDefault(preferences[PreferencesKeys.NIGHT_THEME], legacyNight),
            showTimeLeft = preferences[PreferencesKeys.SHOW_TIME_LEFT] ?: defaults.showTimeLeft,
            showProgressLine = preferences[PreferencesKeys.SHOW_PROGRESS_LINE]
                ?: defaults.showProgressLine,
            tapZonesInverted = preferences[PreferencesKeys.TAP_ZONES_INVERTED]
                ?: defaults.tapZonesInverted,
            tapZones = enumOrDefault(preferences[PreferencesKeys.TAP_ZONES], defaults.tapZones)
        )
    }

    private fun writeSettings(preferences: MutablePreferences, settings: ReaderSettings) {
        preferences[PreferencesKeys.FONT_SIZE] = settings.fontSizeSp
        preferences[PreferencesKeys.LINE_SPACING] = settings.lineSpacingMultiplier
        preferences[PreferencesKeys.HORIZONTAL_PADDING] = settings.horizontalPaddingDp
        preferences[PreferencesKeys.FONT_FAMILY] = ReaderFontIds.migrate(settings.fontFamily)
        preferences[PreferencesKeys.THEME] = settings.theme.name
        preferences[PreferencesKeys.BIONIC_READING] = settings.isBionicReadingEnabled
        preferences[PreferencesKeys.CONTINUOUS_SCROLL] = settings.isContinuousScroll
        preferences[PreferencesKeys.KEEP_SCREEN_ON] = settings.keepScreenOn
        preferences[PreferencesKeys.VOLUME_NAV] = settings.volumeKeyNavigation
        preferences[PreferencesKeys.TTS_SPEED] = settings.ttsSpeed
        preferences[PreferencesKeys.TTS_PITCH] = settings.ttsPitch
        preferences[PreferencesKeys.FOOTER_BOOK_PAGES] = settings.showBookPagesInFooter
        preferences[PreferencesKeys.TEXT_ALIGN] = settings.textAlign.name
        preferences[PreferencesKeys.HYPHENATION] = settings.hyphenation
        preferences[PreferencesKeys.FIRST_LINE_INDENT] = settings.firstLineIndentEm
        preferences[PreferencesKeys.PARAGRAPH_SPACING_EM] = settings.paragraphSpacingEm
        if (PreferencesKeys.LEGACY_PARAGRAPH_SPACING_DP in preferences) {
            preferences.remove(PreferencesKeys.LEGACY_PARAGRAPH_SPACING_DP)
        }
        preferences[PreferencesKeys.PAGE_TURN_ANIMATION] = settings.pageTurnAnimation.name
        preferences[PreferencesKeys.THEME_MODE] = settings.themeMode.name
        preferences[PreferencesKeys.DAY_THEME] = settings.dayTheme.name
        preferences[PreferencesKeys.NIGHT_THEME] = settings.nightTheme.name
        preferences[PreferencesKeys.SHOW_TIME_LEFT] = settings.showTimeLeft
        preferences[PreferencesKeys.SHOW_PROGRESS_LINE] = settings.showProgressLine
        preferences[PreferencesKeys.TAP_ZONES_INVERTED] = settings.tapZonesInverted
        preferences[PreferencesKeys.TAP_ZONES] = settings.tapZones.name
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        if (name == null) default else enumValues<T>().firstOrNull { it.name == name } ?: default
}
