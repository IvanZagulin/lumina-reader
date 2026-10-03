@file:OptIn(ExperimentalTextApi::class)

package com.lumina.reader.ui.reader

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.lumina.reader.R
import com.lumina.reader.core.model.ReaderFontIds

/** A reading font of the catalogue; [id] is stored in ReaderSettings.fontFamily. */
@Immutable
data class ReaderFont(
    val id: String,
    val label: String,
    val family: FontFamily
)

/**
 * The reading-font catalogue (design spec §1.4). The families are created
 * once, so equal ids always give the same [FontFamily] instance: the
 * paginator and the pages resolve exactly the same typefaces. Resource fonts
 * load lazily, the first time a face is measured or drawn.
 */
object ReaderFonts {
    /** Optical size the variable Literata is drawn at: tuned for body text. */
    private const val LITERATA_OPTICAL_SIZE = 12f

    private fun literata(weight: FontWeight, style: FontStyle): Font = Font(
        resId = if (style == FontStyle.Italic) R.font.literata_italic_variable else R.font.literata_variable,
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.Setting("opsz", LITERATA_OPTICAL_SIZE)
        )
    )

    private val uprightWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
    private val italicWeights = listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)

    private val LiterataFamily: FontFamily = FontFamily(
        uprightWeights.map { literata(it, FontStyle.Normal) } +
            italicWeights.map { literata(it, FontStyle.Italic) }
    )

    private val PtSerifFamily: FontFamily = FontFamily(
        Font(R.font.ptserif_regular, FontWeight.Normal),
        Font(R.font.ptserif_bold, FontWeight.Bold),
        Font(R.font.ptserif_italic, FontWeight.Normal, FontStyle.Italic)
    )

    // Golos Text has no italic; the platform slants the upright face.
    private val GolosFamily: FontFamily = FontFamily(
        uprightWeights.map { Font(R.font.golostext_variable, it) }
    )

    val Literata = ReaderFont(ReaderFontIds.LITERATA, "Литерата", LiterataFamily)
    val PtSerif = ReaderFont(ReaderFontIds.PT_SERIF, "PT Serif", PtSerifFamily)
    val Golos = ReaderFont(ReaderFontIds.GOLOS, "Голос", GolosFamily)
    val SystemSerif = ReaderFont(ReaderFontIds.SYSTEM_SERIF, "Системный", FontFamily.Serif)
    val SystemSans = ReaderFont(ReaderFontIds.SYSTEM_SANS, "Без засечек", FontFamily.SansSerif)
    val Mono = ReaderFont(ReaderFontIds.MONO, "Моно", FontFamily.Monospace)

    /** In the order of the settings sheet. */
    val all: List<ReaderFont> = listOf(Literata, PtSerif, Golos, SystemSerif, SystemSans, Mono)

    /** The font for a stored id; legacy names ("Serif", "SansSerif", ...) are migrated. */
    fun byId(id: String?): ReaderFont {
        val migrated = ReaderFontIds.migrate(id)
        return all.firstOrNull { it.id == migrated } ?: Literata
    }
}

/**
 * The single place that turns a stored font id into a [FontFamily]. Every
 * reader view and the paginator must use it so measured and drawn text match.
 */
fun readerFontFamily(name: String): FontFamily = ReaderFonts.byId(name).family
