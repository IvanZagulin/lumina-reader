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
import com.lumina.reader.core.model.ReaderSettings
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
        val FOOTER_BOOK_PAGES = booleanPreferencesKey("footer_book_pages")
        val TEXT_ALIGN = stringPreferencesKey("text_align")
        val HYPHENATION = booleanPreferencesKey("hyphenation")
        val FIRST_LINE_INDENT = floatPreferencesKey("first_line_indent_em")
        val PARAGRAPH_SPACING = intPreferencesKey("paragraph_spacing_dp")
        val PAGE_TURN_ANIMATION = stringPreferencesKey("page_turn_animation")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHOW_TIME_LEFT = booleanPreferencesKey("show_time_left")
        val TAP_ZONES_INVERTED = booleanPreferencesKey("tap_zones_inverted")
    }

    val settingsFlow: Flow<ReaderSettings> = context.dataStore.data.map { readSettings(it) }

    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { preferences ->
            writeSettings(preferences, transform(readSettings(preferences)))
        }
    }

    private fun readSettings(preferences: Preferences): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            fontSizeSp = preferences[PreferencesKeys.FONT_SIZE] ?: defaults.fontSizeSp,
            lineSpacingMultiplier = preferences[PreferencesKeys.LINE_SPACING]
                ?: defaults.lineSpacingMultiplier,
            horizontalPaddingDp = preferences[PreferencesKeys.HORIZONTAL_PADDING]
                ?: defaults.horizontalPaddingDp,
            fontFamily = preferences[PreferencesKeys.FONT_FAMILY] ?: defaults.fontFamily,
            theme = enumOrDefault(preferences[PreferencesKeys.THEME], defaults.theme),
            isBionicReadingEnabled = preferences[PreferencesKeys.BIONIC_READING]
                ?: defaults.isBionicReadingEnabled,
            isContinuousScroll = preferences[PreferencesKeys.CONTINUOUS_SCROLL]
                ?: defaults.isContinuousScroll,
            keepScreenOn = preferences[PreferencesKeys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            volumeKeyNavigation = preferences[PreferencesKeys.VOLUME_NAV]
                ?: defaults.volumeKeyNavigation,
            ttsSpeed = preferences[PreferencesKeys.TTS_SPEED] ?: defaults.ttsSpeed,
            showBookPagesInFooter = preferences[PreferencesKeys.FOOTER_BOOK_PAGES]
                ?: defaults.showBookPagesInFooter,
            textAlign = enumOrDefault(preferences[PreferencesKeys.TEXT_ALIGN], defaults.textAlign),
            hyphenation = preferences[PreferencesKeys.HYPHENATION] ?: defaults.hyphenation,
            firstLineIndentEm = preferences[PreferencesKeys.FIRST_LINE_INDENT]
                ?: defaults.firstLineIndentEm,
            paragraphSpacingDp = preferences[PreferencesKeys.PARAGRAPH_SPACING]
                ?: defaults.paragraphSpacingDp,
            pageTurnAnimation = enumOrDefault(
                preferences[PreferencesKeys.PAGE_TURN_ANIMATION],
                defaults.pageTurnAnimation
            ),
            themeMode = enumOrDefault(preferences[PreferencesKeys.THEME_MODE], defaults.themeMode),
            showTimeLeft = preferences[PreferencesKeys.SHOW_TIME_LEFT] ?: defaults.showTimeLeft,
            tapZonesInverted = preferences[PreferencesKeys.TAP_ZONES_INVERTED]
                ?: defaults.tapZonesInverted
        )
    }

    private fun writeSettings(preferences: MutablePreferences, settings: ReaderSettings) {
        preferences[PreferencesKeys.FONT_SIZE] = settings.fontSizeSp
        preferences[PreferencesKeys.LINE_SPACING] = settings.lineSpacingMultiplier
        preferences[PreferencesKeys.HORIZONTAL_PADDING] = settings.horizontalPaddingDp
        preferences[PreferencesKeys.FONT_FAMILY] = settings.fontFamily
        preferences[PreferencesKeys.THEME] = settings.theme.name
        preferences[PreferencesKeys.BIONIC_READING] = settings.isBionicReadingEnabled
        preferences[PreferencesKeys.CONTINUOUS_SCROLL] = settings.isContinuousScroll
        preferences[PreferencesKeys.KEEP_SCREEN_ON] = settings.keepScreenOn
        preferences[PreferencesKeys.VOLUME_NAV] = settings.volumeKeyNavigation
        preferences[PreferencesKeys.TTS_SPEED] = settings.ttsSpeed
        preferences[PreferencesKeys.FOOTER_BOOK_PAGES] = settings.showBookPagesInFooter
        preferences[PreferencesKeys.TEXT_ALIGN] = settings.textAlign.name
        preferences[PreferencesKeys.HYPHENATION] = settings.hyphenation
        preferences[PreferencesKeys.FIRST_LINE_INDENT] = settings.firstLineIndentEm
        preferences[PreferencesKeys.PARAGRAPH_SPACING] = settings.paragraphSpacingDp
        preferences[PreferencesKeys.PAGE_TURN_ANIMATION] = settings.pageTurnAnimation.name
        preferences[PreferencesKeys.THEME_MODE] = settings.themeMode.name
        preferences[PreferencesKeys.SHOW_TIME_LEFT] = settings.showTimeLeft
        preferences[PreferencesKeys.TAP_ZONES_INVERTED] = settings.tapZonesInverted
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        if (name == null) default else enumValues<T>().firstOrNull { it.name == name } ?: default
}
