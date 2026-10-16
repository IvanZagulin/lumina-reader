package com.lumina.reader.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.social.Member
import com.lumina.reader.core.social.ReadingStatus
import com.lumina.reader.core.social.SharedBook
import com.lumina.reader.ui.catalog.CatalogTopBar
import com.lumina.reader.ui.components.absorbListOverscroll
import com.lumina.reader.ui.reader.rememberTextSharer
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.LuminaDimens

/**
 * The reading circle: who is reading what, and what this device shares back.
 *
 * Two states, decided by whether this device has joined: the invite form, or
 * the feed with the sharing switch above it. Opening the screen refreshes,
 * which also pushes this device's shelf - or an empty one while sharing is off,
 * since that is what keeps the server's copy in step with the choice.
 */
@Composable
fun CircleScreen(
    viewModel: CircleViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp
    // The same share sheet a quote uses; an invite code is just text.
    val shareText = rememberTextSharer()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CatalogTopBar(
            title = "Читательский круг",
            subtitle = if (state.settings.joined) {
                "Вы здесь как ${state.settings.displayName}"
            } else {
                "Кто что читает — по приглашению"
            },
            onBack = onBack,
            actions = {
                if (state.settings.joined) {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        if (state.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Обновить",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = LuminaDimens.ScreenGutter,
                end = LuminaDimens.ScreenGutter,
                top = 8.dp,
                bottom = bottom
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.error?.let { message ->
                item(key = "error", contentType = "error") {
                    ErrorCard(message = message, onDismiss = viewModel::dismissError)
                }
            }
            state.notice?.let { message ->
                item(key = "notice", contentType = "notice") {
                    NoticeCard(message = message, onDismiss = viewModel::dismissNotice)
                }
            }

            if (!state.settings.joined) {
                item(key = "join", contentType = "join") {
                    JoinCard(
                        serverUrl = state.serverUrlDraft,
                        onServerUrlChange = viewModel::setServerUrlDraft,
                        busy = state.busy,
                        onJoin = { code, name -> viewModel.join(state.serverUrlDraft, code, name) }
                    )
                }
                // Shown here too: the first member of a circle has nobody to get
                // a code from and has to mint their own before joining.
                item(key = "invite", contentType = "invite") {
                    InviteCard(
                        serverUrl = state.serverUrlDraft,
                        adminToken = state.settings.adminToken,
                        code = state.lastInviteCode,
                        minting = state.minting,
                        onSaveToken = viewModel::saveAdminToken,
                        onMint = viewModel::createInvite,
                        onShare = shareText,
                        onDismissCode = viewModel::dismissInvite
                    )
                }
                return@LazyColumn
            }

            item(key = "sharing", contentType = "sharing") {
                SharingCard(
                    sharingEnabled = state.settings.sharingEnabled,
                    hiddenCount = state.settings.hiddenBookKeys.size,
                    busy = state.busy,
                    onSharingChange = viewModel::setSharingEnabled,
                    onLeave = viewModel::leave
                )
            }

            item(key = "invite", contentType = "invite") {
                InviteCard(
                    serverUrl = state.settings.serverUrl,
                    adminToken = state.settings.adminToken,
                    code = state.lastInviteCode,
                    minting = state.minting,
                    onSaveToken = viewModel::saveAdminToken,
                    onMint = viewModel::createInvite,
                    onShare = shareText,
                    onDismissCode = viewModel::dismissInvite
                )
            }

            if (state.members.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyCircle(loaded = state.loadedOnce)
                }
            } else {
                items(state.members, key = { it.userId }, contentType = { "member" }) { member ->
                    MemberCard(
                        member = member,
                        onSend = { viewModel.startSending(member) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    state.sendingTo?.let { member ->
        SendBooksSheet(
            member = member,
            books = state.myBooks,
            sending = state.sending,
            onSend = viewModel::sendChosenBooks,
            onDismiss = viewModel::cancelSending
        )
    }
}

@Composable
private fun JoinCard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    busy: Boolean,
    onJoin: (code: String, displayName: String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val canJoin = !busy && code.isNotBlank() && name.isNotBlank() && serverUrl.isNotBlank()

    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Войти в круг", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Нужен одноразовый код от того, кто держит круг. " +
                    "Пока вы не включите «Делиться полкой», с устройства не уйдёт ничего.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text("Как вас назвать") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                label = { Text("Код приглашения") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { onServerUrlChange(it.trim()) },
                label = { Text("Адрес сервера") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onJoin(code, name) },
                enabled = canJoin,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Войти в круг")
            }
        }
    }
}

/**
 * Minting invitations, for whoever runs the circle.
 *
 * The admin secret lives on the server; pasting it here is what lets a code be
 * made from the app instead of over SSH. It is kept in the app's own settings
 * as plain text, so the card says so rather than letting the reader assume
 * otherwise.
 */
@Composable
private fun InviteCard(
    serverUrl: String,
    adminToken: String,
    code: String?,
    minting: Boolean,
    onSaveToken: (String) -> Unit,
    onMint: () -> Unit,
    onShare: (String) -> Unit,
    onDismissCode: () -> Unit
) {
    var editing by remember(adminToken) { mutableStateOf(adminToken.isBlank()) }
    var draft by remember(adminToken) { mutableStateOf(adminToken) }

    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Пригласить друга", style = MaterialTheme.typography.titleMedium)

            if (editing) {
                Text(
                    text = "Нужен админ-токен круга — он лежит на сервере в файле .env. " +
                        "Приложение сохранит его у себя, чтобы приглашения выпускались отсюда. " +
                        "Хранится как обычный текст в данных приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Админ-токен") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = {
                            onSaveToken(draft)
                            editing = draft.isBlank()
                        },
                        enabled = draft.isNotBlank()
                    ) { Text("Сохранить") }
                }
                return@Column
            }

            Text(
                text = "Код одноразовый: друг введёт его один раз и останется в круге.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            if (code == null) {
                Button(onClick = onMint, enabled = !minting, modifier = Modifier.fillMaxWidth()) {
                    if (minting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Выпустить приглашение")
                }
            } else {
                Surface(
                    shape = LuminaShape.Tile,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismissCode) { Text("Скрыть") }
                    TextButton(
                        onClick = { onShare(inviteMessage(code, serverUrl)) }
                    ) { Text("Отправить другу") }
                }
            }

            TextButton(onClick = { editing = true }) { Text("Сменить админ-токен") }
        }
    }
}

/** What a friend actually needs: the code, and where to use it. */
internal fun inviteMessage(code: String, serverUrl: String): String =
    "Заходи в мой читательский круг в Lumina.\nКод приглашения: $code\nАдрес сервера: $serverUrl"

@Composable
private fun SharingCard(
    sharingEnabled: Boolean,
    hiddenCount: Int,
    busy: Boolean,
    onSharingChange: (Boolean) -> Unit,
    onLeave: () -> Unit
) {
    var confirmingLeave by remember { mutableStateOf(false) }

    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .toggleable(
                        value = sharingEnabled,
                        enabled = !busy,
                        role = Role.Switch,
                        onValueChange = onSharingChange
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Делиться полкой", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (sharingEnabled) {
                            if (hiddenCount > 0) "Видно всем в круге, кроме скрытых книг: $hiddenCount" else "Видно всем в круге"
                        } else {
                            "Выключено — на сервере о вас ничего не хранится"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = sharingEnabled, onCheckedChange = null, enabled = !busy)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (confirmingLeave) {
                Text(
                    text = "Выйти из круга? Ваша полка на сервере будет стёрта, " +
                        "а чтобы вернуться, понадобится новое приглашение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { confirmingLeave = false }, enabled = !busy) { Text("Отмена") }
                    TextButton(
                        onClick = {
                            confirmingLeave = false
                            onLeave()
                        },
                        enabled = !busy
                    ) { Text("Выйти") }
                }
            } else {
                TextButton(onClick = { confirmingLeave = true }, enabled = !busy) {
                    Text("Выйти из круга")
                }
            }
        }
    }
}

@Composable
private fun MemberCard(member: Member, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)) {
            Text(member.displayName, style = MaterialTheme.typography.titleMedium)
            if (member.books.isEmpty()) {
                Text(
                    text = "Полкой пока не делится",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    text = booksSummary(member.books.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                member.books.forEach { book ->
                    SharedBookRow(book)
                }
            }
            // Sending works whether or not they share a shelf back.
            TextButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Отправить книгу")
            }
        }
    }
}

/**
 * Choosing what to send. A series is not a special case: its volumes are books
 * with the same series name, so picking several of them and sending is all the
 * other side needs to rebuild the shelf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendBooksSheet(
    member: Member,
    books: List<Book>,
    sending: Boolean,
    onSend: (Set<Long>) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf(emptySet<Long>()) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = LuminaShape.Sheet,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Отправить ${member.displayName}", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Книга уйдёт через сервер и появится у друга в полке, когда он откроет приложение. " +
                    "С сервера она удалится сразу после получения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }

        if (books.isEmpty()) {
            Text(
                text = "В библиотеке пока нет книг.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@ModalBottomSheet
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).absorbListOverscroll(),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            items(books, key = { it.id }, contentType = { "book" }) { book ->
                val checked = book.id in chosen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(
                            value = checked,
                            enabled = !sending,
                            role = Role.Checkbox,
                            onValueChange = { on ->
                                chosen = if (on) chosen + book.id else chosen - book.id
                            }
                        )
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = !sending)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text(
                            text = buildString {
                                append(book.author.ifBlank { "Неизвестный автор" })
                                if (book.seriesName.isNotBlank()) append(" · ").append(book.seriesName)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Button(
                onClick = { onSend(chosen) },
                enabled = !sending && chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (sending) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (chosen.isEmpty()) "Выберите книги" else "Отправить ${booksSummary(chosen.size)}")
            }
            Spacer(Modifier.height(bottom + 8.dp))
        }
    }
}

@Composable
private fun SharedBookRow(book: SharedBook) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(book.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = book.author.ifBlank { "Неизвестный автор" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = describeProgress(book),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            book.rating?.let { rating ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "★".repeat(rating.coerceIn(0, 5)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyCircle(loaded: Boolean) {
    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(
                text = if (loaded) {
                    "Кроме вас в круге пока никого нет. Передайте другу код приглашения."
                } else {
                    "Загружаем круг…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoticeCard(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Скрыть") }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = LuminaShape.Card,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Скрыть") }
        }
    }
}

/** «читает, 42%» / «дочитал» / «в планах» — the three statuses the server stores. */
internal fun describeProgress(book: SharedBook): String = when (book.status) {
    ReadingStatus.DONE -> "дочитал"
    ReadingStatus.NOT_STARTED -> "в планах"
    else -> "читает, ${book.progress.coerceIn(0, 100)}%"
}

/** «3 книги» with the Russian plural the count needs. */
internal fun booksSummary(count: Int): String {
    val tail = count % 100
    val word = when {
        tail in 11..14 -> "книг"
        count % 10 == 1 -> "книга"
        count % 10 in 2..4 -> "книги"
        else -> "книг"
    }
    return "$count $word"
}
