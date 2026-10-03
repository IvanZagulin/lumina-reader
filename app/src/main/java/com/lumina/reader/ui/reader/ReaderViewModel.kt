package com.lumina.reader.ui.reader

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReadingStats
import com.lumina.reader.core.parser.BookParserFactory
import com.lumina.reader.core.preferences.ReaderPreferences
import com.lumina.reader.core.repository.BookCacheRepository
import com.lumina.reader.core.tts.TtsManager
import com.lumina.reader.core.tts.TtsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class ReaderViewModel(
    application: Application,
    private val bookId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val bookDao = db.bookDao()
    private val bookmarkDao = db.bookmarkDao()
    private val statsDao = db.readingStatsDao()
    private val preferences = ReaderPreferences(application)

    private val storedSettings: Flow<ReaderSettings?> = preferences.settingsFlow

    /**
     * Null until the stored settings have been read: the reader waits for
     * them instead of laying out (and paginating) with defaults first.
     */
    val settings: StateFlow<ReaderSettings?> =
        storedSettings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---- Book ------------------------------------------------------------

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _parsedBook = MutableStateFlow<ParsedBook?>(null)
    val parsedBook: StateFlow<ParsedBook?> = _parsedBook.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _pdfDocument = MutableStateFlow<PdfDocumentRenderer?>(null)
    internal val pdfDocument: StateFlow<PdfDocumentRenderer?> = _pdfDocument.asStateFlow()

    /** Pages measured by the paged reader, shared by every chapter and the book map. */
    internal val pageCache = ChapterPageCache()

    /** Decoded illustrations of this book. */
    internal val imageCache = ReaderImageCache(ReaderImageCache.defaultMaxBytes())

    private val _textInfo = MutableStateFlow(BookTextInfo(IntArray(0), null))

    /** Text lengths and language of the book, computed once off the main thread. */
    internal val textInfo: StateFlow<BookTextInfo> = _textInfo.asStateFlow()

    // ---- Position and navigation ------------------------------------------

    private val _position = MutableStateFlow(ReaderPosition(0, 0, 0))

    /** First visible character of the current page (or line, when scrolling). */
    val position: StateFlow<ReaderPosition> = _position.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _navigationRequest = MutableStateFlow<NavigationRequest?>(null)

    /** A position the viewers should show; acknowledged with [onNavigationHandled]. */
    val navigationRequest: StateFlow<NavigationRequest?> = _navigationRequest.asStateFlow()
    private val navigationIds = AtomicLong(0)

    private val _visibleRange = MutableStateFlow<VisibleRange?>(null)

    /** What the reader shows right now, as reported by the active viewer. */
    val visibleRange: StateFlow<VisibleRange?> = _visibleRange.asStateFlow()

    // ---- Bookmarks and highlights -------------------------------------------

    val bookmarks: StateFlow<List<Bookmark>> = bookmarkDao.getBookmarksForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val highlights: StateFlow<List<ReadingHighlight>> = bookmarkDao.getHighlightsForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True when a bookmark lies on the page (or screen) being shown. */
    val isCurrentPageBookmarked: StateFlow<Boolean> = combine(bookmarks, _visibleRange) { list, range ->
        range != null && list.any { range.contains(it.chapterIndex, it.paragraphIndex, it.charOffset) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Search -------------------------------------------------------------

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _activeSearchMatch = MutableStateFlow<SearchMatch?>(null)

    /** The match the reader jumped to from search, emphasised on its page. */
    val activeSearchMatch: StateFlow<SearchMatch?> = _activeSearchMatch.asStateFlow()
    private var searchJob: Job? = null

    // ---- Time left ------------------------------------------------------------

    private val _wordsPerMinute = MutableStateFlow(DEFAULT_WORDS_PER_MINUTE)

    private class ChapterWords(
        val chapterIndex: Int,
        val plainParagraphs: List<String>,
        val wordCounts: IntArray,
        val totalWords: Int
    )

    @Volatile
    private var cachedChapterWords: ChapterWords? = null

    /** Estimated minutes to the end of the chapter; null when unknown (e.g. PDF). */
    val minutesLeftInChapter: StateFlow<Int?> =
        combine(_position, _parsedBook, _wordsPerMinute) { position, parsed, wordsPerMinute ->
            val chapter = parsed?.chapters?.getOrNull(position.chapterIndex)
            if (chapter == null) {
                null
            } else {
                val words = chapterWords(position.chapterIndex, chapter)
                if (words.totalWords == 0) {
                    null
                } else {
                    estimateMinutesLeft(
                        wordsRemainingInChapter(
                            words.plainParagraphs,
                            words.wordCounts,
                            position.paragraphIndex,
                            position.charOffset
                        ),
                        wordsPerMinute
                    )
                }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---- Text to speech ---------------------------------------------------

    private var ttsManager: TtsManager? = null
    private var ttsStateJob: Job? = null
    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    // ---- Session statistics -------------------------------------------------

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()
    private var sessionStartTime: Long? = null
    private var wordsReadInSession: Int = 0
    private val wordTracker = SessionWordTracker()
    private var progressUpdateJob: Job? = null

    @Volatile
    private var chapterLengths: IntArray = IntArray(0)

    /**
     * Book percentage the paged reader displayed for a position. It is exact
     * once the whole book has been paginated, so the library shows the same
     * number as the reader footer.
     */
    private data class ReportedProgress(
        val position: ReaderPosition,
        val percent: Float
    )

    @Volatile
    private var reportedProgress: ReportedProgress? = null

    init {
        loadBook()
        loadReadingSpeed()
    }

    private fun loadBook() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            withContext(Dispatchers.IO) {
                try {
                    val currentBook = bookDao.getBookById(bookId)
                    if (currentBook == null) {
                        _loadError.value = "Книга не найдена"
                        return@withContext
                    }
                    _book.value = currentBook
                    val file = File(currentBook.filePath)
                    if (!file.isFile) {
                        _loadError.value = "Файл книги больше недоступен"
                        return@withContext
                    }

                    // The cache entry is only used while it matches the file on
                    // disk and the current parser version.
                    val parsed = BookCacheRepository.get(file.absolutePath)
                        ?: BookParserFactory.getParser(currentBook.format).parse(file).also {
                            BookCacheRepository.put(file.absolutePath, it)
                        }
                    if (parsed.chapters.isEmpty()) {
                        _loadError.value = "В книге не найден текст"
                        return@withContext
                    }
                    if (currentBook.format == BookFormat.PDF) {
                        _pdfDocument.value = PdfDocumentRenderer.open(file)
                    }
                    val restored = restoreReaderPosition(
                        chapters = parsed.chapters,
                        chapterIndex = currentBook.currentChapterIndex,
                        paragraphIndex = currentBook.currentParagraphIndex,
                        charOffset = currentBook.currentCharOffset
                    )
                    Log.d(TAG, "Opened book: chapters=${parsed.chapters.size}, images=${parsed.images.size}")
                    val lengths = chapterTextLengths(parsed.chapters)
                    chapterLengths = lengths
                    _textInfo.value = BookTextInfo(
                        chapterLengths = lengths,
                        localeTag = detectTextLocaleTag(bookTextSample(parsed))
                    )
                    val position = restored.position
                    _position.value = position
                    _currentChapterIndex.value = position.chapterIndex
                    _navigationRequest.value = NavigationRequest(
                        id = navigationIds.incrementAndGet(),
                        chapterIndex = position.chapterIndex,
                        paragraphIndex = position.paragraphIndex,
                        charOffset = position.charOffset,
                        toChapterEnd = restored.atChapterEnd,
                        countAsReading = false
                    )
                    _parsedBook.value = parsed
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open book $bookId", e)
                    _loadError.value = "Не удалось открыть книгу: ${e.localizedMessage ?: "ошибка чтения файла"}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun loadReadingSpeed() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessions: List<ReadingStats> = try {
                statsDao.getRecentReadingSessions(limit = 20, minSeconds = 30)
            } catch (e: Exception) {
                emptyList()
            }
            _wordsPerMinute.value = averageWordsPerMinute(sessions)
        }
    }

    // ---- Navigation -----------------------------------------------------------

    /** Opens a chapter at its beginning (table of contents). */
    fun goToChapter(index: Int) {
        navigate(index, 0, 0, toChapterEnd = false, countAsReading = false)
    }

    /**
     * Shows the page that contains [charOffset] of [paragraphIndex]. Used for
     * every jump: search results, bookmarks, the table of contents and page
     * numbers. Jumps never count as reading.
     */
    fun goToPosition(chapterIndex: Int, paragraphIndex: Int = 0, charOffset: Int = 0) {
        navigate(chapterIndex, paragraphIndex, charOffset, toChapterEnd = false, countAsReading = false)
    }

    fun goToBookmark(bookmark: Bookmark) {
        goToPosition(bookmark.chapterIndex, bookmark.paragraphIndex, bookmark.charOffset)
    }

    /** Page turn past the end of a chapter. */
    fun nextChapter() {
        val parsed = _parsedBook.value ?: return
        val next = _currentChapterIndex.value + 1
        if (next < parsed.chapters.size) {
            navigate(next, 0, 0, toChapterEnd = false, countAsReading = true)
        }
    }

    /** Page turn back from the start of a chapter: opens the previous chapter's last page. */
    fun previousChapter() {
        val previous = _currentChapterIndex.value - 1
        if (previous >= 0) {
            navigate(previous, 0, 0, toChapterEnd = true, countAsReading = true)
        }
    }

    private fun navigate(
        chapterIndex: Int,
        paragraphIndex: Int,
        charOffset: Int,
        toChapterEnd: Boolean,
        countAsReading: Boolean
    ) {
        val parsed = _parsedBook.value ?: return
        val chapter = parsed.chapters.getOrNull(chapterIndex) ?: return
        val lastParagraph = chapter.paragraphs.lastIndex.coerceAtLeast(0)
        val paragraph = if (toChapterEnd) lastParagraph else paragraphIndex.coerceIn(0, lastParagraph)
        val offset = if (toChapterEnd) 0 else charOffset.coerceAtLeast(0)
        if (chapterIndex != _currentChapterIndex.value) _visibleRange.value = null
        _position.value = ReaderPosition(chapterIndex, paragraph, offset)
        _currentChapterIndex.value = chapterIndex
        _navigationRequest.value = NavigationRequest(
            id = navigationIds.incrementAndGet(),
            chapterIndex = chapterIndex,
            paragraphIndex = paragraph,
            charOffset = offset,
            toChapterEnd = toChapterEnd,
            countAsReading = countAsReading
        )
        scheduleProgressUpdate()
    }

    /** The viewer has shown the request with [requestId]. */
    fun onNavigationHandled(requestId: Long) {
        _navigationRequest.update { current -> if (current?.id == requestId) null else current }
    }

    /**
     * The viewer shows [range]. The position becomes its first character.
     * Words are counted only when [countWords] is true, i.e. when the reader
     * turned the page or scrolled by hand.
     */
    fun onVisibleRangeChanged(range: VisibleRange, countWords: Boolean) {
        if (range.chapterIndex != _currentChapterIndex.value) return
        _visibleRange.value = range
        val position = ReaderPosition(range.chapterIndex, range.start.paragraphIndex, range.start.charOffset)
        if (_position.value != position) {
            _position.value = position
            scheduleProgressUpdate()
        }
        if (countWords) {
            countVisibleWords(range)
            val match = _activeSearchMatch.value
            if (match != null && !range.contains(match.chapterIndex, match.paragraphIndex, match.start)) {
                _activeSearchMatch.value = null
            }
        }
    }

    /** Called by the paged reader whenever the footer percentage changes. */
    fun onPageProgressChanged(chapterIndex: Int, percent: Float) {
        val position = _position.value
        if (chapterIndex != position.chapterIndex) return
        val progress = ReportedProgress(position, percent.coerceIn(0f, 100f))
        if (reportedProgress == progress) return
        reportedProgress = progress
        scheduleProgressUpdate()
    }

    private fun countVisibleWords(range: VisibleRange) {
        val chapter = _parsedBook.value?.chapters?.getOrNull(range.chapterIndex) ?: return
        if (chapter.paragraphs.isEmpty()) return
        val first = range.start.paragraphIndex.coerceIn(0, chapter.paragraphs.lastIndex)
        val last = minOf(range.end.paragraphIndex, chapter.paragraphs.lastIndex)
        synchronized(sessionLock) {
            var added = 0
            for (paragraphIndex in first..last) {
                val text = paragraphPlainText(chapter.paragraphs[paragraphIndex])
                if (text.isEmpty()) continue
                val start = if (paragraphIndex == range.start.paragraphIndex) range.start.charOffset else 0
                val end = if (paragraphIndex == range.end.paragraphIndex) range.end.charOffset else text.length
                if (end <= start) continue
                added += wordTracker.count(range.chapterIndex, paragraphIndex, text, start, end)
            }
            wordsReadInSession += added.coerceAtMost(MAX_WORDS_PER_REPORT)
        }
    }

    private fun calculateProgress(position: ReaderPosition): Float {
        reportedProgress
            ?.takeIf { it.position == position }
            ?.let { return it.percent }
        val chapters = _parsedBook.value?.chapters ?: return 0f
        return paragraphProgressPercent(
            chapters,
            chapterLengths,
            position.chapterIndex,
            position.paragraphIndex,
            position.charOffset
        )
    }

    private fun scheduleProgressUpdate() {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400) // debounce DB write
            val position = _position.value
            bookDao.updateProgress(
                bookId = bookId,
                chapterIndex = position.chapterIndex,
                paragraphIndex = position.paragraphIndex,
                charOffset = position.charOffset,
                progress = calculateProgress(position)
            )
        }
    }

    // ---- Search -----------------------------------------------------------

    /** Debounced, cancellable search over the whole book. */
    fun onSearchQueryChange(query: String) {
        searchJob?.cancel()
        val parsed = _parsedBook.value
        if (parsed == null || query.trim().length < MIN_SEARCH_QUERY_LENGTH) {
            _searchState.value = SearchState(query = query)
            return
        }
        _searchState.value = _searchState.value.copy(query = query, isSearching = true)
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val outcome = withContext(Dispatchers.Default) {
                searchChapters(parsed.chapters, query) { ensureActive() }
            }
            _searchState.value = SearchState(
                query = query,
                results = outcome.results,
                isSearching = false,
                truncated = outcome.truncated
            )
        }
    }

    /** Jumps to a search result and emphasises the match on its page. */
    fun openSearchResult(result: SearchResult) {
        _activeSearchMatch.value = SearchMatch(
            chapterIndex = result.chapterIndex,
            paragraphIndex = result.paragraphIndex,
            start = result.matchStart,
            end = result.matchEnd
        )
        goToPosition(result.chapterIndex, result.paragraphIndex, result.matchStart)
    }

    fun clearSearchMatch() {
        _activeSearchMatch.value = null
    }

    // ---- Bookmarks ----------------------------------------------------------

    /** Removes the bookmarks on the visible page, or bookmarks its first character. */
    fun toggleBookmarkAtCurrentPosition() {
        val parsed = _parsedBook.value ?: return
        val position = _position.value
        val range = _visibleRange.value
        val onPage = bookmarks.value.filter { bookmark ->
            if (range != null) {
                range.contains(bookmark.chapterIndex, bookmark.paragraphIndex, bookmark.charOffset)
            } else {
                bookmark.chapterIndex == position.chapterIndex &&
                    bookmark.paragraphIndex == position.paragraphIndex &&
                    bookmark.charOffset == position.charOffset
            }
        }
        val chapter = parsed.chapters.getOrNull(position.chapterIndex) ?: return
        val isPdf = _book.value?.format == BookFormat.PDF
        viewModelScope.launch(Dispatchers.IO) {
            if (onPage.isNotEmpty()) {
                onPage.forEach { bookmarkDao.deleteBookmark(it) }
            } else {
                bookmarkDao.insertBookmark(
                    Bookmark(
                        bookId = bookId,
                        chapterIndex = position.chapterIndex,
                        paragraphIndex = position.paragraphIndex,
                        charOffset = position.charOffset,
                        chapterTitle = displayChapterTitle(chapter.title, position.chapterIndex),
                        snippet = bookmarkSnippet(chapter, position.paragraphIndex, position.charOffset, isPdf)
                    )
                )
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.deleteBookmark(bookmark)
        }
    }

    // ---- Highlights -----------------------------------------------------------

    /** Highlights [startOffset, endOffset) of a paragraph's plain text. */
    fun addHighlight(
        chapterIndex: Int,
        paragraphIndex: Int,
        startOffset: Int,
        endOffset: Int,
        text: String,
        colorHex: String = DEFAULT_HIGHLIGHT_HEX,
        note: String? = null
    ) {
        if (endOffset <= startOffset) return
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.insertHighlight(
                ReadingHighlight(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    paragraphIndex = paragraphIndex,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    selectedText = text,
                    colorHex = colorHex,
                    note = note?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
    }

    fun updateHighlightNote(highlightId: Long, note: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.updateHighlightNote(highlightId, note?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    fun deleteHighlight(highlight: ReadingHighlight) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.deleteHighlight(highlight)
        }
    }

    // ---- Settings -------------------------------------------------------------

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            preferences.updateSettings(transform)
        }
    }

    // ---- Text to speech -------------------------------------------------------

    private fun ensureTtsManager(): TtsManager {
        ttsManager?.let { return it }
        val manager = TtsManager(getApplication<Application>())
        ttsManager = manager
        // One collector for the lifetime of the manager.
        ttsStateJob = viewModelScope.launch {
            manager.state.collect { state -> _ttsState.value = state }
        }
        return manager
    }

    fun toggleTts() {
        val parsed = _parsedBook.value ?: return
        val position = _position.value
        val chapter = parsed.chapters.getOrNull(position.chapterIndex) ?: return
        val manager = ensureTtsManager()
        when (manager.state.value) {
            TtsState.IDLE -> {
                val spoken = chapter.paragraphs.map(::paragraphPlainText)
                // The manager skips blank paragraphs, so count only spoken ones.
                val startIndex = spoken
                    .take(position.paragraphIndex.coerceIn(0, spoken.size))
                    .count { it.isNotBlank() }
                manager.play(spoken, startIndex)
            }
            TtsState.PLAYING -> manager.pause()
            TtsState.PAUSED -> manager.resume()
            // Keeps this compiling if the speech engine gains more states.
            else -> Unit
        }
    }

    // ---- Session ------------------------------------------------------------

    fun startSession() {
        synchronized(sessionLock) {
            if (sessionStartTime == null) sessionStartTime = System.currentTimeMillis()
        }
    }

    private class SessionSnapshot(
        val position: ReaderPosition,
        val progressPercent: Float,
        val durationSeconds: Long,
        val words: Int
    )

    fun saveSessionData() {
        progressUpdateJob?.cancel()
        val bookLoaded = _parsedBook.value != null
        val snapshot = synchronized(sessionLock) {
            val startedAt = sessionStartTime ?: return
            val position = _position.value
            SessionSnapshot(
                position = position,
                progressPercent = calculateProgress(position),
                durationSeconds = ((System.currentTimeMillis() - startedAt) / 1_000L).coerceAtLeast(0L),
                words = wordsReadInSession
            ).also {
                // A second ON_STOP/onDispose callback becomes a no-op instead of
                // creating a duplicate session. ON_START begins a fresh interval.
                sessionStartTime = null
                wordsReadInSession = 0
                wordTracker.clear()
            }
        }

        persistenceScope.launch {
            if (bookLoaded) {
                bookDao.updateProgress(
                    bookId = bookId,
                    chapterIndex = snapshot.position.chapterIndex,
                    paragraphIndex = snapshot.position.paragraphIndex,
                    charOffset = snapshot.position.charOffset,
                    progress = snapshot.progressPercent
                )
            }
            if (snapshot.durationSeconds >= 5) {
                statsDao.insertStats(
                    ReadingStats(
                        bookId = bookId,
                        sessionDurationSeconds = snapshot.durationSeconds,
                        wordsReadCount = snapshot.words
                    )
                )
            }
        }
    }

    private fun chapterWords(chapterIndex: Int, chapter: Chapter): ChapterWords {
        cachedChapterWords?.takeIf { it.chapterIndex == chapterIndex }?.let { return it }
        val plain = chapter.paragraphs.map(::paragraphPlainText)
        val counts = IntArray(plain.size) { countWords(plain[it]) }
        return ChapterWords(chapterIndex, plain, counts, counts.sum()).also { cachedChapterWords = it }
    }

    override fun onCleared() {
        saveSessionData()
        searchJob?.cancel()
        ttsStateJob?.cancel()
        ttsManager?.release()
        ttsManager = null
        imageCache.clear()
        val pdf = _pdfDocument.value
        _pdfDocument.value = null
        if (pdf != null) persistenceScope.launch { pdf.close() }
        super.onCleared()
    }

    companion object {
        private const val TAG = "ReaderViewModel"
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** Guards statistics against one huge report (e.g. a fast fling). */
        private const val MAX_WORDS_PER_REPORT = 5_000
        const val DEFAULT_HIGHLIGHT_HEX = "#FFEB3B"
    }
}

class ReaderViewModelFactory(
    private val application: Application,
    private val bookId: Long
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(application, bookId) as T
    }
}
