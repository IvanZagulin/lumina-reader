package com.lumina.reader.ui.reader.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.LuminaShape

/**
 * «↩ Вернуться на 42,3 %»: an inverse pill shown for a few seconds after a
 * jump (scrubber, contents, search), leading back to where the reader was.
 */
@Composable
internal fun ReturnToPageChip(
    label: String,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ReaderInverseCapsule(colors = colors, modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(LuminaShape.Pill)
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 36.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = null,
                tint = colors.inverseContent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.inverseContent,
                maxLines = 1
            )
        }
    }
}
