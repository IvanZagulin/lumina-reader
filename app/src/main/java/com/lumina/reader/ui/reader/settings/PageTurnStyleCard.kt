package com.lumina.reader.ui.reader.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.PageTurnAnimation
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.LuminaShape
import kotlin.math.sin

/** «Сдвиг», «3D-переворот», «Загиб», «Без анимации». */
internal fun pageTurnLabel(style: PageTurnAnimation): String = when (style) {
    PageTurnAnimation.SLIDE -> "Сдвиг"
    PageTurnAnimation.FLIP -> "3D-переворот"
    PageTurnAnimation.CURL -> "Загиб"
    PageTurnAnimation.NONE -> "Без анимации"
}

/**
 * A page-turn style card (§4.2), 96×80dp. The selected card loops a small
 * 1.2s demo (an infinite transition that exists only while it is selected
 * and motion is allowed). The curl carries a «бета» tag and is disabled
 * where it is not supported.
 */
@Composable
internal fun PageTurnStyleCard(
    style: PageTurnAnimation,
    selected: Boolean,
    enabled: Boolean,
    colors: ReaderChromeColors,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animate = selected && enabled && !reducedMotion
    val progress: State<Float>? = if (animate) {
        val transition = rememberInfiniteTransition(label = "pageTurnDemo")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "pageTurnDemoProgress"
        )
    } else {
        null
    }
    val contentAlpha = if (enabled) 1f else 0.38f
    Surface(
        shape = LuminaShape.Tile,
        color = if (selected) colors.accent.copy(alpha = 0.08f) else colors.content.copy(alpha = 0.04f),
        contentColor = colors.content,
        border = if (selected) BorderStroke(2.dp, colors.accent) else BorderStroke(1.dp, colors.border),
        modifier = modifier
            .size(width = 96.dp, height = 80.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { if (!enabled) stateDescription = "Недоступно на этом устройстве" }
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Canvas(modifier = Modifier.size(width = 52.dp, height = 40.dp)) {
                    val t = progress?.value ?: 0.45f
                    drawPageTurnDemo(
                        style = style,
                        t = t,
                        page = colors.page,
                        ink = colors.content.copy(alpha = 0.55f * contentAlpha),
                        accent = colors.accent.copy(alpha = contentAlpha)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = pageTurnLabel(style),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = (if (selected) colors.accent else colors.content).copy(alpha = contentAlpha),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            if (style == PageTurnAnimation.CURL) {
                Surface(
                    color = colors.accent,
                    contentColor = colors.onAccent,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = "бета",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/** Tiny looping illustration of a page-turn style at time [t] (0..1). */
private fun DrawScope.drawPageTurnDemo(style: PageTurnAnimation, t: Float, page: Color, ink: Color, accent: Color) {
    val w = size.width * 0.62f
    val h = size.height
    val left = (size.width - w) / 2f
    val radius = CornerRadius(3.dp.toPx())
    val stroke = Stroke(width = 1.dp.toPx())
    // Ease in and out over the loop: 0 → 1 → hold.
    val phase = (sin((t * 2f - 0.5f) * Math.PI).toFloat() + 1f) / 2f
    fun pageAt(x: Float, fill: Color) {
        drawRoundRect(fill, Offset(x, 0f), Size(w, h), radius)
        drawRoundRect(ink, Offset(x, 0f), Size(w, h), radius, style = stroke)
        for (i in 0 until 4) {
            val y = h * (0.22f + i * 0.17f)
            drawLine(ink, Offset(x + w * 0.15f, y), Offset(x + w * 0.85f, y), 1.dp.toPx())
        }
    }
    when (style) {
        PageTurnAnimation.SLIDE -> {
            pageAt(left + w * 0.3f * (1f - phase), page)
            pageAt(left - (w + left) * phase, page)
        }
        PageTurnAnimation.FLIP -> {
            pageAt(left, page)
            withTransform({ scale(scaleX = 1f - phase, scaleY = 1f, pivot = Offset(left, h / 2f)) }) {
                drawRoundRect(page, Offset(left, 0f), Size(w, h), radius)
                drawRoundRect(accent, Offset(left, 0f), Size(w, h), radius, style = stroke)
            }
        }
        PageTurnAnimation.CURL -> {
            pageAt(left, page)
            val fold = w * 0.15f + w * 0.55f * phase
            val path = Path().apply {
                moveTo(left + w - fold, h)
                lineTo(left + w, h - fold)
                lineTo(left + w - fold, h - fold)
                close()
            }
            drawPath(path, accent.copy(alpha = 0.35f))
            drawPath(path, accent, style = stroke)
        }
        PageTurnAnimation.NONE -> pageAt(left, page)
    }
}
