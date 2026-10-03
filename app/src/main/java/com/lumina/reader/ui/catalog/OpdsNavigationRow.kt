package com.lumina.reader.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.ui.theme.LuminaShape

private fun OpdsNavKind.icon(): ImageVector = when (this) {
    OpdsNavKind.AUTHOR -> Icons.Rounded.Person
    OpdsNavKind.SERIES -> Icons.Rounded.CollectionsBookmark
    OpdsNavKind.GENRE -> Icons.Rounded.Category
    OpdsNavKind.LETTER -> Icons.Rounded.SortByAlpha
    OpdsNavKind.OTHER -> Icons.Rounded.Folder
}

/** A folder of a feed (spec §7.9 «OpdsNavigationRow», 64dp). */
@Composable
internal fun OpdsNavigationRow(
    entry: OpdsEntry.Navigation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val kind = CatalogUi.navKind(entry)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = kind.icon(),
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant
        )
    }
}

/** A 48dp letter tile of an alphabet feed («А», «Б», «Ба»…). */
@Composable
internal fun LetterTile(
    entry: OpdsEntry.Navigation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = LuminaShape.Tile,
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "Буква ${entry.title}" }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = entry.title.trim(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
