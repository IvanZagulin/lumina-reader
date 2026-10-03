package com.lumina.reader.ui.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.library.BookImporter
import com.lumina.reader.core.opds.OpdsAcquisition
import com.lumina.reader.core.opds.OpdsCatalogConfig
import com.lumina.reader.core.opds.OpdsEntry
import com.lumina.reader.core.opds.OpdsLink
import com.lumina.reader.core.opds.OpdsRepository
import com.lumina.reader.core.opds.describeOpdsError
import com.lumina.reader.core.preferences.CatalogPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * OPDS browsing: a start screen with the enabled catalogues, a stack of
 * feeds per catalogue (folders open sub-feeds, back returns), pagination via
 * the feed's "next" link, search inside the current catalogue and across all
 * of them. Downloads run in the app-scoped [BookImporter], so they continue
 * after the screen is closed.
 */
class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = OpdsRepository()
    private val importer = BookImporter.get(application)
    private val catalogPreferences = CatalogPreferences(application)

    private val mutableState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = mutableState.asStateFlow()

    /** Download state per acquisition URL. */
    val downloads: StateFlow<Map<String, DownloadState>> = importer.downloads

    private var nextPageId = 1L
    private var pageJob: Job? = null
    private var loadMoreJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            catalogPreferences.catalogs.collect { all ->
                mutableState.update { state -> state.copy(catalogs = all.filter { it.enabled }) }
            }
        }
    }

    // ---- Navigation ----------------------------------------------------------

    fun openCatalog(catalog: OpdsCatalogConfig) {
        pushPage(catalog = catalog, title = catalog.name, url = catalog.url)
    }

    fun openNavigation(catalog: OpdsCatalogConfig, entry: OpdsEntry.Navigation) {
        pushPage(catalog = catalog, title = entry.title, url = entry.url)
    }

    /** Opens a related feed of a book ("all books of the author", "the series"). */
    fun openLink(catalog: OpdsCatalogConfig, link: OpdsLink) {
        mutableState.update { it.copy(selected = null) }
        pushPage(catalog = catalog, title = link.title ?: catalog.name, url = link.href)
    }

    /** Shows all results of one catalogue from the global search as a page with pagination. */
    fun openSearchSection(section: CatalogSearchSection) {
        val query = mutableState.value.globalSearch?.query.orEmpty()
        val page = CatalogPage(
            id = nextPageId++,
            catalog = section.catalog,
            title = "${section.catalog.name}: $query",
            url = section.feedUrl ?: section.catalog.url,
            entries = section.entries,
            nextUrl = section.nextUrl,
            searchQuery = query,
            isLoaded = true
        )
        mutableState.update { it.copy(pages = it.pages + page) }
    }

    /**
     * Handles "back" inside the screen: closes the details sheet, then the
     * current feed, then the global search. Returns false when there is
     * nothing left to close (the screen itself should close).
     */
    fun goBack(): Boolean {
        val state = mutableState.value
        return when {
            state.selected != null -> {
                mutableState.update { it.copy(selected = null) }
                true
            }
            state.pages.isNotEmpty() -> {
                pageJob?.cancel()
                loadMoreJob?.cancel()
                val remaining = state.pages.dropLast(1)
                mutableState.update { it.copy(pages = remaining) }
                remaining.lastOrNull()?.let { top -> if (!top.isLoaded) loadPage(top.id) }
                true
            }
            state.globalSearch != null -> {
                searchJob?.cancel()
                mutableState.update { it.copy(globalSearch = null) }
                true
            }
            else -> false
        }
    }

    /** Leaves the catalogue completely and returns to the start screen. */
    fun closeCatalog() {
        pageJob?.cancel()
        loadMoreJob?.cancel()
        mutableState.update { it.copy(pages = emptyList(), selected = null) }
    }

    fun retryPage() {
        mutableState.value.currentPage?.let { loadPage(it.id) }
    }

    fun loadMore() {
        val page = mutableState.value.currentPage ?: return
        val next = page.nextUrl ?: return
        if (page.isLoading || page.isLoadingMore) return
        updatePage(page.id) { it.copy(isLoadingMore = true, loadMoreError = null) }
        loadMoreJob = viewModelScope.launch {
            try {
                val feed = repository.fetchFeed(next, page.catalog)
                updatePage(page.id) { current ->
                    current.copy(
                        entries = appendUniqueEntries(current.entries, feed.entries),
                        // Guard against feeds whose "next" points to themselves.
                        nextUrl = feed.nextUrl?.takeIf { it != next && it != current.url },
                        isLoadingMore = false
                    )
                }
            } catch (e: CancellationException) {
                updatePage(page.id) { it.copy(isLoadingMore = false) }
                throw e
            } catch (e: Exception) {
                updatePage(page.id) { it.copy(isLoadingMore = false, loadMoreError = describeOpdsError(e)) }
            }
        }
    }

    private fun pushPage(catalog: OpdsCatalogConfig, title: String, url: String, searchQuery: String? = null) {
        val parentSearchLink = mutableState.value.currentPage
            ?.takeIf { it.catalog.id == catalog.id }
            ?.searchLink
        val page = CatalogPage(
            id = nextPageId++,
            catalog = catalog,
            title = title,
            url = url,
            searchLink = parentSearchLink,
            searchQuery = searchQuery,
            isLoading = true
        )
        mutableState.update { it.copy(pages = it.pages + page) }
        loadPage(page.id)
    }

    private fun loadPage(pageId: Long) {
        val page = findPage(pageId) ?: return
        pageJob?.cancel()
        updatePage(pageId) { it.copy(isLoading = true, error = null) }
        pageJob = viewModelScope.launch {
            try {
                val query = page.searchQuery
                val feed = when {
                    query == null -> repository.fetchFeed(page.url, page.catalog)
                    page.searchLink != null -> repository.searchWithLink(page.searchLink, page.catalog, query)
                    else -> repository.searchCatalog(page.catalog, query, mutableState.value.scope.searchType)
                }
                updatePage(pageId) { current ->
                    current.copy(
                        title = current.title.ifBlank { feed.title.ifBlank { current.catalog.name } },
                        url = feed.url,
                        entries = appendUniqueEntries(emptyList(), feed.entries),
                        nextUrl = feed.nextUrl?.takeIf { it != feed.url },
                        searchLink = feed.searchLink ?: current.searchLink,
                        isLoading = false,
                        isLoaded = true,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePage(pageId) { it.copy(isLoading = false, error = describeOpdsError(e)) }
            }
        }
    }

    private fun findPage(pageId: Long): CatalogPage? = mutableState.value.pages.firstOrNull { it.id == pageId }

    private fun updatePage(pageId: Long, transform: (CatalogPage) -> CatalogPage) {
        mutableState.update { state ->
            state.copy(pages = state.pages.map { if (it.id == pageId) transform(it) else it })
        }
    }

    // ---- Search ----------------------------------------------------------------

    fun onQueryChange(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun onScopeSelected(scope: CatalogSearchScope) {
        mutableState.update { it.copy(scope = scope) }
    }

    /** Searches the current catalogue while browsing, otherwise all enabled catalogues. */
    fun search() {
        val state = mutableState.value
        val query = state.query.trim()
        if (query.isEmpty()) return
        val page = state.currentPage
        if (page != null) {
            pushPage(catalog = page.catalog, title = "Поиск: $query", url = page.url, searchQuery = query)
        } else {
            runGlobalSearch(query, state.scope)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        mutableState.update { it.copy(query = "", globalSearch = null) }
    }

    private fun runGlobalSearch(query: String, scope: CatalogSearchScope) {
        searchJob?.cancel()
        val catalogs = mutableState.value.catalogs
        if (catalogs.isEmpty()) {
            AppMessages.post("Нет включённых каталогов. Включите или добавьте каталог в настройках.")
            return
        }
        mutableState.update {
            it.copy(
                globalSearch = GlobalSearchState(
                    query = query,
                    scope = scope,
                    sections = catalogs.map { catalog -> CatalogSearchSection(catalog = catalog, isLoading = true) }
                )
            )
        }
        searchJob = viewModelScope.launch {
            // Each catalogue reports as soon as it answers.
            catalogs.forEach { catalog ->
                launch {
                    val result = repository.searchAll(listOf(catalog), query, scope.searchType).first()
                    val feed = result.feed
                    val section = CatalogSearchSection(
                        catalog = catalog,
                        entries = feed?.entries?.let { appendUniqueEntries(emptyList(), it) }.orEmpty(),
                        nextUrl = feed?.nextUrl,
                        feedUrl = feed?.url,
                        isLoading = false,
                        error = result.error
                            ?: if (feed != null && feed.entries.isEmpty()) "Ничего не найдено" else null
                    )
                    mutableState.update { state ->
                        val search = state.globalSearch ?: return@update state
                        if (search.query != query) return@update state
                        state.copy(
                            globalSearch = search.copy(
                                sections = search.sections.map { if (it.catalog.id == catalog.id) section else it }
                            )
                        )
                    }
                }
            }
        }
    }

    // ---- Books and downloads ---------------------------------------------------

    fun selectPublication(catalog: OpdsCatalogConfig, publication: OpdsEntry.Publication) {
        mutableState.update { it.copy(selected = SelectedPublication(catalog, publication)) }
    }

    fun dismissPublication() {
        mutableState.update { it.copy(selected = null) }
    }

    fun download(catalog: OpdsCatalogConfig, publication: OpdsEntry.Publication, acquisition: OpdsAcquisition) {
        importer.download(requestFor(catalog, publication, acquisition))
    }

    /** Downloads the preferred format of every given book ("Скачать все"). */
    fun downloadAll(items: List<Pair<OpdsCatalogConfig, OpdsEntry.Publication>>) {
        var started = 0
        items.forEach { (catalog, publication) ->
            val acquisition = publication.preferredAcquisition ?: return@forEach
            if (importer.download(requestFor(catalog, publication, acquisition))) started++
        }
        AppMessages.post(
            if (started > 0) "Скачивание начато: $started ${bookWord(started)}" else "Все книги уже скачиваются или скачаны"
        )
    }

    fun retryDownload(url: String) {
        importer.retry(url)
    }

    fun cancelDownload(url: String) {
        importer.cancel(url)
    }

    private fun requestFor(
        catalog: OpdsCatalogConfig,
        publication: OpdsEntry.Publication,
        acquisition: OpdsAcquisition
    ) = DownloadRequest(
        url = acquisition.url,
        title = publication.title,
        author = publication.authorLine,
        formatHint = acquisition.format,
        headers = catalog.authHeaders(),
        mirrorBaseUrls = catalog.mirrorBaseUrls
    )

    private fun bookWord(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> "книг"
            mod10 == 1 -> "книга"
            mod10 in 2..4 -> "книги"
            else -> "книг"
        }
    }
}
