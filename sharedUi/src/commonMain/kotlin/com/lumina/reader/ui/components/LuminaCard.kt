package com.lumina.reader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LuminaShape

/**
 * The flat card of the app (spec §1.7, §4.1): surface colour, radius 20, a 1 dp
 * `outlineVariant` hairline and no elevation. Clickable when [onClick] is set.
 */
@Composable
fun LuminaCard(
    modifier: Modifier = Modifier,
    shape: Shape = LuminaShape.Card,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = color,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(content = content)
        }
    }
}
