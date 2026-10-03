package com.lumina.reader.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.OpdsLink

/**
 * OPDS catalogues: start screen with the enabled catalogues, browsing with
 * folders and books, search (current catalogue or all), book details and
 * per-book download progress. "Открыть" calls [onOpenBook] with the book id.
 */
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onManageCatalogs: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val downloads by viewModel.downloads.collectAsState()

    BackHandler(enabled = state.selected != null || state.isBrowsing || state.globalSearch != null) {
        viewModel.goBack()
    }

    val actions = remember(viewModel, onOpenBook) {
        CatalogScreenActions(
            onBack = { if (!viewModel.goBack()) onBack() },
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
            onOpenBook = onOpenBook
        )
    }

    CatalogContent(state = state, downloads = downloads, actions = actions)
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
    val onDownload: (OpdsCatalogConfig, OpdsEntry.Publication, com.lumina.reader.core.opds.OpdsAcquisition) -> Unit,
    val onDownloadAll: (List<Pair<OpdsCatalogConfig, OpdsEntry.Publication>>) -> Unit,
    val onRetryDownload: (String) -> Unit,
    val onCancelDownload: (String) -> Unit,
    val onOpenBook: (Long) -> Unit
) {
    fun downloadActions(catalog: OpdsCatalogConfig, publication: OpdsEntry.Publication) = DownloadActions(
        onDownload = { acquisition -> onDownload(catalog, publication, acquisition) },
        onOpenBook = onOpenBook,
        onRetry = onRetryDownload,
        onCancel = onCancelDownload
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogContent(
    state: CatalogUiState,
    downloads: Map<String, DownloadState>,
    actions: CatalogScreenActions
) {
    val page = state.currentPage
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (page != null && page.title != page.catalog.name) {
                            Text(
                                text = page.catalog.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (page != null) {
                        IconButton(onClick = actions.onHome) {
                            Icon(Icons.Default.Home, contentDescription = "К списку каталогов")
                        }
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        val downloadable = page?.downloadablePublications.orEmpty()
                        if (page != null && downloadable.size > 1) {
                            DropdownMenuItem(
                                text = { Text("Скачать все книги на странице (${downloadable.size})") },
                                leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    actions.onDownloadAll(downloadable.map { page.catalog to it })
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Настроить каталоги") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                actions.onManageCatalogs()
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            CatalogSearchBar(
                query = state.query,
                placeholder = state.searchPlaceholder,
                onQueryChange = actions.onQueryChange,
                onSearch = actions.onSearch,
                onClear = actions.onClearSearch
            )
            if (page == null) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(CatalogSearchScope.entries, key = { it.name }) { scope ->
                        FilterChip(
                            selected = state.scope == scope,
                            onClick = { actions.onScopeSelected(scope) },
                            label = { Text(scope.title) }
                        )
                    }
                }
            }

            when {
                page != null -> FeedPageContent(page = page, downloads = downloads, actions = actions)
                state.globalSearch != null -> GlobalSearchContent(
                    search = state.globalSearch,
                    downloads = downloads,
                    actions = actions
                )
                else -> CatalogHome(
                    catalogs = state.catalogs,
                    onOpenCatalog = actions.onOpenCatalog,
                    onManageCatalogs = actions.onManageCatalogs
                )
            }
        }
    }

    state.selected?.let { selected ->
        PublicationDetailsSheet(
            selected = selected,
            downloads = downloads,
            actions = actions.downloadActions(selected.catalog, selected.publication),
            onOpenLink = { link -> actions.onOpenLink(selected.catalog, link) },
            onDismiss = actions.onDismissPublication
        )
    }
}

@Composable
private fun CatalogSearchBar(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Очистить")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            focusManager.clearFocus()
            onSearch()
        }),
        singleLine = true,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun CatalogHome(
    catalogs: List<OpdsCatalogConfig>,
    onOpenCatalog: (OpdsCatalogConfig) -> Unit,
    onManageCatalogs: () -> Unit
) {
    if (catalogs.isEmpty()) {
        MessagePanel(
            message = "Нет включённых каталогов. Включите встроенные или добавьте свой OPDS-каталог.",
            actionLabel = "Настроить каталоги",
            onAction = onManageCatalogs
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "hint") {
            Text(
                text = "Откройте каталог, чтобы листать разделы, или найдите книгу сразу во всех каталогах.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(catalogs, key = { it.id }) { catalog ->
            CatalogCard(catalog = catalog, onClick = { onOpenCatalog(catalog) })
        }
        item(key = "manage") {
            OutlinedButton(
                onClick = onManageCatalogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить или настроить каталоги")
            }
        }
    }
}

@Composable
private fun FeedPageContent(
    page: CatalogPage,
    downloads: Map<String, DownloadState>,
    actions: CatalogScreenActions
) {
    when {
        page.isLoading && page.entries.isEmpty() -> CenteredProgress()
        page.error != null && page.entries.isEmpty() -> MessagePanel(
            message = page.error,
            isError = true,
            actionLabel = "Повторить",
            onAction = actions.onRetryPage
        )
        page.entries.isEmpty() -> MessagePanel(
            message = if (page.searchQuery != null) "Ничего не найдено" else "В этом разделе пока пусто"
        )
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        actions = actions
                    )
                }
                if (page.nextUrl != null) {
                    item(key = "load_more") {
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

@Composable
private fun EntryItem(
    catalog: OpdsCatalogConfig,
    entry: OpdsEntry,
    downloads: Map<String, DownloadState>,
    actions: CatalogScreenActions
) {
    when (entry) {
        is OpdsEntry.Navigation -> NavigationRow(
            entry = entry,
            onClick = { actions.onOpenNavigation(catalog, entry) }
        )
        is OpdsEntry.Publication -> PublicationRow(
            publication = entry,
            // Credentials only go to the catalogue's own hosts, never to a CDN.
            authHeaders = catalog.authHeadersFor(entry.thumbnailUrl),
            download = publicationDownload(entry, downloads),
            actions = actions.downloadActions(catalog, entry),
            onClick = { actions.onSelectPublication(catalog, entry) }
        )
    }
}

@Composable
private fun GlobalSearchContent(
    search: GlobalSearchState,
    downloads: Map<String, DownloadState>,
    actions: CatalogScreenActions
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val downloadable = search.downloadablePublications
        if (search.scope == CatalogSearchScope.SERIES && downloadable.size > 1) {
            item(key = "download_all") {
                Button(
                    onClick = { actions.onDownloadAll(downloadable) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Скачать все найденные книги (${downloadable.size})")
                }
            }
        }
        search.sections.forEach { section ->
            item(key = "section_${section.catalog.id}") {
                SearchSectionHeader(
                    section = section,
                    onShowAll = { actions.onOpenSearchSection(section) }
                )
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
                    actions = actions
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    section: CatalogSearchSection,
    onShowAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.catalog.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (section.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (section.entries.isNotEmpty()) {
            TextButton(onClick = onShowAll) { Text("Все результаты") }
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}
