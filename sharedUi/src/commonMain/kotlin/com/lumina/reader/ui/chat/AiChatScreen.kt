package com.lumina.reader.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.network.AiMessage
import com.lumina.reader.ui.downloads.DownloadMetaRegistry
import com.lumina.reader.ui.downloads.DownloadRowActions
import com.lumina.reader.ui.downloads.DownloadRowUi
import com.lumina.reader.ui.downloads.DownloadTaskRow
import com.lumina.reader.ui.downloads.DownloadUiMapper
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

private val UserBubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
private val AssistantBubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
private val CommandPattern = Regex("\\[(DOWNLOAD|ORGANIZE):.*?\\]")

private enum class ChatRole { USER, ASSISTANT, STATUS }

/**
 * One message as the chat shows it; [key] is its position in the conversation.
 * [downloadKey] links a status line to the download it started (a card under it).
 */
@Immutable
private data class ChatLine(val key: Int, val role: ChatRole, val text: String, val downloadKey: String? = null)

private fun toChatLines(messages: List<AiMessage>, downloadCards: Map<Int, String>): List<ChatLine> =
    messages.withIndex().mapNotNull { (index, message) ->
        val role = when (message.role) {
            "user" -> ChatRole.USER
            "assistant" -> ChatRole.ASSISTANT
            AiChatViewModel.ROLE_STATUS -> ChatRole.STATUS
            else -> return@mapNotNull null
        }
        // Commands are executed by the app and never shown.
        val text = message.content.replace(CommandPattern, "").trim()
        if (text.isEmpty()) null else ChatLine(index, role, text, downloadCards[index])
    }

/**
 * The assistant (spec §7.11): user bubbles in primaryContainer, assistant
 * answers on a hairline surface under «✦ Lumina», the app's progress notes
 * ([AiChatViewModel.ROLE_STATUS]) as small status lines, a typing indicator
 * and a sunken input pill. A top-level tab: no back arrow ([onBack] is kept
 * for callers). The assistant's commands run in [AiChatViewModel] itself;
 * [onDownloadAction] and [onOrganizeAction] are kept only for source
 * compatibility and are not called.
 */
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDownloadAction: (String) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOrganizeAction: (String, List<String>) -> Unit = { _, _ -> }
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val downloadCards by viewModel.downloadCards.collectAsState()
    var inputText by rememberSaveable { mutableStateOf("") }
    val lines = remember(messages, downloadCards) { toChatLines(messages, downloadCards).asReversed() }

    // Download cards (spec §7.11): the live state of the books the assistant downloads,
    // from the view model's downloads (the app's importer on Android).
    val downloads by viewModel.downloads.collectAsState()
    val downloadMeta by DownloadMetaRegistry.meta.collectAsState()
    val downloadRows = remember(downloadCards, downloads, downloadMeta) {
        downloadCards.values.mapNotNull { key ->
            downloads[key]?.let { state -> key to DownloadUiMapper.row(key, state, downloadMeta[key]) }
        }.toMap()
    }
    val downloadActions = remember(viewModel) {
        DownloadRowActions(
            onCancel = viewModel::cancelDownload,
            onRetry = viewModel::retryDownload,
            onDismiss = viewModel::dismissDownload,
            // The navigation host opens the reader for these requests.
            onOpen = AppMessages::requestOpenBook
        )
    }
    val listState = rememberLazyListState()
    val reducedMotion = rememberReducedMotion()

    // New messages arrive at index 0 of the reversed list: keep them in view.
    LaunchedEffect(lines.size, isLoading) {
        if (lines.isNotEmpty() || isLoading) listState.animateScrollToItem(0)
    }

    val send: (String) -> Unit = { text ->
        if (text.isNotBlank() && !isLoading) {
            viewModel.sendMessage(text.trim())
            inputText = ""
        }
    }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // iPhone has no back button to close the keyboard, and the keyboard covers the dock,
    // so a chat with something typed could not be left: a tap on the conversation or a
    // drag of it puts the keyboard away.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val hideKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val hideKeyboardOnDrag = remember(focusManager, keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
                return Offset.Zero
            }
        }
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Above the dock (64dp + 12dp margin) unless the keyboard is open.
    val inputBottom = if (imeVisible) 8.dp else navBottom + LuminaDimens.DockHeight + LuminaDimens.ChromeMargin + 8.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        ChatHeader()
        Box(
            modifier = Modifier
                .weight(1f)
                .nestedScroll(hideKeyboardOnDrag)
                .pointerInput(Unit) { detectTapGestures(onTap = { hideKeyboard() }) }
        ) {
            if (lines.isEmpty() && !isLoading) {
                EmptyChat(onSuggestion = send)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    reverseLayout = true
                ) {
                    if (isLoading) {
                        item(key = "typing", contentType = "typing") {
                            TypingIndicator(reducedMotion = reducedMotion)
                        }
                    }
                    items(lines, key = { it.key }, contentType = { it.role }) { line ->
                        when (line.role) {
                            ChatRole.USER -> UserBubble(line.text)
                            ChatRole.ASSISTANT -> AssistantMessage(line.text)
                            ChatRole.STATUS -> {
                                val row = line.downloadKey?.let { downloadRows[it] }
                                if (row == null) {
                                    StatusLine(line.text)
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        StatusLine(line.text)
                                        ChatDownloadCard(row = row, actions = downloadActions, reducedMotion = reducedMotion)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        ChatInput(
            value = inputText,
            onValueChange = { inputText = it },
            enabled = !isLoading,
            onSend = { send(inputText) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = inputBottom)
        )
    }
}

@Composable
private fun ChatHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = LuminaDimens.ScreenGutter, end = LuminaDimens.ScreenGutter, top = 8.dp, bottom = 4.dp)
    ) {
        Text(
            text = "Помощник",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "Подскажет, что почитать, найдёт и скачает книги",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = UserBubbleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AssistantMessage(text: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        AssistantEyebrow()
        Spacer(Modifier.size(4.dp))
        Surface(
            shape = AssistantBubbleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun AssistantEyebrow() {
    Text(
        text = "✦ LUMINA",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 4.dp)
            .clearAndSetSemantics { }
    )
}

/** The app's own progress note («Ищу «…» в каталогах…»): a small line, not a bubble. */
@Composable
private fun StatusLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A small hairline card with the live state of a download the assistant started. */
@Composable
private fun ChatDownloadCard(row: DownloadRowUi, actions: DownloadRowActions, reducedMotion: Boolean) {
    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
    ) {
        DownloadTaskRow(
            row = row,
            actions = actions,
            reducedMotion = reducedMotion,
            compact = true,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp)
        )
    }
}

/** Three dots pulsing 0.3 → 1 alpha, 900ms with a 150ms stagger; static under reduced motion. */
@Composable
private fun TypingIndicator(reducedMotion: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "Помощник печатает" }
    ) {
        AssistantEyebrow()
        Spacer(Modifier.size(4.dp))
        Surface(
            shape = AssistantBubbleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                if (reducedMotion) {
                    repeat(3) { Dot(color = dotColor, alpha = { 0.6f }) }
                } else {
                    val transition = rememberInfiniteTransition(label = "typing")
                    val alphas = (0 until 3).map { index ->
                        transition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 450),
                                repeatMode = RepeatMode.Reverse,
                                initialStartOffset = StartOffset(index * 150)
                            ),
                            label = "dot$index"
                        )
                    }
                    alphas.forEach { state -> Dot(color = dotColor, alpha = { state.value }) }
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color, alpha: () -> Float) {
    Box(
        modifier = Modifier
            .size(8.dp)
            // The animated value is read in the draw phase only.
            .graphicsLayer { this.alpha = alpha() }
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "Что почитать после последней прочитанной книги?",
        "Собери серию",
        "Найди книгу в каталоге"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = LuminaDimens.ScreenGutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        AssistantEyebrow()
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Спросите о книгах",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Помощник знает вашу полку и статистику, ищет в каталогах и сам скачивает книги.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(16.dp))
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onSuggestion(suggestion) },
                shape = LuminaShape.Pill,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** `surfaceSunken` from the extended palette, or the closest Material role when it is not provided. */
@Composable
@ReadOnlyComposable
private fun chatSunkenColor(): Color {
    val extended = Lumina.colors
    val schemeDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (extended.isDark == schemeDark) extended.surfaceSunken else MaterialTheme.colorScheme.surfaceContainerHighest
}

/** 52dp sunken pill with a 44dp primary send circle. */
@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val canSend = enabled && value.isNotBlank()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(chatSunkenColor())
            .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = "Спросите о книге или попросите скачать…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Surface(
                onClick = onSend,
                enabled = canSend,
                shape = CircleShape,
                color = if (canSend) colors.primary else colors.onSurface.copy(alpha = 0.12f),
                contentColor = if (canSend) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Отправить",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
