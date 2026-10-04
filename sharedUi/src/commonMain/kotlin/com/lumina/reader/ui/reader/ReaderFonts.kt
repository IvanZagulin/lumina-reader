@file:OptIn(ExperimentalTextApi::class)

package com.lumina.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.lumina.reader.core.model.ReaderFontIds
import com.lumina.reader.sharedui.resources.Res
import com.lumina.reader.sharedui.resources.golostext_variable
import com.lumina.reader.sharedui.resources.literata_italic_variable
import com.lumina.reader.sharedui.resources.literata_variable
import com.lumina.reader.sharedui.resources.ptserif_bold
import com.lumina.reader.sharedui.resources.ptserif_italic
import com.lumina.reader.sharedui.resources.ptserif_regular
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.Font as ResourceFont

/** A reading font of the catalogue; [id] is stored in ReaderSettings.fontFamily. */
@Immutable
data class ReaderFont(
    val id: String,
    val label: String,
    val family: FontFamily
)

/**
 * The reading-font catalogue (design spec §1.4).
 *
 * The bundled faces are Compose Multiplatform resources (composeResources/font),
 * so Android and iPhone read the same files, and loading one is a composable
 * call: the catalogue is built in composition ([rememberReaderFonts]) and
 * reached through [ReaderFonts]. Code that is not composable — the paginator
 * and the paragraph styles — gets its family from [ReaderTypography], which is
 * resolved from the catalogue once, so measured and drawn text always use the
 * same typeface.
 */
@Immutable
class ReaderFontCatalog(literata: FontFamily, ptSerif: FontFamily, golos: FontFamily) {
    val Literata = ReaderFont(ReaderFontIds.LITERATA, "Литерата", literata)
    val PtSerif = ReaderFont(ReaderFontIds.PT_SERIF, "PT Serif", ptSerif)
    val Golos = ReaderFont(ReaderFontIds.GOLOS, "Голос", golos)
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

/** Provided by a screen that wants one catalogue for its whole tree; null otherwise. */
val LocalReaderFonts = staticCompositionLocalOf<ReaderFontCatalog?> { null }

/** The reading fonts of this composition. */
val ReaderFonts: ReaderFontCatalog
    @Composable get() = LocalReaderFonts.current ?: rememberReaderFonts()

/** Optical size the variable Literata is drawn at: tuned for body text. */
private const val LITERATA_OPTICAL_SIZE = 12f

private val uprightWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
private val italicWeights = listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold)

/**
 * Loads the bundled reading faces. Resource fonts are equal from one
 * composition to the next, so the remembered families keep their identity
 * and the paginator's cached measurements stay valid.
 */
@Composable
fun rememberReaderFonts(): ReaderFontCatalog {
    val literataFonts = uprightWeights.map { literata(Res.font.literata_variable, it, FontStyle.Normal) } +
        italicWeights.map { literata(Res.font.literata_italic_variable, it, FontStyle.Italic) }
    val ptSerifFonts = listOf(
        ResourceFont(Res.font.ptserif_regular, FontWeight.Normal),
        ResourceFont(Res.font.ptserif_bold, FontWeight.Bold),
        ResourceFont(Res.font.ptserif_italic, FontWeight.Normal, FontStyle.Italic)
    )
    // Golos Text has no italic; the platform slants the upright face.
    val golosFonts = uprightWeights.map { variableWeight(Res.font.golostext_variable, it) }
    return remember(literataFonts, ptSerifFonts, golosFonts) {
        ReaderFontCatalog(
            literata = FontFamily(literataFonts),
            ptSerif = FontFamily(ptSerifFonts),
            golos = FontFamily(golosFonts)
        )
    }
}

/**
 * One instance of the variable Literata. Weight and optical size are set
 * explicitly: Android's Font(R.font.x, weight) applied `wght` by itself, but on
 * iOS a resource font without settings loads the file's default instance.
 */
@Composable
private fun literata(resource: FontResource, weight: FontWeight, style: FontStyle): Font =
    ResourceFont(
        resource,
        weight,
        style,
        FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.Setting("opsz", LITERATA_OPTICAL_SIZE)
        )
    )

/** One weight of a variable font with `wght` set explicitly (see [literata]). */
@Composable
private fun variableWeight(resource: FontResource, weight: FontWeight): Font =
    ResourceFont(resource, weight, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(weight.weight)))
