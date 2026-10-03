package com.lumina.reader.ui.reader

import androidx.compose.ui.text.font.FontFamily

/** A font the reader offers, stored in [com.lumina.reader.core.model.ReaderSettings.fontFamily]. */
data class ReaderFontOption(
    val name: String,
    val label: String
)

/** Fonts shown in the reader settings, in display order. */
val readerFontOptions: List<ReaderFontOption> = listOf(
    ReaderFontOption("Serif", "Книжный (Serif)"),
    ReaderFontOption("SansSerif", "Гротеск (Sans)"),
    ReaderFontOption("Monospace", "Моноширинный")
)

/**
 * The single place that turns a stored font name into a [FontFamily]. Every
 * reader view and the paginator must use it so measured and drawn text match.
 * Unknown names, including the retired "Cursive", fall back to serif.
 */
fun readerFontFamily(name: String): FontFamily = when (name) {
    "SansSerif" -> FontFamily.SansSerif
    "Monospace" -> FontFamily.Monospace
    else -> FontFamily.Serif
}
