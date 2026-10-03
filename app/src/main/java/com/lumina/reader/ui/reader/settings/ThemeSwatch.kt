package com.lumina.reader.ui.reader.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.ReadingTheme
import com.lumina.reader.ui.reader.ReaderFonts
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.LuminaShape

/**
 * A reading-theme tile (§4.2): 56×72dp in the theme's page colour with
 * «Аа» and three hairline "lines of text". For «Авто» [nightTheme] is given
 * and the tile is split diagonally into the day and night themes.
 */
@Composable
internal fun ThemeSwatch(
    label: String,
    theme: ReadingTheme,
    selected: Boolean,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nightTheme: ReadingTheme? = null,
    reducedMotion: Boolean = false
) {
    val scale by animateFloatAsState(
        targetValue = if (selected && !reducedMotion) 1.05f else 1f,
        animationSpec = LuminaMotion.snappy(),
        label = "swatchScale"
    )
    val ring by animateColorAsState(
        targetValue = if (selected) colors.accent else Color.Transparent,
        animationSpec = tween(150),
        label = "swatchRing"
    )
    Column(
        modifier = modifier
            .width(64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .border(2.dp, ring, RoundedCornerShape(17.dp))
                .padding(3.dp)
                .size(width = 56.dp, height = 72.dp)
                .clip(LuminaShape.Tile)
                .drawWithCache {
                    val night = nightTheme?.let {
                        Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                    }
                    val lineColor = theme.textComposeColor.copy(alpha = 0.22f)
                    val nightLine = nightTheme?.textComposeColor?.copy(alpha = 0.3f) ?: lineColor
                    val left = 9.dp.toPx()
                    val lineHeight = 1.5.dp.toPx()
                    val top = size.height - 22.dp.toPx()
                    val step = 6.dp.toPx()
                    val lineWidth = size.width - 2 * left
                    onDrawBehind {
                        drawRect(theme.bgComposeColor)
                        if (night != null && nightTheme != null) drawPath(night, nightTheme.bgComposeColor)
                        for (i in LineWidths.indices) {
                            drawRect(
                                color = if (i == 2) nightLine else lineColor,
                                topLeft = Offset(left, top + i * step),
                                size = Size(lineWidth * LineWidths[i], lineHeight)
                            )
                        }
                    }
                }
                .border(1.dp, colors.content.copy(alpha = 0.12f), LuminaShape.Tile),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = "Аа",
                fontFamily = ReaderFonts.Literata.family,
                fontSize = 18.sp,
                color = theme.textComposeColor,
                modifier = Modifier.padding(top = 12.dp)
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.onAccent,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (selected) colors.content else colors.muted,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private val LineWidths = floatArrayOf(0.70f, 0.82f, 0.56f)

/** Horizontal gap between theme tiles. */
internal val ThemeSwatchSpacing = Arrangement.spacedBy(6.dp)
