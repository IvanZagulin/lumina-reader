package com.lumina.reader.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.theme.LuminaExtendedColors
import com.lumina.reader.ui.theme.OnestFamily
import kotlin.math.PI
import kotlin.math.sin

/** Plank geometry below the baseline (bottom of the covers), spec §4.1. */
object ShelfPlankMetrics {
    val TopFace: Dp = 6.dp
    val FrontFace: Dp = 12.dp
    val DropShadow: Dp = 20.dp

    /** Everything the plank occupies below the baseline. */
    val Total: Dp = TopFace + FrontFace + DropShadow
}

/**
 * A stylised wooden shelf drawn edge to edge behind a row (spec §4.1): a top
 * face with a highlight line, a front face with three sine grain hairlines
 * whose phase comes from [nameSeed], a soft drop shadow, and optionally an
 * engraved plate such as «ВЕДЬМАК · 3/7» centred on the front face.
 * [baseline] is the y of the bottom of the covers. Everything is built once
 * per size in `drawWithCache`; the plate is decorative (no semantics).
 */
fun Modifier.shelfPlank(
    colors: LuminaExtendedColors,
    baseline: Dp,
    nameSeed: String,
    plateText: String? = null,
    textMeasurer: TextMeasurer? = null
): Modifier = drawWithCache {
    val yb = baseline.toPx()
    val topH = ShelfPlankMetrics.TopFace.toPx()
    val frontH = ShelfPlankMetrics.FrontFace.toPx()
    val dropH = ShelfPlankMetrics.DropShadow.toPx()
    val frontTop = yb + topH
    val frontBottom = frontTop + frontH
    val width = size.width

    val topFace = Brush.verticalGradient(
        listOf(colors.plankTop, lerp(colors.plankTop, Color.Black, 0.06f)),
        startY = yb,
        endY = frontTop
    )
    val frontShade = Brush.verticalGradient(
        listOf(Color.Transparent, Color.Black.copy(alpha = 0.15f)),
        startY = frontTop,
        endY = frontBottom
    )
    val drop = Brush.verticalGradient(
        listOf(colors.plankShadow, Color.Transparent),
        startY = frontBottom,
        endY = frontBottom + dropH
    )

    val seed = nameSeed.hashCode()
    val amplitude = 0.6.dp.toPx()
    val step = 6.dp.toPx()
    val grain = Path()
    for (i in 0 until 3) {
        val y = frontTop + frontH * (i + 1) / 4f
        val phase = ((seed ushr (i * 8)) and 0xFF) / 255f * 2f * PI.toFloat()
        val wavelength = (90 + 30 * i).dp.toPx()
        var x = 0f
        grain.moveTo(0f, y + amplitude * sin(phase))
        while (x < width) {
            x += step
            grain.lineTo(x, y + amplitude * sin(phase + x / wavelength * 2f * PI.toFloat()))
        }
    }
    val grainStroke = Stroke(width = 1f)
    val grainColor = if (colors.isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)

    val plate = if (plateText != null && textMeasurer != null) {
        textMeasurer.measure(
            text = AnnotatedString(plateText.uppercase()),
            style = TextStyle(
                fontFamily = OnestFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = if (colors.isDark) Color(0xFFE2C27A).copy(alpha = 0.8f) else Color(0xFF4A2E14).copy(alpha = 0.85f)
            ),
            maxLines = 1
        ).takeIf { it.size.width < width - 40.dp.toPx() }
    } else {
        null
    }

    onDrawBehind {
        drawRect(topFace, topLeft = Offset(0f, yb), size = Size(width, topH))
        drawLine(colors.plankHighlight, Offset(0f, yb), Offset(width, yb), strokeWidth = 1.dp.toPx())
        drawRect(colors.plankFront, topLeft = Offset(0f, frontTop), size = Size(width, frontH))
        drawRect(frontShade, topLeft = Offset(0f, frontTop), size = Size(width, frontH))
        drawPath(grain, grainColor, style = grainStroke)
        drawRect(drop, topLeft = Offset(0f, frontBottom), size = Size(width, dropH))
        if (plate != null) {
            drawText(
                textLayoutResult = plate,
                topLeft = Offset(
                    (width - plate.size.width) / 2f,
                    frontTop + (frontH - plate.size.height) / 2f
                )
            )
        }
    }
}
