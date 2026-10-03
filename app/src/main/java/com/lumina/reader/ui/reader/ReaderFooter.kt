package com.lumina.reader.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.ReaderSettings

/**
 * Footer under every page. Tapping the book position switches between the
 * percentage and book-wide page numbers; a long press opens the jump dialog.
 * The right side shows the estimated time left in the chapter.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReaderProgressFooter(
    chapterLabel: String?,
    position: BookPosition,
    showPages: Boolean,
    settings: ReaderSettings,
    minutesLeft: Int?,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val secondary = settings.theme.secondaryTextComposeColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Equal weights on both sides keep the position exactly centred.
        Text(
            text = chapterLabel.orEmpty(),
            fontSize = 11.sp,
            color = secondary.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (showPages) {
                val approximate = if (position.isExact) "" else "≈ "
                "${approximate}${position.pageNumber} из ${position.totalPages}"
            } else {
                formatBookPercent(position.percent)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = secondary.copy(alpha = 0.85f),
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClickLabel = if (showPages) "Показать процент" else "Показать страницы книги",
                    onLongClickLabel = "Перейти к месту в книге",
                    onLongClick = onLongPress,
                    onClick = onToggle
                )
                .padding(horizontal = 12.dp, vertical = 5.dp)
        )
        Text(
            text = if (settings.showTimeLeft) {
                formatTimeLeft(minutesLeft)?.let { "≈ $it" }.orEmpty()
            } else {
                ""
            },
            fontSize = 11.sp,
            color = secondary.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Jump to a book-wide page number, or to a percentage when [byPages] is false. */
@Composable
internal fun BookJumpDialog(
    position: BookPosition,
    byPages: Boolean,
    onDismiss: () -> Unit,
    onJump: (globalPageIndex: Int) -> Unit
) {
    val totalPages = position.totalPages.coerceAtLeast(1)
    var input by remember(byPages) {
        mutableStateOf(
            if (byPages) position.pageNumber.toString()
            else position.percent.toInt().toString()
        )
    }
    val targetPageIndex: Int? = if (byPages) {
        input.toIntOrNull()?.takeIf { it in 1..totalPages }?.minus(1)
    } else {
        input.replace(',', '.').toFloatOrNull()
            ?.takeIf { it in 0f..100f }
            ?.let { percent ->
                (kotlin.math.ceil(percent / 100.0 * totalPages).toInt() - 1)
                    .coerceIn(0, totalPages - 1)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (byPages) "Перейти к странице" else "Перейти к месту в книге") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    input = if (byPages) {
                        value.filter(Char::isDigit).take(6)
                    } else {
                        value.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
                    }
                },
                label = {
                    Text(if (byPages) "Страница от 1 до $totalPages" else "Процент от 0 до 100")
                },
                supportingText = {
                    Text(
                        if (byPages) {
                            "Сейчас: ${position.pageNumber} из $totalPages"
                        } else {
                            "Сейчас: ${formatBookPercent(position.percent)} · страница " +
                                "${targetPageIndex?.plus(1) ?: "—"} из $totalPages"
                        }
                    )
                },
                isError = input.isNotEmpty() && targetPageIndex == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (byPages) KeyboardType.Number else KeyboardType.Decimal
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = targetPageIndex != null,
                onClick = { targetPageIndex?.let(onJump) }
            ) {
                Text("Перейти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
