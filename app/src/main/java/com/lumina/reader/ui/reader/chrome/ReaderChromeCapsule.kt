package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaShape

/**
 * Floating surface of the reader chrome: the theme surface at 94 %, a
 * hairline, and a soft shadow on light themes only (none on dark pages,
 * where it would read as a smudge).
 */
@Composable
fun ReaderChromeCapsule(
    colors: ReaderChromeColors,
    modifier: Modifier = Modifier,
    shape: Shape = LuminaShape.Pill,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.bg,
        contentColor = colors.content,
        border = BorderStroke(LuminaDimens.Hairline, colors.border),
        shadowElevation = if (colors.isDark) 0.dp else 6.dp,
        content = content
    )
}

/** The dark-on-light (or light-on-dark) pill used by chips and the selection menu. */
@Composable
fun ReaderInverseCapsule(
    colors: ReaderChromeColors,
    modifier: Modifier = Modifier,
    shape: Shape = LuminaShape.Pill,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.inverseBg,
        contentColor = colors.inverseContent,
        shadowElevation = if (colors.isDark) 0.dp else 8.dp,
        content = content
    )
}
