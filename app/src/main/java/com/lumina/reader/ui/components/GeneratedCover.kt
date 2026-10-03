package com.lumina.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LoraFamily
import com.lumina.reader.ui.theme.OnestFamily
import com.lumina.reader.ui.theme.LuminaExtendedColors

/**
 * One bookcloth of the generated-cover palette (spec §4.1). [ink] is foil
 * (`#F0D69A`, ≥ 5.2:1) on dark cloths and debossed brown on Охра.
 */
@Immutable
data class Cloth(val name: String, val color: Color, val ink: Color, val isFoil: Boolean = true)

/** Eight cloth colours; the title and author pick one deterministically. */
object ClothPalette {
    private val Foil = Color(0xFFF0D69A)
    private val Debossed = Color(0xFF2A1A0C)

    val all: List<Cloth> = listOf(
        Cloth("Бордо", Color(0xFF6E1F2A), Foil),
        Cloth("Изумруд", Color(0xFF1F4D3A), Foil),
        Cloth("Индиго", Color(0xFF23305E), Foil),
        Cloth("Охра", Color(0xFFC08A3A), Debossed, isFoil = false),
        Cloth("Слива", Color(0xFF4E2A4F), Foil),
        Cloth("Бирюза", Color(0xFF1E5A63), Foil),
        Cloth("Графит", Color(0xFF2E2F33), Foil),
        Cloth("Терракота", Color(0xFF8C3F25), Foil),
    )

    /** `("${title.lowercase()}|${author.lowercase()}").hashCode().mod(8)`. */
    fun indexFor(title: String, author: String): Int =
        ("${title.lowercase()}|${author.lowercase()}").hashCode().mod(all.size)

    fun forBook(title: String, author: String): Cloth = all[indexFor(title, author)]
}

/**
 * Title size in dp for a cover [widthDp] wide (spec §4.1): by length at 96 dp
 * (≤12 → 15, ≤24 → 13, ≤40 → 11.5, else 10.5), scaled by `width / 96`.
 */
fun generatedTitleSizeDp(title: String, widthDp: Float): Float {
    val base = when (title.trim().length) {
        in 0..12 -> 15f
        in 13..24 -> 13f
        in 25..40 -> 11.5f
        else -> 10.5f
    }
    return base * widthDp / 96f
}

/**
 * A printed cloth cover for books without an image: cloth, diagonal
 * hairlines, paper grain, a double foil frame, the title in Lora, a diamond
 * ornament and the author in caps. Text sizes come from dp so the "printed"
 * cover does not follow the system font scale (it is image-like).
 */
@Composable
fun GeneratedCover(
    title: String,
    author: String,
    widthDp: Dp,
    modifier: Modifier = Modifier
) {
    val cloth = remember(title, author) { ClothPalette.forBook(title, author) }
    val density = LocalDensity.current
    val w = widthDp.value.coerceAtLeast(24f)
    val titleStyle = remember(title, w, density, cloth) {
        with(density) {
            val size = generatedTitleSizeDp(title, w).dp
            TextStyle(
                fontFamily = LoraFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = size.toSp(),
                lineHeight = (size * 1.22f).toSp(),
                color = cloth.ink,
                textAlign = TextAlign.Center
            )
        }
    }
    val authorStyle = remember(w, density, cloth) {
        with(density) {
            val size = (7.5f * w / 96f).dp
            TextStyle(
                fontFamily = OnestFamily,
                fontWeight = FontWeight.Medium,
                fontSize = size.toSp(),
                lineHeight = (size * 1.3f).toSp(),
                letterSpacing = (0.8f * w / 96f).dp.toSp(),
                color = cloth.ink.copy(alpha = 0.92f),
                textAlign = TextAlign.Center
            )
        }
    }
    val grain = rememberPaperGrain()
    val inset = (w * 0.07f).dp

    Box(
        modifier = modifier
            .clearAndSetSemantics { }
            .drawWithCache {
                val hairline = Color.White.copy(alpha = 0.04f)
                val step = 3.dp.toPx()
                val frameInset = size.width * 0.07f
                val foil = Brush.verticalGradient(
                    listOf(LuminaExtendedColors.Light.foilLight, LuminaExtendedColors.Light.foilDark)
                )
                val frameColor = if (cloth.isFoil) {
                    foil
                } else {
                    Brush.verticalGradient(listOf(cloth.ink.copy(alpha = 0.7f), cloth.ink.copy(alpha = 0.9f)))
                }
                val outer = 1.dp.toPx()
                val innerW = 0.5.dp.toPx()
                val gap = 2.dp.toPx()
                val hairlines = Path().apply {
                    var x = -size.height
                    while (x < size.width) {
                        moveTo(x, size.height)
                        lineTo(x + size.height, 0f)
                        x += step
                    }
                }
                val hairlineStroke = Stroke(width = 1f)
                val grainBrush = grain?.let { paperGrainBrush(it) }
                onDrawBehind {
                    drawRect(cloth.color)
                    drawPath(hairlines, hairline, style = hairlineStroke)
                    if (grainBrush != null) drawRect(grainBrush)
                    drawRect(
                        frameColor,
                        topLeft = Offset(frameInset, frameInset),
                        size = Size(size.width - 2 * frameInset, size.height - 2 * frameInset),
                        style = Stroke(outer)
                    )
                    val i2 = frameInset + gap
                    drawRect(
                        frameColor,
                        topLeft = Offset(i2, i2),
                        size = Size(size.width - 2 * i2, size.height - 2 * i2),
                        style = Stroke(innerW)
                    )
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = inset + 6.dp, vertical = inset + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.weight(0.6f))
            Text(
                text = title,
                style = titleStyle,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().wrapContentHeight()
            )
            Spacer(Modifier.height((w * 0.06f).dp))
            // A 4 dp diamond ornament (Lora has no ✦).
            val ornament = if (cloth.isFoil) LuminaExtendedColors.Light.foilLight else cloth.ink
            Spacer(
                modifier = Modifier
                    .size(4.dp)
                    .drawBehind {
                        val path = Path().apply {
                            moveTo(size.width / 2f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(size.width / 2f, size.height)
                            lineTo(0f, size.height / 2f)
                            close()
                        }
                        drawPath(path, ornament)
                    }
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height((w * 0.06f).dp))
            Text(
                text = author.uppercase(),
                style = authorStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The inside of a cover, seen when the transition swings it open: pale paper
 * tinted 20% with the cloth, a soft glow, grain and an «Ex libris · Lumina»
 * stamp. Drawn mirrored by default because it is the back face of a cover
 * rotated past 90°.
 */
@Composable
fun Endpaper(
    cloth: Color,
    modifier: Modifier = Modifier,
    mirrored: Boolean = true
) {
    val density = LocalDensity.current
    val grain = rememberPaperGrain()
    val small = remember(density, cloth) {
        with(density) {
            TextStyle(
                fontFamily = OnestFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 8.dp.toSp(),
                letterSpacing = 1.dp.toSp(),
                color = cloth.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
    val big = remember(density, cloth) {
        with(density) {
            TextStyle(
                fontFamily = LoraFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.dp.toSp(),
                color = lerp(Color(0xFF2A211B), cloth, 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer { if (mirrored) scaleX = -1f }
            .clearAndSetSemantics { }
            .drawWithCache {
                val paper = lerp(Color(0xFFEFE6D6), cloth, 0.2f)
                val glow = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.4f),
                    radius = size.maxDimension * 0.7f
                )
                val grainBrush = grain?.let { paperGrainBrush(it) }
                onDrawBehind {
                    drawRect(paper)
                    drawRect(glow)
                    if (grainBrush != null) drawRect(grainBrush)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "EX LIBRIS", style = small)
            Text(text = "Lumina", style = big)
        }
    }
}
