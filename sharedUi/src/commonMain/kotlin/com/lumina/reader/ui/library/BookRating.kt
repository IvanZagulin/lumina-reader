package com.lumina.reader.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The highest mark a book can get; the server accepts 0..5 and the circle shows it. */
const val MAX_BOOK_RATING: Int = 5

/**
 * The reader's own mark out of five.
 *
 * Tapping the star that is already the mark clears it, because a rating given
 * by mistake would otherwise be impossible to take back - there is nowhere
 * else in the app to say "actually, no opinion".
 */
@Composable
fun StarRating(
    rating: Int,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    starSize: Dp = 32.dp
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..MAX_BOOK_RATING) {
            val filled = star <= rating
            IconButton(
                onClick = { onRate(if (rating == star) 0 else star) },
                enabled = enabled,
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = if (filled) "Оценка $star из $MAX_BOOK_RATING, снять" else "Поставить $star из $MAX_BOOK_RATING"
                }
            ) {
                Icon(
                    imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = null,
                    tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(starSize)
                )
            }
        }
    }
}

/**
 * Asked right after a book is finished, which is the moment an opinion is
 * sharpest. A mark closes the dialog at once - there is nothing to confirm -
 * and «Не сейчас» leaves the book unrated rather than nagging again later:
 * the mark can still be given from the book's own sheet.
 */
@Composable
fun RateBookDialog(
    title: String,
    initialRating: Int,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Как вам книга?") },
        text = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StarRating(rating = initialRating, onRate = onRate)
                Text(
                    text = "Оценку увидят те, с кем вы делитесь полкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Не сейчас") } }
    )
}
