package com.lumina.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.library.AppServices
import com.lumina.reader.core.library.LibraryServices
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStatus
import com.lumina.reader.core.preferences.LibraryPreferences
import com.lumina.reader.core.preferences.LibrarySort
import com.lumina.reader.core.preferences.LibraryViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class LibraryViewModel(
    services: LibraryServices = AppServices.library
) : ViewModel() {

    private val bookDao = services.bookDao
    private val imports = services.imports
    private val repository = services.repository
    private val libraryPreferences = services.libraryPreferences
    private val uiPreferences = services.uiPreferences

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedFormat = MutableStateFlow<BookFormat?>(null)
    val selectedFormat = _selectedFormat.asStateFlow()

    // «Полки» is the default view (spec §5.2): shelves of unread books, series,
    // and the collapsed «Прочитано» shelf.
    private val _selectedStatus = MutableStateFlow(ReadingStatus.COLLECTIONS)
    val selectedStatus = _selectedStatus.asStateFlow()

    private val _selectedCollection = MutableStateFlow<String?>(null)
    val selectedCollection = _selectedCollection.asStateFlow()

    private val _selectedSeries = MutableStateFlow<String?>(null)
    val selectedSeries = _selectedSeries.asStateFlow()

    /** True while a local file ("Добавить книгу" / "Открыть с помощью") is being imported. */
    val isLoading: StateFlow<Boolean> = imports.activeImports
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val mutableLibraryLoaded = MutableStateFlow(false)

    /** False until the database delivered the books once (no empty-state flash on start). */
    val libraryLoaded: StateFlow<Boolean> = mutableLibraryLoaded.asStateFlow()

    /** Every book, unfiltered (newest reading first). */
    val allBooks: StateFlow<List<Book>> = bookDao.getAllBooks()
        .onEach { mutableLibraryLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Shelves the user created; they are listed even when empty. */
    val customShelves: StateFlow<List<String>> = libraryPreferences.customShelves
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val collections: StateFlow<List<String>> = combine(
        libraryPreferences.customShelves,
        bookDao.getCollections()
    ) { custom, onBooks -> mergeShelfNames(custom, onBooks) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            listOf(LibraryPreferences.MAIN_SHELF) + LibraryPreferences.DEFAULT_SHELVES
        )

    val seriesNames: StateFlow<List<String>> = bookDao.getSeriesNames().map { names ->
        names.map(::normalizeShelfName)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Distinguishes an empty library from "everything has been read". */
    val hasAnyBooks: StateFlow<Boolean> = allBooks
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val shelfSelection = combine(_selectedCollection, _selectedSeries, ::ShelfSelection)

    /** Typing is debounced; clearing the field applies at once. */
    private val effectiveQuery: Flow<String> = _searchQuery.debounce { query ->
        if (query.isBlank()) 0L else SEARCH_DEBOUNCE_MS
    }

    /** The books of the current tab, search and shelf, computed off the main thread. */
    val books: StateFlow<List<Book>> = combine(
        allBooks,
        effectiveQuery,
        _selectedFormat,
        _selectedStatus,
        shelfSelection
    ) { all, query, format, status, shelf ->
        filterLibraryBooks(all, query, format, status, shelf)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentBooks: StateFlow<List<Book>> = allBooks.map { list ->
        list.filter { it.currentProgressPercent > 0f }.take(3)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- UI state of the redesigned library (spec §5) -------------------------

    /** «Вид»: Полки / Шкаф / Список (persisted in [AppUiPreferences]). */
    val viewMode: StateFlow<LibraryViewMode> = uiPreferences.libraryView

    /** «Сортировка» of the flat views. */
    val sort: StateFlow<LibrarySort> = uiPreferences.librarySort

    /** «Подписи под книгами» on shelf rows. */
    val shelfCaptions: StateFlow<Boolean> = uiPreferences.shelfCaptions

    /** The «Продолжить чтение» book (or the newest unread one). */
    val heroPick: StateFlow<HeroPick?> = allBooks
        .map(::pickHeroBook)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Shelves of the «Полки» view (spec §5.2), grouped off the main thread. */
    val shelves: StateFlow<List<ShelfSection>> = combine(allBooks, customShelves) { all, custom ->
        val hero = pickHeroBook(all)?.takeIf(HeroPick::resume)?.book?.id
        groupIntoShelves(all, hero, custom)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** [books] in the order chosen in «Сортировка» (flat bookcase and list views). */
    val displayBooks: StateFlow<List<Book>> = combine(books, sort) { list, order -> list.sortedForView(order) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Counts for the header subtitle. */
    val summary: StateFlow<LibrarySummary> = allBooks
        .map(::summarizeLibrary)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibrarySummary(0, 0, 0))

    fun onViewModeSelected(mode: LibraryViewMode) {
        uiPreferences.setLibraryView(mode)
    }

    fun onSortSelected(order: LibrarySort) {
        uiPreferences.setLibrarySort(order)
    }

    fun onShelfCaptionsChanged(enabled: Boolean) {
        uiPreferences.setShelfCaptions(enabled)
    }

    init {
        imports.seedWelcomeBookIfNeeded()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onFormatSelected(format: BookFormat?) {
        _selectedFormat.value = if (_selectedFormat.value == format) null else format
    }

    fun onStatusSelected(status: ReadingStatus) {
        _selectedStatus.value = status
    }

    fun onCollectionSelected(coll: String?) {
        _selectedCollection.value = coll?.let(::normalizeShelfName)
        _selectedSeries.value = null
    }

    fun onSeriesSelected(seriesName: String) {
        _selectedCollection.value = null
        _selectedSeries.value = normalizeShelfName(seriesName)
    }

    fun onAllShelvesSelected() {
        _selectedCollection.value = null
        _selectedSeries.value = null
    }

    fun toggleFavorite(book: Book) {
        viewModelScope.launch(Dispatchers.Default) {
            bookDao.updateFavorite(book.id, !book.isFavorite)
        }
    }

    fun toggleCompleted(book: Book) {
        viewModelScope.launch(Dispatchers.Default) {
            bookDao.updateCompleted(book.id, !book.isCompleted)
        }
    }

    fun updateBookOrganization(
        book: Book,
        collection: String,
        seriesName: String,
        seriesOrder: Int
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            val normalizedCollection = normalizeShelfName(collection).ifBlank { LibraryPreferences.MAIN_SHELF }
            val normalizedSeries = normalizeShelfName(seriesName)
            val normalizedOrder = if (normalizedSeries.isBlank()) 0 else seriesOrder.coerceAtLeast(0)

            bookDao.updateOrganization(
                id = book.id,
                collection = normalizedCollection,
                seriesName = normalizedSeries,
                seriesOrder = normalizedOrder
            )

            val message = if (normalizedSeries.isBlank()) {
                "Полка книги обновлена"
            } else {
                val orderLabel = normalizedOrder.takeIf { it > 0 }?.let { " · книга $it" }.orEmpty()
                "Серия «$normalizedSeries»$orderLabel сохранена"
            }
            AppMessages.post(message)
        }
    }

    /** Persists the full series as one ordered operation. */
    fun organizeSeries(seriesName: String, orderedBooks: List<Book>) {
        val normalizedSeries = normalizeShelfName(seriesName)
        if (normalizedSeries.isBlank() || orderedBooks.isEmpty()) return
        viewModelScope.launch {
            if (repository.organizeSeries(normalizedSeries, orderedBooks)) {
                AppMessages.post("Серия «$normalizedSeries» сохранена в правильном порядке")
            }
        }
    }

    /** Deletes the book with its file, cover, bookmarks and highlights. */
    fun deleteBook(book: Book) {
        imports.launchInBackground {
            repository.deleteBook(book)
            // Catalogue rows of this book offer the download again.
            imports.forgetBook(book.id)
            AppMessages.post("Книга «${book.title}» удалена")
        }
    }

    // ---- Shelves -------------------------------------------------------------

    /** Creates a shelf that persists even while it has no books; it shows on «Полки». */
    fun createShelf(name: String) {
        val normalized = normalizeShelfName(name)
        if (normalized.isEmpty()) return
        viewModelScope.launch {
            if (repository.createShelf(normalized, collections.value)) {
                AppMessages.post("Полка «$normalized» создана")
            }
            // The new (empty) shelf is shown on the «Полки» view.
            _selectedStatus.value = ReadingStatus.COLLECTIONS
            onAllShelvesSelected()
        }
    }

    fun renameShelf(oldName: String, newName: String) {
        val target = normalizeShelfName(newName)
        if (target.isEmpty()) return
        viewModelScope.launch {
            if (repository.renameShelf(oldName, target)) {
                if (_selectedCollection.value.equals(normalizeShelfName(oldName), ignoreCase = true)) {
                    _selectedCollection.value = target
                }
                AppMessages.post("Полка переименована в «$target»")
            } else {
                AppMessages.post("Эту полку нельзя переименовать")
            }
        }
    }

    fun deleteShelf(name: String) {
        viewModelScope.launch {
            if (repository.deleteShelf(name)) {
                if (_selectedCollection.value.equals(normalizeShelfName(name), ignoreCase = true)) {
                    _selectedCollection.value = null
                }
                AppMessages.post("Полка «${normalizeShelfName(name)}» удалена, книги перенесены на «${LibraryPreferences.MAIN_SHELF}»")
            } else {
                AppMessages.post("Основную полку удалить нельзя")
            }
        }
    }

    /** Renames a series: every book of it gets the new series name, keeping its place in the order. */
    fun renameSeries(oldName: String, newName: String) {
        val source = normalizeShelfName(oldName)
        val target = normalizeShelfName(newName)
        if (target.isEmpty() || target == source) return
        viewModelScope.launch(Dispatchers.Default) {
            val books = bookDao.getAllBooksOnce().filter { normalizeShelfName(it.seriesName).equals(source, ignoreCase = true) }
            books.forEach { book ->
                bookDao.updateOrganization(book.id, book.collection, target, book.seriesOrder)
            }
            AppMessages.post("Серия переименована в «$target»")
        }
    }

    /** Takes the books out of a series; they stay in the library, on their shelves. */
    fun disbandSeries(name: String) {
        val source = normalizeShelfName(name)
        viewModelScope.launch(Dispatchers.Default) {
            val books = bookDao.getAllBooksOnce().filter { normalizeShelfName(it.seriesName).equals(source, ignoreCase = true) }
            books.forEach { book ->
                bookDao.updateOrganization(book.id, book.collection, "", 0)
            }
            AppMessages.post("Серия «$source» расформирована, книги остались в библиотеке")
        }
    }

    /** True for shelves the user may rename or delete. */
    fun isEditableShelf(name: String?): Boolean =
        name != null && !name.equals(LibraryPreferences.MAIN_SHELF, ignoreCase = true)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
