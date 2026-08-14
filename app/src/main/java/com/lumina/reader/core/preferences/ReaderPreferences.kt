package com.lumina.reader.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReadingTheme
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
    }

    val settingsFlow: Flow<ReaderSettings> = context.dataStore.data.map { preferences ->
        val fontSize = preferences[PreferencesKeys.FONT_SIZE] ?: 18
        val lineSpacing = preferences[PreferencesKeys.LINE_SPACING] ?: 1.45f
        val padding = preferences[PreferencesKeys.HORIZONTAL_PADDING] ?: 20
        val fontFamily = preferences[PreferencesKeys.FONT_FAMILY] ?: "Serif"
        val themeStr = preferences[PreferencesKeys.THEME] ?: ReadingTheme.OLED_BLACK.name
        val theme = try {
            ReadingTheme.valueOf(themeStr)
        } catch (e: Exception) {
            ReadingTheme.OLED_BLACK
        }
        val bionic = preferences[PreferencesKeys.BIONIC_READING] ?: false
        val continuous = preferences[PreferencesKeys.CONTINUOUS_SCROLL] ?: false
        val screenOn = preferences[PreferencesKeys.KEEP_SCREEN_ON] ?: true
        val volumeNav = preferences[PreferencesKeys.VOLUME_NAV] ?: true
        val ttsSpeed = preferences[PreferencesKeys.TTS_SPEED] ?: 1.0f

        ReaderSettings(
            fontSizeSp = fontSize,
            lineSpacingMultiplier = lineSpacing,
            horizontalPaddingDp = padding,
            fontFamily = fontFamily,
            theme = theme,
            isBionicReadingEnabled = bionic,
            isContinuousScroll = continuous,
            keepScreenOn = screenOn,
            volumeKeyNavigation = volumeNav,
            ttsSpeed = ttsSpeed
        )
    }

    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        context.dataStore.edit { preferences ->
            val current = ReaderSettings(
                fontSizeSp = preferences[PreferencesKeys.FONT_SIZE] ?: 18,
                lineSpacingMultiplier = preferences[PreferencesKeys.LINE_SPACING] ?: 1.45f,
                horizontalPaddingDp = preferences[PreferencesKeys.HORIZONTAL_PADDING] ?: 20,
                fontFamily = preferences[PreferencesKeys.FONT_FAMILY] ?: "Serif",
                theme = try {
                    ReadingTheme.valueOf(preferences[PreferencesKeys.THEME] ?: ReadingTheme.OLED_BLACK.name)
                } catch (e: Exception) {
                    ReadingTheme.OLED_BLACK
                },
                isBionicReadingEnabled = preferences[PreferencesKeys.BIONIC_READING] ?: false,
                isContinuousScroll = preferences[PreferencesKeys.CONTINUOUS_SCROLL] ?: false,
                keepScreenOn = preferences[PreferencesKeys.KEEP_SCREEN_ON] ?: true,
                volumeKeyNavigation = preferences[PreferencesKeys.VOLUME_NAV] ?: true,
                ttsSpeed = preferences[PreferencesKeys.TTS_SPEED] ?: 1.0f
            )
            val updated = transform(current)
            preferences[PreferencesKeys.FONT_SIZE] = updated.fontSizeSp
            preferences[PreferencesKeys.LINE_SPACING] = updated.lineSpacingMultiplier
            preferences[PreferencesKeys.HORIZONTAL_PADDING] = updated.horizontalPaddingDp
            preferences[PreferencesKeys.FONT_FAMILY] = updated.fontFamily
            preferences[PreferencesKeys.THEME] = updated.theme.name
            preferences[PreferencesKeys.BIONIC_READING] = updated.isBionicReadingEnabled
            preferences[PreferencesKeys.CONTINUOUS_SCROLL] = updated.isContinuousScroll
            preferences[PreferencesKeys.KEEP_SCREEN_ON] = updated.keepScreenOn
            preferences[PreferencesKeys.VOLUME_NAV] = updated.volumeKeyNavigation
            preferences[PreferencesKeys.TTS_SPEED] = updated.ttsSpeed
        }
    }
}
