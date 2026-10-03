package com.lumina.reader.platform

import android.util.Log
import java.util.Locale

actual object LuminaLog {
    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    actual fun w(tag: String, message: String, error: Throwable?) {
        if (error == null) Log.w(tag, message) else Log.w(tag, message, error)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        if (error == null) Log.e(tag, message) else Log.e(tag, message, error)
    }
}

actual object AppInfo {
    @Volatile
    private var version: String = ""

    /** Called once by the Android application with BuildConfig.VERSION_NAME. */
    fun init(versionName: String) {
        version = versionName
    }

    actual val versionName: String
        get() = version

    actual val platform: PlatformKind = PlatformKind.ANDROID
}

/**
 * The java.util.Locale for this tag (what TextToSpeech and formatters take).
 * The tag of the system default gives back Locale.getDefault() itself, so the
 * TTS fallback hands the engine exactly the Locale it got before LanguageTag
 * (a locale whose variant is not valid BCP 47 would not survive
 * toLanguageTag/forLanguageTag unchanged).
 */
fun LanguageTag.toLocale(): Locale {
    val default = Locale.getDefault()
    if (bcp47 == default.toLanguageTag()) return default
    return Locale.forLanguageTag(bcp47)
}

actual fun systemLanguageTag(): LanguageTag = LanguageTag(Locale.getDefault().toLanguageTag())

actual fun LanguageTag.displayLanguage(inLanguage: LanguageTag): String {
    val locale = toLocale()
    return locale.getDisplayLanguage(inLanguage.toLocale()).ifBlank { locale.language }
}
