package com.lumina.reader.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStatus
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookClick: (Long) -> Unit,
    onStatsClick: () -> Unit,
    onCheckForUpdates: () -> Unit = {},
    isCheckingForUpdates: Boolean = false
) {
    val books by viewModel.books.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val selectedStatus by viewModel.selectedStatus.collectAsState()
    val selectedCollection by viewModel.selectedCollection.collectAsState()
    val selectedSeries by viewModel.selectedSeries.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val seriesNames by viewModel.seriesNames.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.userMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importBookFromUri(uri)
        }
    }

    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var bookToEditOrganization by remember { mutableStateOf<Book?>(null) }
    var newCollectionName by remember { mutableStateOf("") }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Lumina",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Reader",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Light
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onCheckForUpdates,
                        enabled = !isCheckingForUpdates
                    ) {
                        if (isCheckingForUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "Проверить обновления",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(onClick = onStatsClick) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Статистика чтения",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    filePickerLauncher.launch(
                        arrayOf(
                            "application/pdf",
                            "application/epub+zip",
                            "application/x-fictionbook+xml",
                            "application/x-fictionbook",
                            "text/plain",
                            "application/zip",
                            "*/*"
                        )
                    )
                },
                icon = { Icon(Icons.Default.Add, contentDescription = "Добавить") },
                text = { Text("Добавить книгу", fontWeight = FontWeight.SemiBold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                placeholder = { Text("Поиск книг, авторов, полок...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Поиск",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )

            // Compact status pills leave more vertical space for the actual library.
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 1.dp)
            ) {
                items(ReadingStatus.values().toList(), key = { it.name }) { status ->
                    FilterChip(
                        selected = selectedStatus == status,
                        onClick = { viewModel.onStatusSelected(status) },
                        label = {
                            Text(
                                text = status.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                fontWeight = if (selectedStatus == status) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (selectedStatus == status) {
                            {
                                Icon(
                                    imageVector = when (status) {
                                        ReadingStatus.ALL -> Icons.Default.AutoStories
                                        ReadingStatus.READING -> Icons.Default.MenuBook
                                        ReadingStatus.FAVORITES -> Icons.Default.Favorite
                                        ReadingStatus.COMPLETED -> Icons.Default.CheckCircle
                                        ReadingStatus.COLLECTIONS -> Icons.Default.Folder
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
            }

            // If "По полкам" is selected -> show collections sub-row
            if (selectedStatus == ReadingStatus.COLLECTIONS) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        FilterChip(
                            selected = selectedCollection == null && selectedSeries == null,
                            onClick = viewModel::onAllShelvesSelected,
                            label = { Text("Все полки") }
                        )
                    }
                    items(collections) { coll ->
                        FilterChip(
                            selected = selectedCollection == coll,
                            onClick = { viewModel.onCollectionSelected(coll) },
                            label = { Text(coll) }
                        )
                    }
                    items(seriesNames) { seriesName ->
                        FilterChip(
                            selected = selectedSeries.equals(seriesName, ignoreCase = true),
                            onClick = { viewModel.onSeriesSelected(seriesName) },
                            label = { Text(seriesName) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Bookmarks,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                    item {
                        AssistChip(
                            onClick = { showCreateCollectionDialog = true },
                            label = { Text("+ Новая полка") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            // Format Filter Chips (EPUB, FB2, PDF, TXT)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFormat == null,
                        onClick = { viewModel.onFormatSelected(null) },
                        label = { Text("Все форматы") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFormat == BookFormat.EPUB,
                        onClick = { viewModel.onFormatSelected(BookFormat.EPUB) },
                        label = { Text("EPUB") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFormat == BookFormat.FB2 || selectedFormat == BookFormat.FB2_ZIP,
                        onClick = { viewModel.onFormatSelected(BookFormat.FB2) },
                        label = { Text("FB2") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFormat == BookFormat.PDF,
                        onClick = { viewModel.onFormatSelected(BookFormat.PDF) },
                        label = { Text("PDF") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFormat == BookFormat.TXT,
                        onClick = { viewModel.onFormatSelected(BookFormat.TXT) },
                        label = { Text("TXT") }
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }

            // Book List or Empty State
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Ничего не найдено" else "Здесь пока пусто",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Попробуйте изменить поисковый запрос" else "Нажмите «+» чтобы добавить книгу (EPUB, FB2, PDF, TXT)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                ) {
                    if (
                        selectedStatus == ReadingStatus.COLLECTIONS &&
                        selectedCollection == null &&
                        selectedSeries == null
                    ) {
                        // A named series becomes a virtual shelf automatically. Regular
                        // collection shelves remain intact, so a series book can also
                        // still be found on the shelf the user assigned it to.
                        val seriesShelves = books
                            .filter { it.seriesName.isNotBlank() }
                            .groupBy { normalizeShelfName(it.seriesName).lowercase() }
                            .values
                            .sortedBy { normalizeShelfName(it.first().seriesName).lowercase() }

                        val regularShelves = books
                            .groupBy {
                                normalizeShelfName(it.collection).ifBlank { "Основная" }.lowercase()
                            }
                            .values
                            .sortedBy {
                                normalizeShelfName(it.first().collection).ifBlank { "Основная" }.lowercase()
                            }

                        seriesShelves.forEach { seriesBooks ->
                            val seriesName = normalizeShelfName(seriesBooks.first().seriesName)
                            item(key = "series_header_$seriesName") {
                                LibraryShelfHeader(
                                    title = seriesName,
                                    bookCount = seriesBooks.size,
                                    isSeries = true
                                )
                            }
                            items(
                                items = seriesBooks.sortedForSeries(),
                                key = { "series_${seriesName}_${it.id}" }
                            ) { book ->
                                BookItemCard(
                                    book = book,
                                    onClick = { onBookClick(book.id) },
                                    onDelete = { bookToDelete = book },
                                    onToggleFavorite = { viewModel.toggleFavorite(book) },
                                    onToggleCompleted = { viewModel.toggleCompleted(book) },
                                    onEditOrganization = { bookToEditOrganization = book }
                                )
                            }
                        }

                        regularShelves.forEach { collectionBooks ->
                            val collectionName = normalizeShelfName(collectionBooks.first().collection)
                                .ifBlank { "Основная" }
                            item(key = "collection_header_$collectionName") {
                                LibraryShelfHeader(
                                    title = collectionName,
                                    bookCount = collectionBooks.size,
                                    isSeries = false
                                )
                            }
                            items(
                                items = collectionBooks.sortedByDescending { it.lastReadTimestamp },
                                key = { "collection_${collectionName}_${it.id}" }
                            ) { book ->
                                BookItemCard(
                                    book = book,
                                    onClick = { onBookClick(book.id) },
                                    onDelete = { bookToDelete = book },
                                    onToggleFavorite = { viewModel.toggleFavorite(book) },
                                    onToggleCompleted = { viewModel.toggleCompleted(book) },
                                    onEditOrganization = { bookToEditOrganization = book }
                                )
                            }
                        }
                    } else {
                        if (selectedStatus == ReadingStatus.COLLECTIONS && selectedSeries != null) {
                            item(key = "selected_series_header") {
                                LibraryShelfHeader(
                                    title = selectedSeries.orEmpty(),
                                    bookCount = books.size,
                                    isSeries = true
                                )
                            }
                        } else if (
                            selectedStatus == ReadingStatus.COLLECTIONS &&
                            selectedCollection != null
                        ) {
                            item(key = "selected_collection_header") {
                                LibraryShelfHeader(
                                    title = selectedCollection.orEmpty(),
                                    bookCount = books.size,
                                    isSeries = false
                                )
                            }
                        }
                        items(books, key = { it.id }) { book ->
                            BookItemCard(
                                book = book,
                                onClick = { onBookClick(book.id) },
                                onDelete = { bookToDelete = book },
                                onToggleFavorite = { viewModel.toggleFavorite(book) },
                                onToggleCompleted = { viewModel.toggleCompleted(book) },
                                onEditOrganization = { bookToEditOrganization = book }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("Удалить книгу?") },
            text = { Text("Книга «${book.title}» будет удалена из библиотеки.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(book)
                        bookToDelete = null
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text("Отмена")
                }
            }
        )
    }

    // Edit the regular shelf and the optional automatic series shelf together.
    bookToEditOrganization?.let { book ->
        var selectedCollectionName by remember(book.id) { mutableStateOf(book.collection) }
        var selectedSeriesName by remember(book.id) { mutableStateOf(book.seriesName) }
        var seriesOrderText by remember(book.id) {
            mutableStateOf(book.seriesOrder.takeIf { it > 0 }?.toString().orEmpty())
        }
        AlertDialog(
            onDismissRequest = { bookToEditOrganization = null },
            icon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
            title = { Text("Полка и серия") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Обычная полка",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = selectedCollectionName,
                        onValueChange = { selectedCollectionName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название полки") },
                        placeholder = { Text("Например, Фантастика") },
                        singleLine = true
                    )
                    if (collections.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(collections, key = { it.lowercase() }) { collection ->
                                SuggestionChip(
                                    onClick = { selectedCollectionName = collection },
                                    label = { Text(collection, maxLines = 1) }
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    Text(
                        "Серия книг",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "После сохранения серия появится в библиотеке как отдельная полка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = selectedSeriesName,
                        onValueChange = { selectedSeriesName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название серии") },
                        placeholder = { Text("Например, Дюна") },
                        trailingIcon = {
                            if (selectedSeriesName.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        selectedSeriesName = ""
                                        seriesOrderText = ""
                                    }
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Убрать серию")
                                }
                            }
                        },
                        singleLine = true
                    )
                    if (seriesNames.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(seriesNames, key = { it.lowercase() }) { series ->
                                SuggestionChip(
                                    onClick = { selectedSeriesName = series },
                                    label = { Text(series, maxLines = 1) },
                                    icon = {
                                        Icon(
                                            Icons.Default.Bookmarks,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = seriesOrderText,
                        onValueChange = { value ->
                            seriesOrderText = value.filter(Char::isDigit).take(6)
                        },
                        label = { Text("Номер книги в серии") },
                        placeholder = { Text("1, 2, 3…") },
                        supportingText = { Text("Без номера книга будет показана в конце серии") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedSeriesName.isNotBlank(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateBookOrganization(
                            book = book,
                            collection = selectedCollectionName,
                            seriesName = selectedSeriesName,
                            seriesOrder = seriesOrderText.toIntOrNull() ?: 0
                        )
                        bookToEditOrganization = null
                    },
                    enabled = selectedCollectionName.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { bookToEditOrganization = null }) { Text("Отмена") }
            }
        )
    }

    // Create New Collection Dialog
    if (showCreateCollectionDialog) {
        AlertDialog(
            onDismissRequest = { showCreateCollectionDialog = false },
            title = { Text("Новая полка") },
            text = {
                OutlinedTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    placeholder = { Text("Название (напр. Фантастика)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newCollectionName.isNotBlank()) {
                            viewModel.onCollectionSelected(newCollectionName.trim())
                            newCollectionName = ""
                            showCreateCollectionDialog = false
                        }
                    }
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateCollectionDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun LibraryShelfHeader(
    title: String,
    bookCount: Int,
    isSeries: Boolean
) {
    val accent = if (isSeries) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.16f),
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isSeries) Icons.Default.Bookmarks else Icons.Default.Folder,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = if (isSeries) "Серия · книги по порядку" else "Полка",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
            ) {
                Text(
                    text = "$bookCount ${bookCount.bookWord()}",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
        }
    }
}

private fun Int.bookWord(): String {
    val mod100 = this % 100
    val mod10 = this % 10
    return when {
        mod100 in 11..14 -> "книг"
        mod10 == 1 -> "книга"
        mod10 in 2..4 -> "книги"
        else -> "книг"
    }
}
