package com.lumina.reader.ui.reader

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
import androidx.compose.ui.text.input.KeyboardType
import com.lumina.reader.ui.theme.LegacyM3Defaults

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
                onClick = { targetPageIndex?.let(onJump) },
                colors = LegacyM3Defaults.textButtonColors()
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
