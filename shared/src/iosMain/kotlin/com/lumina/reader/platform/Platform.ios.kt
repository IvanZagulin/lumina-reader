package com.lumina.reader.platform

// Star import: some NSLocale/NSBundle members come from Objective-C categories,
// which Kotlin/Native exposes either as members or as extensions.
import platform.Foundation.*

actual object LuminaLog {
    actual fun d(tag: String, message: String) {
        log("D", tag, message, null)
    }

    actual fun w(tag: String, message: String, error: Throwable?) {
        log("W", tag, message, error)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        log("E", tag, message, error)
    }

    private fun log(level: String, tag: String, message: String, error: Throwable?) {
        val suffix = if (error == null) "" else ": " + error.toString()
        // The text goes in as an argument, never as the format string.
        NSLog("%@", "$level/$tag: $message$suffix")
    }
}

actual object AppInfo {
    actual val versionName: String by lazy {
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
    }

    actual val platform: PlatformKind = PlatformKind.IOS
}

actual fun systemLanguageTag(): LanguageTag {
    val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
    return LanguageTag(preferred?.takeIf { it.isNotBlank() } ?: "en-US")
}

actual fun LanguageTag.displayLanguage(inLanguage: LanguageTag): String {
    val code = bcp47.substringBefore('-').substringBefore('_').lowercase()
    val name = NSLocale(localeIdentifier = inLanguage.bcp47).localizedStringForLanguageCode(code)
    return name?.takeIf { it.isNotBlank() } ?: code
}
