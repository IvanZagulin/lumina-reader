package com.lumina.reader.ui.reader.chrome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LuminaMotion

/**
 * The cloth ribbon that hangs over a bookmarked page (§4.2): 16×30dp with a
 * 6dp V-notch. It drops in with a springy [LuminaMotion.ribbon] and lifts
 * out in 150ms. The progress is read only while drawing.
 */
@Composable
internal fun BookmarkRibbon(
    visible: Boolean,
    color: Color,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible, reducedMotion) {
        val target = if (visible) 1f else 0f
        when {
            reducedMotion -> progress.snapTo(target)
            visible -> progress.animateTo(target, LuminaMotion.ribbon())
            else -> progress.animateTo(target, tween(durationMillis = 150))
        }
    }
    Box(
        modifier = modifier
            .size(width = 16.dp, height = 30.dp)
            .clearAndSetSemantics { }
            .graphicsLayer {
                val p = progress.value
                translationY = -30.dp.toPx() * (1f - p)
                alpha = p.coerceIn(0f, 1f)
            }
            .drawWithCache {
                val notch = 6.dp.toPx()
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(size.width / 2f, size.height - notch)
                    lineTo(0f, size.height)
                    close()
                }
                val shade = Color.Black.copy(alpha = 0.12f)
                onDrawBehind {
                    drawPath(path, color)
                    // A faint fold line down the middle gives the cloth some depth.
                    clipPath(path) {
                        drawRect(
                            color = shade,
                            topLeft = Offset(size.width / 2f, 0f),
                            size = Size(size.width / 2f, size.height)
                        )
                    }
                }
            }
    )
}
