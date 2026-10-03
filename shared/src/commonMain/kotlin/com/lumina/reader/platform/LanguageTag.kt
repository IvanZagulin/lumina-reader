package com.lumina.reader.platform

import kotlin.jvm.JvmInline

/**
 * A language as an IETF BCP 47 tag ("ru-RU", "en"); replaces java.util.Locale
 * in common code. Android converts it with Locale.forLanguageTag.
 */
@JvmInline
value class LanguageTag(val bcp47: String) {
    override fun toString(): String = bcp47
}

/** The user's preferred language, like `Locale.getDefault()`. */
expect fun systemLanguageTag(): LanguageTag

/**
 * Name of this tag's language written in [inLanguage] ("английский" for "en"
 * in Russian), or the bare language code when the platform has no name for it.
 * Android: `Locale.getDisplayLanguage(Locale)`.
 */
expect fun LanguageTag.displayLanguage(inLanguage: LanguageTag): String
