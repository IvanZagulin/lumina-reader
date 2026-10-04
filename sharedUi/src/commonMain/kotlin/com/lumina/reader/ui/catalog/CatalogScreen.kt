package com.lumina.reader.ui.catalog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.OpdsFormats
import com.lumina.reader.core.opds.OpdsLink
import com.lumina.reader.ui.PlatformBackHandler
import com.lumina.reader.ui.downloads.DownloadMetaRegistry
import com.lumina.reader.ui.downloads.DownloadRowActions
import com.lumina.reader.ui.downloads.DownloadTaskRow
import com.lumina.reader.ui.downloads.DownloadUiMapper
import com.lumina.reader.ui.downloads.DownloadsSheet
import com.lumina.reader.ui.theme.LuminaDimens
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion

/**
 * OPDS catalogues (spec §7.9): the catalogues home (cards, global search,
 * recent downloads), feed browsing with breadcrumbs, the global search and
 * the book details sheet. "Открыть" calls [onOpenBook] with the book id.
 *
 * [initialQuery] starts a search in all catalogues once (the library's
 * «Искать в каталогах»). The catalogue editor and the card menus use
 * [sourcesViewModel], created with an initializer because iOS has no
 * reflective view model factory (on Android the key and scope are the same
 * as `viewModel()`'s).
 *
 * The system back (Android) steps back inside the screen first; the iPhone
 * has none and uses the screen's own back arrows.
 */
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onManageCatalogs: () -> Unit,
    initialQuery: String? = null,
    // Qualified: the parameter `viewModel` above shadows the function.
    sourcesViewModel: CatalogSourcesViewModel = androidx.lifecycle.viewmodel.compose.viewModel { CatalogSourcesViewModel() }
) {
    val state by viewModel.uiState.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val library by viewModel.libraryIndex.collectAsState()
    val checks by sourcesViewModel.checks.collectAsState()
    val form by sourcesViewModel.form.collectAsState()
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var catalogToDelete by remember { mutableStateOf<OpdsCatalogConfig?>(null) }

    var initialQueryHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialQuery) {
        val query = initialQuery?.trim().orEmpty()
        if (!initialQueryHandled && query.isNotEmpty()) {
            initialQueryHandled = true
            viewModel.searchAllCatalogs(query)
        }
    }

    // Opened from the library's «Искать в каталогах»: leaving the search results
    // returns to the library instead of a catalogue home without a back arrow.
    val searchOnlyEntry = !initialQuery.isNullOrBlank()
    val closesScreen = searchOnlyEntry && state.selected == null && state.pages.isEmpty()
    PlatformBackHandler(enabled = state.selected != null || state.isBrowsing || state.globalSearch != null) {
        if (closesScreen) onBack() else viewModel.goBack()
    }

    val currentClosesScreen by rememberUpdatedState(closesScreen)
    val actions = remember(viewModel, onOpenBook, onManageCatalogs) {
        CatalogScreenActions(
            onBack = { if (currentClosesScreen || !viewModel.goBack()) onBack() },
            onHome = viewModel::closeCatalog,
            onManageCatalogs = onManageCatalogs,
            onQueryChange = viewModel::onQueryChange,
            onScopeSelected = viewModel::onScopeSelected,
            onSearch = viewModel::search,
            onClearSearch = viewModel::clearSearch,
            onOpenCatalog = viewModel::openCatalog,
            onOpenNavigation = viewModel::openNavigation,
            onOpenLink = viewModel::openLink,
            onOpenSearchSection = viewModel::openSearchSection,
            onSelectPublication = viewModel::selectPublication,
            onDismissPublication = viewModel::dismissPublication,
            onRetryPage = viewModel::retryPage,
            onLoadMore = viewModel::loadMore,
            onDownload = viewModel::download,
            onDownloadAll = viewModel::downloadAll,
            onRetryDownload = viewModel::retryDownload,
            onCancelDownload = viewModel::cancelDownload,
            onOpenBook = onOpenBook,
            onPopToPage = viewModel::popToPage,
            onOpenDownloads = { showDownloads = true },
            onDismissDownload = viewModel::dismissDownload
        )
    }
    val homeActions = remember(sourcesViewModel) {
        CatalogHomeActions(
            onAddCatalog = sourcesViewModel::startAdding,
            card = CatalogCardActions(
                onCheck = sourcesViewModel::checkConnection,
                onEdit = sourcesViewModel::startEditing,
                onHide = { catalog -> sourcesViewModel.setEnabled(catalog, false) },
                onDelete = { catalog -> catalogToDelete = catalog }
            )
        )
    }

    CatalogContent(
        state = state,
        downloads = downloads,
        actions = actions,
        library = library,
        checks = checks,
        homeActions = homeActions
    )

    form?.let { current ->
        CatalogEditorSheet(
            form = current,
            onChange = sourcesViewModel::updateForm,
            onCheck = sourcesViewModel::checkForm,
            onSave = sourcesViewModel::saveForm,
            onDismiss = sourcesViewModel::dismissForm
        )
    }
    catalogToDelete?.let { catalog ->
        DeleteCatalogDialog(
            catalog = catalog,
            onConfirm = {
                sourcesViewModel.delete(catalog)
                catalogToDelete = null
            },
            onDismiss = { catalogToDelete = null }
        )
    }
    if (showDownloads) {
        DownloadsSheet(onDismiss = { showDownloads = false }, onOpenBook = onOpenBook)
    }
}

/** Everything the catalogue UI can ask for; keeps the content stateless. */
class CatalogScreenActions(
    val onBack: () -> Unit,
    val onHome: () -> Unit,
    val onManageCatalogs: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onScopeSelected: (CatalogSearchScope) -> Unit,
    val onSearch: () -> Unit,
    val onClearSearch: () -> Unit,
    val onOpenCatalog: (OpdsCatalogConfig) -> Unit,
    val onOpenNavigation: (OpdsCatalogConfig, OpdsEntry.Navigation) -> Unit,
    val onOpenLink: (OpdsCatalogConfig, OpdsLink) -> Unit,
    val onOpenSearchSection: (CatalogSearchSection) -> Unit,
    val onSelectPublication: (OpdsCatalogConfig, OpdsEntry.Publication) -> Unit,
    val onDismissPublication: () -> Unit,
    val onRetryPage: () -> Unit,
    val onLoadMore: () -> Unit,
    val onDownload: (OpdsCatalogConfig, OpdsEntry.Publication, OpdsAcquisition) -> Unit,
    val onDownloadAll: (List<Pair<OpdsCatalogConfig, OpdsEntry.Publication>>) -> Unit,
    val onRetryDownload: (String) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val onOpenBook: (Long) -> Unit,
    val onPopToPage: (Long) -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    val onDismissDownload: (String) -> Unit = {}
) {
    fun chipCallbacks(catalog: OpdsCatalogConfig, publication: OpdsEntry.Publication) = ChipCallbacks(
        onDownload = { acquisition -> onDownload(catalog, publication, acquisition) },
        onCancel = onCancelDownload,
        onRetry = onRetryDownload,
        onOpen = onOpenBook
    )
}

/** Home-only actions: adding and managing catalogues from their cards. */
class CatalogHomeActions(
    val onAddCatalog: () -> Unit,
    val card: CatalogCardActions
)

/** Which level the screen shows; the outgoing level stays drawable during the transition. */
@Immutable
private sealed interface CatalogLevel {
    val key: String
    val depth: Int

    data object Home : CatalogLevel {
        override val key = "home"
        override val depth = 0
    }

    data class Search(val search: GlobalSearchState) : CatalogLevel {
        override val key = "search"
        override val depth = 1
    }

    data class Feed(val page: CatalogPage, val index: Int) : CatalogLevel {
        override val key = "page:${page.id}"
        override val depth = 2 + index
    }
}

@Composable
fun CatalogContent(
    state: CatalogUiState,
    downloads: Map<String, DownloadState>,
    actions: CatalogScreenActions,
    library: LibraryIndex = LibraryIndex.Empty,
    checks: Map<String, ConnectionCheck> = emptyMap(),
    homeActions: CatalogHomeActions? = null
) {
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current
    val shift = with(density) { 30.dp.roundToPx() }
    val saveableHolder = rememberSaveableStateHolder()
    val page = state.currentPage
    val level: CatalogLevel = when {
        page != null -> CatalogLevel.Feed(page, state.pages.lastIndex)
        state.globalSearch != null -> CatalogLevel.Search(state.globalSearch)
        else -> CatalogLevel.Home
    }

    // Forget the scroll positions of levels that were closed.
    val openKeys = remember(state.pages) { state.pages.map { "page:${it.id}" }.toSet() }
    var knownKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(openKeys) {
        (knownKeys - openKeys).forEach { saveableHolder.removeState(it) }
        knownKeys = openKeys
    }

    AnimatedContent(
        targetState = level,
        contentKey = { it.key },
        transitionSpec = {
            if (reducedMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                val forward = targetState.depth >= initialState.depth
                val spec = tween<androidx.compose.ui.unit.IntOffset>(240, easing = LuminaMotion.Emphasized)
                (slideInHorizontally(spec) { if (forward) shift else -shift } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(spec) { if (forward) -shift else shift } + fadeOut(tween(120)))
            }
        },
        label = "catalogLevel",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) { target ->
        saveableHolder.SaveableStateProvider(target.key) {
            when (target) {
                CatalogLevel.Home -> CatalogHome(
                    state = state,
                    downloads = downloads,
                    checks = checks,
                    actions = actions,
                    homeActions = homeActions
                )
                is CatalogLevel.Search -> GlobalSearchContent(
                    state = state,
                    search = target.search,
                    downloads = downloads,
                    library = library,
                    actions = actions
                )
                is CatalogLevel.Feed -> FeedContent(
                    state = state,
                    page = target.page,
                    downloads = downloads,
                    library = library,
                    actions = actions
                )
            }
        }
    }

    state.selected?.let { selected ->
        val libraryBookId = remember(selected, library) {
            library.find(selected.publication.title, selected.publication.authors)
        }
        OpdsBookDetailsSheet(
            selected = selected,
            downloads = downloads,
            libraryBookId = libraryBookId,
            callbacks = actions.chipCallbacks(selected.catalog, selected.publication),
            onOpenLink = { link -> actions.onOpenLink(selected.catalog, link) },
            onDismiss = actions.onDismissPublication
        )
    }
}

// ---- Home ------------------------------------------------------------------------

@Composable
private fun CatalogHome(
    state: CatalogUiState,
    downloads: Map<String, DownloadState>,
    checks: Map<String, ConnectionCheck>,
    actions: CatalogScreenActions,
    homeActions: CatalogHomeActions?
) {
    val colors = MaterialTheme.colorScheme
    val meta by DownloadMetaRegistry.meta.collectAsState()
    val rows = remember(downloads, meta) { DownloadUiMapper.rows(downloads, meta) }
    val reducedMotion = rememberReducedMotion()
    val rowActions = remember(actions) {
        DownloadRowActions(
            onCancel = actions.onCancelDownload,
            onRetry = actions.onRetryDownload,
            onDismiss = actions.onDismissDownload,
            onOpen = actions.onOpenBook
        )
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top + 8.dp, bottom = LuminaDimens.DockClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header", contentType = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = LuminaDimens.ScreenGutter, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Каталоги",
                        style = MaterialTheme.typography.displaySmall,
                        color = colors.onBackground,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        text = "Бесплатные и личные OPDS-библиотеки",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (homeActions != null) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = homeActions.onAddCatalog,
                            shape = CircleShape,
                            color = sunkenColor(),
                            contentColor = colors.onSurface,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Add, contentDescription = "Добавить каталог")
                            }
                        }
                    }
                }
            }
        }
        item(key = "search", contentType = "search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CatalogSearchPill(
                    value = state.query,
                    onValueChange = actions.onQueryChange,
                    placeholder = "Искать во всех каталогах",
                    onSearch = actions.onSearch,
                    onClear = actions.onClearSearch,
                    modifier = Modifier.padding(horizontal = LuminaDimens.ScreenGutter)
                )
                ScopeChips(selected = state.scope, onSelect = actions.onScopeSelected)
            }
        }
        item(key = "mine", contentType = "eyebrow") {
            Eyebrow("Мои каталоги", modifier = Modifier.padding(start = LuminaDimens.ScreenGutter, top = 8.dp))
        }
        if (state.catalogs.isEmpty()) {
            item(key = "empty", contentType = "message") {
                CatalogMessageCard(
                    title = "Нет включённых каталогов",
                    message = "Включите встроенные каталоги или добавьте свой OPDS-адрес.",
                    actionLabel = "Настроить каталоги",
                    onAction = actions.onManageCatalogs,
                    modifier = Modifier.padding(horizontal = LuminaDimens.ScreenGutter)
                )
            }
        }
        items(state.catalogs, key = { "catalog:${it.id}" }, contentType = { "catalog" }) { catalog ->
            if (homeActions != null) {
                CatalogCard(
                    catalog = catalog,
                    check = checks[catalog.id],
                    onClick = { actions.onOpenCatalog(catalog) },
                    actions = homeActions.card,
                    modifier = Modifier
                        .padding(horizontal = LuminaDimens.ScreenGutter)
                        .animateItem()
                )
            } else {
                CatalogCard(
                    catalog = catalog,
                    check = checks[catalog.id],
                    onClick = { actions.onOpenCatalog(catalog) },
                    actions = CatalogCardActions({}, {}, {}, {}),
                    modifier = Modifier.padding(horizontal = LuminaDimens.ScreenGutter)
                )
            }
        }
        if (homeActions != null) {
            item(key = "add", contentType = "add") {
                AddCatalogCard(
                    onClick = homeActions.onAddCatalog,
                    modifier = Modifier.padding(horizontal = LuminaDimens.ScreenGutter)
                )
            }
        }
        item(key = "manage", contentType = "manage") {
            TextButton(
                onClick = actions.onManageCatalogs,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Все каталоги и скрытые")
            }
        }
        if (rows.isNotEmpty()) {
            item(key = "downloads_header", contentType = "eyebrow") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = LuminaDimens.ScreenGutter, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Eyebrow("Загрузки", modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onOpenDownloads) { Text("Все загрузки") }
                }
            }
            item(key = "downloads", contentType = "downloads") {
                HairlineCard(modifier = Modifier.padding(horizontal = LuminaDimens.ScreenGutter)) {
                    Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                        rows.take(3).forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(thickness = LuminaDimens.Hairline, color = colors.outlineVariant)
                            }
                            DownloadTaskRow(row = row, actions = rowActions, reducedMotion = reducedMotion, compact = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeChips(selected: CatalogSearchScope, onSelect: (CatalogSearchScope) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = LuminaDimens.ScreenGutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(CatalogSearchScope.entries, key = { it.name }) { scope ->
            val isSelected = scope == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(scope) },
                label = { Text(scope.title) },
                shape = LuminaShape.Pill,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.onBackground,
                    selectedLabelColor = MaterialTheme.colorScheme.background
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    }
}

// ---- Feed --------------------------------------------------------------------------

@Composable
fun CatalogTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Назад",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

@Composable
private fun FeedContent(
    state: CatalogUiState,
    page: CatalogPage,
    downloads: Map<String, DownloadState>,
    library: LibraryIndex,
    actions: CatalogScreenActions
) {
    var searchOpen by rememberSaveable(page.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val crumbs = remember(state.pages) { CatalogUi.breadcrumbs(state.pages) }
    val bottom = LuminaDimens.DockClearance

    Column(modifier = Modifier.fillMaxSize()) {
        CatalogTopBar(
            title = page.title,
            subtitle = page.catalog.name.takeIf { it != page.title },
            onBack = actions.onBack
        ) {
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(Icons.Rounded.Search, contentDescription = "Поиск в «${page.catalog.name}»")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Ещё")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val downloadable = page.downloadablePublications
                    if (downloadable.size > 1) {
                        DropdownMenuItem(
                            text = { Text("Скачать все на странице (${downloadable.size})") },
                            leadingIcon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                actions.onDownloadAll(downloadable.map { page.catalog to it })
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("К списку каталогов") },
                        leadingIcon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            actions.onHome()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Настроить каталоги") },
                        leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            actions.onManageCatalogs()
                        }
                    )
                }
            }
        }
        if (searchOpen) {
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            CatalogSearchPill(
                value = state.query,
                onValueChange = actions.onQueryChange,
                placeholder = state.searchPlaceholder,
                onSearch = {
                    searchOpen = false
                    actions.onSearch()
                },
                onClear = actions.onClearSearch,
                focusRequester = focusRequester,
                modifier = Modifier.padding(horizontal = LuminaDimens.ScreenGutter, vertical = 4.dp)
            )
        }
        CatalogBreadcrumbs(
            crumbs = crumbs,
            onSelect = actions.onPopToPage,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
        ) {
            if (page.isLoading && page.entries.isNotEmpty()) {
                LoadingLine(modifier = Modifier.fillMaxWidth())
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                page.isLoading && page.entries.isEmpty() -> Column {
                    LoadingLine(modifier = Modifier.fillMaxWidth().height(2.dp))
                    CatalogSkeleton()
                }
                page.error != null && page.entries.isEmpty() -> CatalogMessageCard(
                    message = page.error,
                    isError = true,
                    actionLabel = "Повторить",
                    onAction = actions.onRetryPage,
                    modifier = Modifier.padding(LuminaDimens.ScreenGutter)
                )
                page.entries.isEmpty() -> CatalogMessageCard(
                    title = if (page.searchQuery != null) "Ничего не найдено" else "Здесь пока пусто",
                    message = if (page.searchQuery != null) {
                        "Попробуйте другое написание или поиск во всех каталогах."
                    } else {
                        "В этом разделе нет книг и папок."
                    },
                    modifier = Modifier.padding(LuminaDimens.ScreenGutter)
                )
                CatalogUi.isAlphabetFeed(page.entries) -> LetterGrid(page = page, actions = actions, bottom = bottom)
                else -> LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottom)
                ) {
                    items(
                        items = page.entries,
                        key = { it.key },
                        contentType = { if (it is OpdsEntry.Navigation) "folder" else "book" }
                    ) { entry ->
                        EntryItem(
                            catalog = page.catalog,
                            entry = entry,
                            downloads = downloads,
                            library = library,
                            actions = actions
                        )
                    }
                    if (page.nextUrl != null) {
                        item(key = "load_more", contentType = "footer") {
                            LoadMoreFooter(
                                itemCount = page.entries.size,
                                isLoading = page.isLoadingMore,
                                error = page.loadMoreError,
                                onLoadMore = actions.onLoadMore
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterGrid(page: CatalogPage, actions: CatalogScreenActions, bottom: androidx.compose.ui.unit.Dp) {
    val letters = remember(page.entries) { page.entries.filterIsInstance<OpdsEntry.Navigation>() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(56.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = LuminaDimens.ScreenGutter, end = LuminaDimens.ScreenGutter, top = 8.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(letters, key = { it.key }, contentType = { "letter" }) { entry ->
            Box(contentAlignment = Alignment.Center) {
                LetterTile(entry = entry, onClick = { actions.onOpenNavigation(page.catalog, entry) })
            }
        }
        if (page.nextUrl != null) {
            item(key = "load_more", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                LoadMoreFooter(
                    itemCount = page.entries.size,
                    isLoading = page.isLoadingMore,
                    error = page.loadMoreError,
                    onLoadMore = actions.onLoadMore
                )
            }
        }
    }
}

@Composable
private fun EntryItem(
    catalog: OpdsCatalogConfig,
    entry: OpdsEntry,
    downloads: Map<String, DownloadState>,
    library: LibraryIndex,
    actions: CatalogScreenActions
) {
    when (entry) {
        is OpdsEntry.Navigation -> OpdsNavigationRow(
            entry = entry,
            onClick = { actions.onOpenNavigation(catalog, entry) }
        )
        is OpdsEntry.Publication -> {
            val libraryBookId = remember(entry, library) { library.find(entry.title, entry.authors) }
            val chips = remember(entry, downloads, libraryBookId) { FormatChips.of(entry, downloads, libraryBookId) }
            // Credentials only go to the catalogue's own hosts, never to a CDN.
            val authHeaders = remember(catalog, entry.thumbnailUrl) { catalog.authHeadersFor(entry.thumbnailUrl) }
            val authorLink = remember(entry) { CatalogUi.authorLink(entry) }
            Column {
                OpdsEntryRow(
                    publication = entry,
                    authHeaders = authHeaders,
                    chips = chips,
                    callbacks = remember(catalog, entry, actions) { actions.chipCallbacks(catalog, entry) },
                    onClick = { actions.onSelectPublication(catalog, entry) },
                    onOpenAuthor = authorLink?.let { link -> { actions.onOpenLink(catalog, link) } }
                )
                HorizontalDivider(
                    thickness = LuminaDimens.Hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 106.dp, end = LuminaDimens.ScreenGutter)
                )
            }
        }
    }
}

/** Loads the next page when it comes into view; progress line or «Загрузить ещё». */
@Composable
private fun LoadMoreFooter(
    itemCount: Int,
    isLoading: Boolean,
    error: String?,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(itemCount) {
        if (error == null) onLoadMore()
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = LuminaDimens.ScreenGutter, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> LoadingLine(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(3.dp)
            )
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onLoadMore) { Text("Повторить") }
            }
            else -> TextButton(onClick = onLoadMore) { Text("Загрузить ещё") }
        }
    }
}

// ---- Global search -----------------------------------------------------------------

@Composable
private fun GlobalSearchContent(
    state: CatalogUiState,
    search: GlobalSearchState,
    downloads: Map<String, DownloadState>,
    library: LibraryIndex,
    actions: CatalogScreenActions
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CatalogTopBar(
            title = "Поиск по каталогам",
            subtitle = "«${search.query}» · ${search.scope.title.lowercase()}",
            onBack = actions.onBack
        )
        CatalogSearchPill(
            value = state.query,
            onValueChange = actions.onQueryChange,
            placeholder = "Искать во всех каталогах",
            onSearch = actions.onSearch,
            onClear = actions.onClearSearch,
            modifier = Modifier.padding(horizontal = LuminaDimens.ScreenGutter, vertical = 4.dp)
        )
        Spacer(Modifier.height(4.dp))
        ScopeChips(selected = state.scope, onSelect = actions.onScopeSelected)
        Spacer(Modifier.height(4.dp))
        val downloadable = search.downloadablePublications
        val showSeriesBar = search.scope == CatalogSearchScope.SERIES && downloadable.size > 1
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = LuminaDimens.DockClearance)
        ) {
            if (showSeriesBar) {
                item(key = "series_bar", contentType = "series_bar") {
                    SeriesDownloadBar(
                        items = downloadable,
                        downloads = downloads,
                        onDownloadAll = { actions.onDownloadAll(downloadable) }
                    )
                }
            }
            search.sections.forEach { section ->
                item(key = "section_${section.catalog.id}", contentType = "section") {
                    SearchSectionHeader(section = section, onShowAll = { actions.onOpenSearchSection(section) })
                }
                items(
                    items = section.entries,
                    key = { "${section.catalog.id}|${it.key}" },
                    contentType = { if (it is OpdsEntry.Navigation) "folder" else "book" }
                ) { entry ->
                    EntryItem(
                        catalog = section.catalog,
                        entry = entry,
                        downloads = downloads,
                        library = library,
                        actions = actions
                    )
                }
            }
        }
    }
}

/** «Скачать все 7 · FB2», then the counter «3 из 7». */
@Composable
private fun SeriesDownloadBar(
    items: List<Pair<OpdsCatalogConfig, OpdsEntry.Publication>>,
    downloads: Map<String, DownloadState>,
    onDownloadAll: () -> Unit
) {
    val preferred = remember(items) { items.mapNotNull { it.second.preferredAcquisition } }
    val keys = remember(preferred) { preferred.map { it.url } }
    val formatLabel = remember(preferred) {
        preferred.map { OpdsFormats.label(it.format) }.distinct().singleOrNull()
    }
    val (started, done) = CatalogUi.batchCounts(keys, downloads)
    val total = keys.size
    HairlineCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LuminaDimens.ScreenGutter, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (started == 0) {
                Text(
                    text = "Найдено книг серии: $total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onDownloadAll, shape = LuminaShape.Pill) {
                    Text("Скачать все $total" + (formatLabel?.let { " · $it" } ?: ""))
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$done из $total",
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                }
                if (started < total) {
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onDownloadAll) { Text("Докачать") }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    section: CatalogSearchSection,
    onShowAll: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LuminaDimens.ScreenGutter, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CatalogMonogram(name = section.catalog.name, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.catalog.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() }
            )
            val status = when {
                section.isLoading -> "Ищем…"
                section.error != null -> section.error
                else -> "Найдено: ${section.entries.size}" + if (section.nextUrl != null) "+" else ""
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = if (section.error != null && section.entries.isEmpty() && !section.isLoading) {
                    colors.error
                } else {
                    colors.onSurfaceVariant
                }
            )
        }
        if (section.isLoading) {
            LoadingSpinner(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        } else if (section.entries.isNotEmpty()) {
            TextButton(onClick = onShowAll) { Text("Все результаты") }
        }
    }
}

@Composable
fun DeleteCatalogDialog(
    catalog: OpdsCatalogConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить каталог?") },
        text = { Text("Каталог «${catalog.name}» будет удалён из списка. Скачанные книги останутся в библиотеке.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
        shape = LuminaShape.Panel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
}
