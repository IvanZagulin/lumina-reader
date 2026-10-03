package com.lumina.reader.ui.reader.selection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.network.AiClient
import com.lumina.reader.core.network.AiMessage
import com.lumina.reader.ui.reader.ReaderFonts
import com.lumina.reader.ui.reader.chrome.ReaderChromeColors
import com.lumina.reader.ui.reader.chrome.ReaderModalSheet
import kotlinx.coroutines.CancellationException

/** What the reader asks about a quote. */
internal enum class AskAiMode(val label: String) {
    EXPLAIN("Объяснить"),
    RETELL("Пересказать"),
    TRANSLATE("Перевести"),
    WHO("Кто это?")
}

/** Longest quote sent to the assistant. */
internal const val MAX_AI_QUOTE_LENGTH = 2_000

/**
 * The request for «✦ Спросить ИИ»: a short system instruction in Russian
 * and the quote with the book it comes from.
 */
internal fun askAiMessages(mode: AskAiMode, quote: String, bookTitle: String, author: String): List<AiMessage> {
    val task = when (mode) {
        AskAiMode.EXPLAIN -> "Объясни смысл этого фрагмента простыми словами: о чём он, что важно понять."
        AskAiMode.RETELL -> "Кратко перескажи этот фрагмент своими словами в 2–3 предложениях."
        AskAiMode.TRANSLATE -> "Переведи этот фрагмент: на русский, если он на другом языке, иначе на английский."
        AskAiMode.WHO -> "Кто или что упоминается в этом фрагменте? Коротко поясни, без спойлеров дальнейшего сюжета."
    }
    val source = listOf(bookTitle, author).filter { it.isNotBlank() }.joinToString(", ")
    val text = quote.trim().take(MAX_AI_QUOTE_LENGTH)
    return listOf(
        AiMessage(
            role = "system",
            content = "Ты — Lumina, помощник читателя в приложении для чтения книг. " +
                "Отвечай по-русски, кратко и по делу, без markdown-разметки."
        ),
        AiMessage(
            role = "user",
            content = buildString {
                append(task)
                if (source.isNotEmpty()) append("\nКнига: ").append(source)
                append("\n\nФрагмент:\n«").append(text).append('»')
            }
        )
    )
}

private sealed interface AskAiState {
    data object Loading : AskAiState
    data class Answer(val text: String) : AskAiState
    data class Failed(val message: String) : AskAiState
}

/**
 * «✦ Спросить ИИ» (§7.5): the quote, the question chips and the answer
 * under a «✦ LUMINA» eyebrow. Uses the app's [AiClient]; failures are shown
 * with «Повторить».
 */
@Composable
internal fun AskAiSheet(
    quote: String,
    bookTitle: String,
    author: String,
    colors: ReaderChromeColors,
    onDismiss: () -> Unit
) {
    val client = remember { AiClient() }
    var mode by rememberSaveable { mutableStateOf(AskAiMode.EXPLAIN) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<AskAiState>(AskAiState.Loading) }
    LaunchedEffect(mode, attempt) {
        state = AskAiState.Loading
        state = try {
            val answer = client.askAssistant(askAiMessages(mode, quote, bookTitle, author))
            AskAiState.Answer(answer.content.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AskAiState.Failed(e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Нет связи с помощником")
        }
    }
    ReaderModalSheet(colors = colors, onDismiss = onDismiss, scrimAlpha = 0.32f) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                text = "«${quote.trim()}»",
                fontFamily = ReaderFonts.Literata.family,
                fontStyle = FontStyle.Italic,
                fontSize = 15.sp,
                color = colors.content,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AskAiMode.entries.forEach { option ->
                    FilterChip(
                        selected = option == mode,
                        onClick = {
                            if (option == mode) attempt++ else mode = option
                        },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.selectedBg,
                            selectedLabelColor = colors.accent,
                            labelColor = colors.content
                        ),
                        border = BorderStroke(1.dp, colors.border)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "✦ LUMINA",
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent
            )
            Spacer(modifier = Modifier.height(6.dp))
            when (val current = state) {
                AskAiState.Loading -> LinearProgressIndicator(
                    color = colors.accent,
                    trackColor = colors.content.copy(alpha = 0.08f),
                    modifier = Modifier
                        .width(96.dp)
                        .height(2.dp)
                )
                is AskAiState.Answer -> Text(
                    text = current.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.content,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
                is AskAiState.Failed -> Column {
                    Text(
                        text = "Не удалось получить ответ: ${current.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error
                    )
                    TextButton(onClick = { attempt++ }) {
                        Text("Повторить", color = colors.accent)
                    }
                }
            }
        }
    }
}
