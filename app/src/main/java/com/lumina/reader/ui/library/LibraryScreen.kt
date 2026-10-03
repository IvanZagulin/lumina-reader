package com.lumina.reader.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.ReadingStatus
import com.lumina.reader.core.preferences.AppUiPreferences
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.preferences.LibraryViewMode
import com.lumina.reader.ui.components.BookcaseRow
import com.lumina.reader.ui.components.GhostBooksRow
import com.lumina.reader.ui.components.ShelfBookSize
import com.lumina.reader.ui.components.ShelfHeader
import com.lumina.reader.ui.components.ShelfHeaderStyle
import com.lumina.reader.ui.components.ShelfRow
import com.lumina.reader.ui.components.bookcaseLayout
import com.lumina.reader.ui.components.rememberShelfBookSize
import com.lumina.reader.ui.components.toCoverModel
import com.lumina.reader.ui.components.toShelfBookUi
import com.lumina.reader.ui.shell.LocalDockScroll
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.transition.BookTransitionState
import com.lumina.reader.ui.transition.LocalBookTransition
import kotlinx.coroutines.launch

/**
 * The library (spec §5): one LazyColumn over the warm wall with a lamp glow —
 * header, «Продолжить чтение», sticky filters, then shelves (default «Полки»),
 * the bookcase grid or the list. Books open through the root book transition.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (Long) -> Unit,
    onImportBook: () -> Unit,
    onOpenCatalogs: () -> Unit,
    onSearchCatalogs: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onCheckForUpdates: () -> Unit = {},
    isCheckingForUpdates: Boolean = false,
    updateAvailable: Boolean = false
) {
    val context = LocalContext.current
    val allBooks by viewModel.allBooks.collectAsState()
    val libraryLoaded by viewModel.libraryLoaded.collectAsState()
    val books by viewModel.displayBooks.collectAsState()
    val shelves by viewModel.shelves.collectAsState()
    val heroPick by viewModel.heroPick.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val status by viewModel.selectedStatus.collectAsState()
    val format by viewModel.selectedFormat.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val captions by viewModel.shelfCaptions.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val seriesNames by viewModel.seriesNames.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val transition = LocalBookTransition.current
    val uiPreferences = remember(context) { AppUiPreferences.get(context) }
    val openAnimation by uiPreferences.openAnimation.collectAsState()
    val scope = rememberCoroutineScope()

    var searchActive by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var finishedExpanded by rememberSaveable { mutableStateOf(false) }
    var actionsBookId by rememberSaveable { mutableStateOf<Long?>(null) }
    var actionsPage by rememberSaveable { mutableStateOf(BookSheetPage.MAIN) }
    var showViewSheet by rememberSaveable { mutableStateOf(false) }
    var showShelfManager by rememberSaveable { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var renameShelf by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteShelf by rememberSaveable { mutableStateOf<String?>(null) }

    val closeSearch = {
        searchActive = false
        viewModel.onSearchQueryChanged("")
    }
    BackHandler(enabled = searchActive) { closeSearch() }

    val booksById = remember(allBooks) { allBooks.associateBy(Book::id) }
    val bookSize = rememberShelfBookSize()
    val screenWidth = LocalConfiguration.current.screenWidthDp.toFloat()
    val caseLayout = remember(screenWidth, bookSize) { bookcaseLayout(screenWidth, bookSize.width.value) }
    val caseColumns = caseLayout.first
    val caseSize = remember(caseLayout) { ShelfBookSize(caseLayout.second.dp, (caseLayout.second * 1.5f).dp) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = max(LuminaDimens.DockClearance, navBottom + LuminaDimens.DockHeight + 36.dp)

    fun openBook(book: Book, slotKey: String?) {
        val id = book.id
        if (transition == null) {
            onOpenBook(id)
        } else {
            transition.open(book.toCoverModel(), slotKey ?: transition.findSlotKey(id), openAnimation) {
                onOpenBook(id)
            }
        }
    }

    fun showActions(bookId: Long, page: BookSheetPage = BookSheetPage.MAIN) {
        actionsPage = page
        actionsBookId = bookId
    }

    val callbacks = ShelfCallbacks(
        onOpen = { id, slot -> booksById[id]?.let { openBook(it, slot) } },
        onActions = { id, page -> showActions(id, page) },
        onShowAll = { key -> detailKey = key },
        onRename = { name -> renameShelf = name },
        onDelete = { name -> deleteShelf = name }
    )

    val shelvesView = status == ReadingStatus.COLLECTIONS && query.isBlank()
    val listState = rememberLazyListState()
    val colors = Lumina.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // While the reader closes onto the library it grows from 0.94 to 1.
                val closing = transition?.phase == BookTransitionState.Phase.Closing
                val p = if (closing) transition?.libraryBackdrop?.value ?: 0f else 0f
                scaleX = 1f - 0.06f * p
                scaleY = scaleX
            }
            .drawWithCache {
                val glow = Brush.radialGradient(
                    colors = listOf(colors.lampGlow, Color.Transparent),
                    center = Offset(size.width * 0.72f, -40.dp.toPx()),
                    radius = 420.dp.toPx()
                )
                val vignette = if (colors.isDark) {
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        center = Offset(size.width / 2f, size.height * 0.4f),
                        radius = size.maxDimension * 0.75f
                    )
                } else {
                    null
                }
                onDrawBehind {
                    drawRect(colors.wall)
                    drawRect(glow)
                    if (vignette != null) drawRect(vignette)
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .nestedScroll(LocalDockScroll.current),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            item(key = "header", contentType = "header") {
                LibraryHeader(
                    subtitle = summary.subtitle(),
                    searchActive = searchActive,
                    query = query,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onOpenSearch = { searchActive = true },
                    onCloseSearch = closeSearch,
                    onOpenViewSheet = { showViewSheet = true },
                    onManageShelves = { showShelfManager = true },
                    onOpenSettings = onOpenSettings,
                    onCheckForUpdates = onCheckForUpdates,
                    isCheckingForUpdates = isCheckingForUpdates,
                    updateAvailable = updateAvailable
                )
            }
            if (isLoading) {
                item(key = "loading", contentType = "loading") {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(2.dp)
                    )
                }
            }
            val hero = heroPick
            if (hero != null && query.isBlank() && !searchActive) {
                item(key = "hero", contentType = "hero") {
                    Column(modifier = Modifier.animateItem()) {
                        ContinueReadingHero(
                            pick = hero,
                            onOpen = { slot -> openBook(hero.book, slot) },
                            onLongPress = { showActions(hero.book.id) }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            stickyHeader(key = "filters", contentType = "filters") {
                LibraryFilterRow(
                    selected = status,
                    onSelect = { selected ->
                        viewModel.onStatusSelected(selected)
                        if (selected == ReadingStatus.COLLECTIONS) viewModel.onAllShelvesSelected()
                    }
                )
            }

            when {
                libraryLoaded && allBooks.isEmpty() && !isLoading -> item(key = "empty", contentType = "empty") {
                    EmptyShelf(
                        variant = EmptyShelfVariant.NO_BOOKS,
                        onAddFile = onImportBook,
                        onOpenCatalogs = onOpenCatalogs,
                        modifier = Modifier.animateItem()
                    )
                }
                shelvesView -> {
                    if (allBooks.isNotEmpty() && allBooks.all(Book::isDone)) {
                        item(key = "all-read", contentType = "empty") {
                            EmptyShelf(
                                variant = EmptyShelfVariant.ALL_READ,
                                onAddFile = onImportBook,
                                onOpenCatalogs = onOpenCatalogs,
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                    shelfSections(
                        shelves = shelves,
                        viewMode = viewMode,
                        bookSize = bookSize,
                        caseSize = caseSize,
                        caseColumns = caseColumns,
                        captions = captions,
                        finishedExpanded = finishedExpanded,
                        onToggleFinished = { finishedExpanded = !finishedExpanded },
                        callbacks = callbacks
                    )
                }
                libraryLoaded && books.isEmpty() -> item(key = "empty-filter", contentType = "empty") {
                    EmptyShelf(
                        variant = if (query.isNotBlank()) EmptyShelfVariant.NO_RESULTS else EmptyShelfVariant.EMPTY_FILTER,
                        query = query,
                        onSearchCatalogs = onSearchCatalogs,
                        onResetFilter = {
                            viewModel.onStatusSelected(ReadingStatus.COLLECTIONS)
                            viewModel.onAllShelvesSelected()
                            if (format != null) viewModel.onFormatSelected(format)
                        },
                        modifier = Modifier.animateItem()
                    )
                }
                viewMode == LibraryViewMode.LIST -> items(
                    items = books,
                    key = { "list:${it.id}" },
                    contentType = { "list" }
                ) { book ->
                    BookListRow(
                        book = book,
                        slotKey = "list:${book.id}",
                        onOpen = { openBook(book, "list:${book.id}") },
                        onMore = { showActions(book.id) },
                        onMoveToShelf = { showActions(book.id, BookSheetPage.MOVE_TO_SHELF) },
                        onDelete = { showActions(book.id, BookSheetPage.CONFIRM_DELETE) },
                        modifier = Modifier.animateItem()
                    )
                }
                else -> {
                    val rows = books.chunked(caseColumns)
                    rows.forEachIndexed { index, row ->
                        item(key = "case:$index", contentType = "case") {
                            val shelfBooks = remember(row) { row.map { it.toShelfBookUi() } }
                            BookcaseRow(
                                rowSeed = "case:$index",
                                books = shelfBooks,
                                size = caseSize,
                                slotPrefix = "grid",
                                onOpen = { book, slot -> callbacks.onOpen(book.id, slot) },
                                onLongPress = { book -> showActions(book.id) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Sheets and dialogs ----------------------------------------------------

    val actionsBook = actionsBookId?.let { booksById[it] }
    if (actionsBook != null) {
        BookActionsSheet(
            book = actionsBook,
            collections = collections,
            seriesNames = seriesNames,
            initialPage = actionsPage,
            onDismiss = { actionsBookId = null },
            onRead = { openBook(actionsBook, null) },
            onToggleFavorite = { viewModel.toggleFavorite(actionsBook) },
            onToggleCompleted = { viewModel.toggleCompleted(actionsBook) },
            onSaveOrganization = { organization ->
                viewModel.updateBookOrganization(
                    book = actionsBook,
                    collection = organization.collection,
                    seriesName = organization.seriesName,
                    seriesOrder = organization.seriesOrder
                )
            },
            onDelete = { viewModel.deleteBook(actionsBook) },
            onShare = { scope.launch { shareBook(context, actionsBook) } }
        )
    }

    if (showViewSheet) {
        LibraryViewSheet(
            sort = sort,
            format = format,
            viewMode = viewMode,
            captions = captions,
            onSortSelected = viewModel::onSortSelected,
            onFormatSelected = viewModel::onFormatSelected,
            onViewModeSelected = viewModel::onViewModeSelected,
            onCaptionsChanged = viewModel::onShelfCaptionsChanged,
            onDismiss = { showViewSheet = false }
        )
    }

    if (showShelfManager) {
        val entries = remember(collections, allBooks) {
            collections
                .filterNot { it.equals(LibraryPreferences.MAIN_SHELF, ignoreCase = true) }
                .map { name -> ShelfEntry(name, allBooks.count { it.shelfName().equals(name, ignoreCase = true) }) }
        }
        ShelfManagerSheet(
            shelves = entries,
            onCreate = viewModel::createShelf,
            onRename = viewModel::renameShelf,
            onDelete = viewModel::deleteShelf,
            onDismiss = { showShelfManager = false }
        )
    }

    detailKey?.let { key ->
        val section = shelves.firstOrNull { it.key == key }
        if (section != null) {
            ShelfDetailSheet(
                title = section.title,
                books = section.books,
                onOpen = { book -> openBook(book, null) },
                onLongPress = { book -> showActions(book.id) },
                onDismiss = { detailKey = null }
            )
        }
    }

    renameShelf?.let { name ->
        RenameShelfDialog(
            shelfName = name,
            onRename = { target ->
                viewModel.renameShelf(name, target)
                renameShelf = null
            },
            onDismiss = { renameShelf = null }
        )
    }
    deleteShelf?.let { name ->
        DeleteShelfDialog(
            shelfName = name,
            onDelete = {
                viewModel.deleteShelf(name)
                deleteShelf = null
            },
            onDismiss = { deleteShelf = null }
        )
    }
}

/** Callbacks of the shelf sections, kept together so the LazyColumn builder stays small. */
private class ShelfCallbacks(
    val onOpen: (bookId: Long, slotKey: String?) -> Unit,
    val onActions: (bookId: Long, page: BookSheetPage) -> Unit,
    val onShowAll: (sectionKey: String) -> Unit,
    val onRename: (shelf: String) -> Unit,
    val onDelete: (shelf: String) -> Unit
)

/** The «Полки» view: a header and a row (or grid rows / list rows) per [ShelfSection]. */
private fun LazyListScope.shelfSections(
    shelves: List<ShelfSection>,
    viewMode: LibraryViewMode,
    bookSize: ShelfBookSize,
    caseSize: ShelfBookSize,
    caseColumns: Int,
    captions: Boolean,
    finishedExpanded: Boolean,
    onToggleFinished: () -> Unit,
    callbacks: ShelfCallbacks
) {
    shelves.forEach { section ->
        val style = when (section.kind) {
            ShelfKind.SERIES -> ShelfHeaderStyle.SERIES
            ShelfKind.CUSTOM -> ShelfHeaderStyle.CUSTOM
            ShelfKind.FINISHED -> ShelfHeaderStyle.COLLAPSIBLE
            else -> ShelfHeaderStyle.PLAIN
        }
        item(key = "header:${section.key}", contentType = "shelf-header") {
            ShelfHeader(
                title = section.title,
                count = section.books.size,
                style = style,
                readCount = section.readCount,
                totalCount = section.totalCount,
                expanded = finishedExpanded,
                onShowAll = if (section.books.isNotEmpty() && section.kind != ShelfKind.FINISHED) {
                    { callbacks.onShowAll(section.key) }
                } else {
                    null
                },
                onToggle = onToggleFinished,
                onRename = if (section.kind == ShelfKind.CUSTOM) {
                    { callbacks.onRename(section.title) }
                } else {
                    null
                },
                onDelete = if (section.kind == ShelfKind.CUSTOM) {
                    { callbacks.onDelete(section.title) }
                } else {
                    null
                },
                modifier = Modifier.animateItem()
            )
        }
        if (section.kind == ShelfKind.FINISHED && !finishedExpanded) return@forEach
        if (section.books.isEmpty()) {
            item(key = "ghost:${section.key}", contentType = "ghost") {
                Column(modifier = Modifier.animateItem()) {
                    GhostBooksRow(size = bookSize, seed = section.key)
                    Text(
                        text = "Пусто — добавьте книги через карточку книги",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                    )
                }
            }
            return@forEach
        }
        when (viewMode) {
            LibraryViewMode.SHELVES -> item(key = "row:${section.key}", contentType = "shelf-row") {
                val shelfBooks = remember(section.books) { section.books.map { it.toShelfBookUi() } }
                ShelfRow(
                    sectionKey = section.key,
                    books = shelfBooks,
                    size = bookSize,
                    captions = captions,
                    plateText = section.plateText(),
                    showNewDot = section.kind == ShelfKind.NEW,
                    dimFinished = section.kind == ShelfKind.SERIES,
                    onOpen = { book, slot -> callbacks.onOpen(book.id, slot) },
                    onLongPress = { book -> callbacks.onActions(book.id, BookSheetPage.MAIN) },
                    onShowAll = { callbacks.onShowAll(section.key) },
                    modifier = Modifier.animateItem()
                )
            }
            LibraryViewMode.BOOKCASE -> section.books.chunked(caseColumns).forEachIndexed { index, row ->
                item(key = "case:${section.key}:$index", contentType = "case") {
                    val shelfBooks = remember(row) { row.map { it.toShelfBookUi() } }
                    BookcaseRow(
                        rowSeed = "${section.key}:$index",
                        books = shelfBooks,
                        size = caseSize,
                        slotPrefix = "grid:${section.key}",
                        showNewDot = section.kind == ShelfKind.NEW,
                        onOpen = { book, slot -> callbacks.onOpen(book.id, slot) },
                        onLongPress = { book -> callbacks.onActions(book.id, BookSheetPage.MAIN) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
            LibraryViewMode.LIST -> items(
                items = section.books,
                key = { "list:${section.key}:${it.id}" },
                contentType = { "list" }
            ) { book ->
                val slot = "list:${section.key}:${book.id}"
                BookListRow(
                    book = book,
                    slotKey = slot,
                    onOpen = { callbacks.onOpen(book.id, slot) },
                    onMore = { callbacks.onActions(book.id, BookSheetPage.MAIN) },
                    onMoveToShelf = { callbacks.onActions(book.id, BookSheetPage.MOVE_TO_SHELF) },
                    onDelete = { callbacks.onActions(book.id, BookSheetPage.CONFIRM_DELETE) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}
