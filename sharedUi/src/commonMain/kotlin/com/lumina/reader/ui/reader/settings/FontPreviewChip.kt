package com.lumina.reader.ui.reader.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.ui.reader.ReaderFont
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.theme.LuminaShape

/**
 * A reading-font card (§4.2): 104×72dp with «Аб» set in the face and its
 * name. Faces load lazily when the card is first drawn.
 */
@Composable
fun FontPreviewChip(
    font: ReaderFont,
    selected: Boolean,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = LuminaShape.Tile,
        color = if (selected) colors.accent.copy(alpha = 0.08f) else colors.content.copy(alpha = 0.04f),
        contentColor = colors.content,
        border = if (selected) BorderStroke(2.dp, colors.accent) else BorderStroke(1.dp, colors.border),
        modifier = modifier
            .size(width = 104.dp, height = 72.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Аб",
                fontFamily = font.family,
                fontSize = 28.sp,
                color = colors.content,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = font.label,
                fontSize = 11.sp,
                color = if (selected) colors.accent else colors.muted,
                maxLines = 1
            )
        }
    }
}
