package com.lumina.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.library.AppServices
import com.lumina.reader.core.library.ReaderServices
import com.lumina.reader.core.library.errorMessageOf
import com.lumina.reader.core.library.isOutOfMemoryError
import com.lumina.reader.core.library.resolveStoredLibraryPath
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Bookmark
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReadingHighlight
import com.lumina.reader.core.model.ReadingStats
import com.lumina.reader.core.tts.ReadAloudController
import com.lumina.reader.core.tts.TtsChapter
import com.lumina.reader.core.tts.TtsChapterSource
import com.lumina.reader.core.tts.TtsPlaybackState
import com.lumina.reader.core.tts.TtsStatus
import com.lumina.reader.platform.AppClock
import com.lumina.reader.platform.LuminaLog
import com.lumina.reader.platform.PlatformLock
import com.lumina.reader.ui.theme.HighlightPalette
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
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The reader of one book. Its database, preferences, book cache and
 * read-aloud come from [ReaderServices] ([AppServices.reader] unless a test
 * passes its own), which each platform builds its own way.
 */
@OptIn(ExperimentalAtomicApi::class)
class ReaderViewModel(
    private val bookId: Long,
    services: ReaderServices = AppServices.reader
) : ViewModel() {

    private val bookDao = services.bookDao
    private val bookmarkDao = services.bookmarkDao
    private val statsDao = services.statsDao
    private val preferences = services.preferences
    private val bookCache = services.bookCache
    private val parserFor = services.parserFor
    private val fileSystem = services.fileSystem
    private val readAloud: ReadAloudController = services.readAloud

    /** Database and file work (Dispatchers.IO). */
    private val io = services.ioDispatcher

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

    /** Words of every chapter, counted once in the background (empty until then). */
    private val _chapterWordCounts = MutableStateFlow(IntArray(0))

    /** Estimated minutes to the end of the book; null until the words are counted. */
    val minutesLeftInBook: StateFlow<Int?> =
        combine(_position, _parsedBook, _wordsPerMinute, _chapterWordCounts) { position, parsed, wordsPerMinute, counts ->
            val chapter = parsed?.chapters?.getOrNull(position.chapterIndex)
            if (chapter == null || counts.size != parsed.chapters.size) {
                null
            } else {
                val words = chapterWords(position.chapterIndex, chapter)
                var remaining = wordsRemainingInChapter(
                    words.plainParagraphs,
                    words.wordCounts,
                    position.paragraphIndex,
                    position.charOffset
                ).toLong()
                for (index in position.chapterIndex + 1 until counts.size) remaining += counts[index]
                estimateMinutesLeft(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), wordsPerMinute)
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---- Session statistics -------------------------------------------------

    private val persistenceScope = CoroutineScope(SupervisorJob() + io)
    private val sessionLock = PlatformLock()
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

    private val _progressPercent = MutableStateFlow(0f)

    /** Book percentage of the current position, for the scrubber and the contents header. */
    val progressPercent: StateFlow<Float> = _progressPercent.asStateFlow()

    init {
        loadBook()
        loadReadingSpeed()
        followReadAloud()
    }

    private fun loadBook() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            withContext(io) {
                try {
                    val currentBook = bookDao.getBookById(bookId)
                    if (currentBook == null) {
                        _loadError.value = "Книга не найдена"
                        return@withContext
                    }
                    _book.value = currentBook
                    // The iPhone stores the path relative to the app container.
                    val path = resolveStoredLibraryPath(currentBook.filePath)
                    if (fileSystem.metadataOrNull(path)?.isRegularFile != true) {
                        _loadError.value = "Файл книги больше недоступен"
                        return@withContext
                    }

                    // The cache entry is only used while it matches the file on
                    // disk and the current parser version.
                    val parsed = bookCache.get(path.toString())
                        ?: parserFor(currentBook.format).parse(path).also {
                            bookCache.put(path.toString(), it)
                        }
                    if (parsed.chapters.isEmpty()) {
                        _loadError.value = "В книге не найден текст"
                        return@withContext
                    }
                    if (currentBook.format == BookFormat.PDF) {
                        _pdfDocument.value = openPdfDocument(path)
                    }
                    val restored = restoreReaderPosition(
                        chapters = parsed.chapters,
                        chapterIndex = currentBook.currentChapterIndex,
                        paragraphIndex = currentBook.currentParagraphIndex,
                        charOffset = currentBook.currentCharOffset
                    )
                    LuminaLog.d(TAG, "Opened book: chapters=${parsed.chapters.size}, images=${parsed.images.size}")
                    val lengths = chapterTextLengths(parsed.chapters)
                    chapterLengths = lengths
                    _textInfo.value = BookTextInfo(
                        chapterLengths = lengths,
                        localeTag = detectTextLocaleTag(bookTextSample(parsed))
                    )
                    val position = restored.position
                    _progressPercent.value = currentBook.currentProgressPercent.coerceIn(0f, 100f)
                    _position.value = position
                    _currentChapterIndex.value = position.chapterIndex
                    _navigationRequest.value = NavigationRequest(
                        id = navigationIds.incrementAndFetch(),
                        chapterIndex = position.chapterIndex,
                        paragraphIndex = position.paragraphIndex,
                        charOffset = position.charOffset,
                        toChapterEnd = restored.atChapterEnd,
                        countAsReading = false
                    )
                    _parsedBook.value = parsed
                    // Off the opening path: the reader is ready before the words are counted.
                    viewModelScope.launch(Dispatchers.Default) {
                        _chapterWordCounts.value = IntArray(parsed.chapters.size) { index ->
                            parsed.chapters[index].paragraphs.sumOf { countWords(paragraphPlainText(it)) }
                        }
                    }
                } catch (e: Throwable) {
                    // OutOfMemoryError exists only on the JVM, so it is told apart
                    // here instead of by a catch clause of its own.
                    if (isOutOfMemoryError(e)) {
                        // Heavily illustrated books can exceed the heap; show an error
                        // instead of letting the Error crash the process.
                        LuminaLog.e(TAG, "Book $bookId is too large to open", e)
                        _book.value?.let { bookCache.remove(resolveStoredLibraryPath(it.filePath).toString()) }
                        _loadError.value = "Книга слишком большая для этого устройства: не хватает памяти, чтобы открыть её"
                    } else if (e is Exception) {
                        LuminaLog.e(TAG, "Failed to open book $bookId", e)
                        _loadError.value = "Не удалось открыть книгу: ${errorMessageOf(e) ?: "ошибка чтения файла"}"
                    } else {
                        throw e
                    }
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    private fun loadReadingSpeed() {
        viewModelScope.launch(io) {
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
        countAsReading: Boolean,
        animate: Boolean = false
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
            id = navigationIds.incrementAndFetch(),
            chapterIndex = chapterIndex,
            paragraphIndex = paragraph,
            charOffset = offset,
            toChapterEnd = toChapterEnd,
            countAsReading = countAsReading,
            animate = animate
        )
        scheduleProgressUpdate()
    }

    /** Jumps to [fraction] of the book (the scrubber of the bottom bar). */
    fun goToBookFraction(fraction: Float) {
        val parsed = _parsedBook.value ?: return
        val target = locateBookFraction(parsed.chapters, chapterLengths, fraction)
        goToPosition(target.chapterIndex, target.paragraphIndex, target.charOffset)
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
        _progressPercent.value = progress.percent
        scheduleProgressUpdate()
    }

    private fun countVisibleWords(range: VisibleRange) {
        val chapter = _parsedBook.value?.chapters?.getOrNull(range.chapterIndex) ?: return
        if (chapter.paragraphs.isEmpty()) return
        val first = range.start.paragraphIndex.coerceIn(0, chapter.paragraphs.lastIndex)
        val last = minOf(range.end.paragraphIndex, chapter.paragraphs.lastIndex)
        sessionLock.withLock {
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
        progressUpdateJob = viewModelScope.launch(io) {
            delay(400) // debounce DB write
            val position = _position.value
            val percent = calculateProgress(position)
            _progressPercent.value = percent
            bookDao.updateProgress(
                bookId = bookId,
                chapterIndex = position.chapterIndex,
                paragraphIndex = position.paragraphIndex,
                charOffset = position.charOffset,
                progress = percent
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

    /** Index of the emphasised match in the current results, or -1. */
    fun activeSearchResultIndex(): Int {
        val match = _activeSearchMatch.value ?: return -1
        return _searchState.value.results.indexOfFirst {
            it.chapterIndex == match.chapterIndex &&
                it.paragraphIndex == match.paragraphIndex &&
                it.matchStart == match.start
        }
    }

    /** Opens the result [delta] places after the emphasised one (search navigator). */
    fun openAdjacentSearchResult(delta: Int) {
        val results = _searchState.value.results
        if (results.isEmpty()) return
        val current = activeSearchResultIndex()
        val target = if (current < 0) 0 else (current + delta).coerceIn(0, results.lastIndex)
        if (target != current) openSearchResult(results[target])
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
        viewModelScope.launch(io) {
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
        viewModelScope.launch(io) {
            bookmarkDao.deleteBookmark(bookmark)
        }
    }

    /** Puts back a bookmark removed a moment ago (undo). */
    fun restoreBookmark(bookmark: Bookmark) {
        viewModelScope.launch(io) {
            bookmarkDao.insertBookmark(bookmark)
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
        viewModelScope.launch(io) {
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
        viewModelScope.launch(io) {
            bookmarkDao.updateHighlightNote(highlightId, note?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    fun deleteHighlight(highlight: ReadingHighlight) {
        viewModelScope.launch(io) {
            bookmarkDao.deleteHighlight(highlight)
        }
    }

    /** Changes the colour of a highlight (the row is replaced in place). */
    fun updateHighlightColor(highlight: ReadingHighlight, colorHex: String) {
        if (highlight.colorHex.equals(colorHex, ignoreCase = true)) return
        viewModelScope.launch(io) {
            bookmarkDao.insertHighlight(highlight.copy(colorHex = colorHex))
        }
    }

    /** Saves a new colour and note of a highlight in one write (the note editor). */
    fun updateHighlight(highlight: ReadingHighlight, colorHex: String, note: String?) {
        viewModelScope.launch(io) {
            bookmarkDao.insertHighlight(
                highlight.copy(colorHex = colorHex, note = note?.trim()?.takeIf { it.isNotEmpty() })
            )
        }
    }

    /** Puts back a highlight removed a moment ago (undo). */
    fun restoreHighlight(highlight: ReadingHighlight) {
        viewModelScope.launch(io) {
            bookmarkDao.insertHighlight(highlight)
        }
    }

    // ---- Settings -------------------------------------------------------------

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            preferences.updateSettings(transform)
        }
    }

    private var pendingSettingsTransform: ((ReaderSettings) -> ReaderSettings)? = null
    private var settingsDebounceJob: Job? = null

    /**
     * For values that repaginate the book and change in quick steps (the
     * font-size slider, A-/A+): the change is written once the steps pause
     * for [SETTINGS_DEBOUNCE_MS], so the page reflows once instead of per step.
     * Transforms arriving meanwhile are applied in order.
     */
    fun updateSettingsDebounced(transform: (ReaderSettings) -> ReaderSettings) {
        val previous = pendingSettingsTransform
        pendingSettingsTransform = if (previous == null) {
            transform
        } else {
            { settings -> transform(previous(settings)) }
        }
        settingsDebounceJob?.cancel()
        settingsDebounceJob = viewModelScope.launch {
            delay(SETTINGS_DEBOUNCE_MS)
            val pending = pendingSettingsTransform ?: return@launch
            pendingSettingsTransform = null
            preferences.updateSettings(pending)
        }
    }

    // ---- Text to speech -------------------------------------------------------

    /** Read-aloud state of the whole app; [isTtsActiveHere] tells whether it is this book. */
    val ttsState: StateFlow<TtsPlaybackState> = readAloud.state

    /** True when [state] belongs to this book and is not idle. */
    fun isTtsActiveHere(state: TtsPlaybackState = readAloud.state.value): Boolean =
        state.bookId == bookId && state.status != TtsStatus.IDLE

    /** «Слушать»: pauses or resumes this book, or starts reading at the current page. */
    fun toggleTts() {
        if (isTtsActiveHere()) {
            readAloud.togglePlayPause()
        } else {
            val position = _position.value
            startTtsAt(position.chapterIndex, position.paragraphIndex)
        }
    }

    /**
     * Starts reading aloud from [paragraphIndex] of [chapterIndex] and on
     * into the following chapters. Playback lives in the app-wide
     * [ReadAloudController] (on Android TtsController and its service), so it
     * continues after the reader is closed.
     */
    fun startTtsAt(chapterIndex: Int, paragraphIndex: Int) {
        val parsed = _parsedBook.value ?: return
        if (_book.value?.format == BookFormat.PDF) return
        val chapter = parsed.chapters.getOrNull(chapterIndex) ?: return
        val current = settings.value
        readAloud.setSpeechRate(current?.ttsSpeed ?: 1f)
        readAloud.setPitch(current?.ttsPitch ?: 1f)
        lastSpokenAnchor = null
        readAloud.start(
            bookId = bookId,
            bookTitle = _book.value?.title.orEmpty(),
            chapter = ttsChapterOf(chapterIndex, chapter),
            startParagraph = paragraphIndex.coerceIn(0, chapter.paragraphs.lastIndex.coerceAtLeast(0)),
            source = BookTtsSource(parsed.chapters)
        )
    }

    fun ttsNextParagraph() {
        if (isTtsActiveHere()) readAloud.nextParagraph()
    }

    fun ttsPreviousParagraph() {
        if (isTtsActiveHere()) readAloud.previousParagraph()
    }

    fun stopTts() {
        if (isTtsActiveHere()) readAloud.stop()
    }

    /** Speech rate, remembered for the next session. */
    fun setTtsSpeed(rate: Float) {
        readAloud.setSpeechRate(rate)
        updateSettings { it.copy(ttsSpeed = rate) }
    }

    fun setTtsPitch(pitch: Float) {
        readAloud.setPitch(pitch)
        updateSettings { it.copy(ttsPitch = pitch) }
    }

    /** Sleep timer in minutes; null switches it off. Replaces «до конца главы». */
    fun setTtsSleepTimer(minutes: Int?) {
        readAloud.setSleepTimer(minutes)
        if (minutes != null) readAloud.setStopAtChapterEnd(false)
    }

    /** «До конца главы»; replaces a running sleep timer. */
    fun setTtsStopAtChapterEnd(enabled: Boolean) {
        readAloud.setStopAtChapterEnd(enabled)
        if (enabled) readAloud.setSleepTimer(null)
    }

    /** Where the previous spoken sentence started, to tell following from browsing. */
    private var lastSpokenAnchor: Pair<Int, TextAnchor>? = null

    /**
     * Keeps the spoken sentence on screen: when reading aloud moves past the
     * page (or into the next chapter) the reader turns with it, unless the
     * reader has paged away from the previous sentence on purpose. The
     * collector lives in viewModelScope and ends with the screen.
     */
    private fun followReadAloud() {
        viewModelScope.launch {
            readAloud.state.collect { state -> onReadAloudProgress(state) }
        }
    }

    private fun onReadAloudProgress(state: TtsPlaybackState) {
        if (state.bookId != bookId || state.status != TtsStatus.PLAYING) return
        val parsed = _parsedBook.value ?: return
        if (state.chapterIndex !in parsed.chapters.indices) return
        val anchor = TextAnchor(state.paragraphIndex, state.sentenceRange?.first ?: 0)
        val spoken = state.chapterIndex to anchor
        val previous = lastSpokenAnchor
        if (previous == spoken) return
        lastSpokenAnchor = spoken
        val range = _visibleRange.value
        if (range != null && isSpokenSentenceVisible(range, state.chapterIndex, state.paragraphIndex, state.sentenceRange)) {
            return
        }
        val wasFollowing = shouldFollowReadAloud(previous, range)
        if (!wasFollowing) return
        val turnsPage = range != null && state.chapterIndex == range.chapterIndex && anchor >= range.end
        navigate(
            chapterIndex = state.chapterIndex,
            paragraphIndex = anchor.paragraphIndex,
            charOffset = anchor.charOffset,
            toChapterEnd = false,
            countAsReading = false,
            animate = turnsPage
        )
    }

    // ---- Session ------------------------------------------------------------

    fun startSession() {
        sessionLock.withLock {
            if (sessionStartTime == null) sessionStartTime = AppClock.nowMillis()
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
        val snapshot = sessionLock.withLock {
            val startedAt = sessionStartTime ?: return@withLock null
            val position = _position.value
            SessionSnapshot(
                position = position,
                progressPercent = calculateProgress(position),
                durationSeconds = ((AppClock.nowMillis() - startedAt) / 1_000L).coerceAtLeast(0L),
                words = wordsReadInSession
            ).also {
                // A second ON_STOP/onDispose callback becomes a no-op instead of
                // creating a duplicate session. ON_START begins a fresh interval.
                sessionStartTime = null
                wordsReadInSession = 0
                wordTracker.clear()
            }
        } ?: return

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
        settingsDebounceJob?.cancel()
        // A debounced setting is still written: the scope below outlives the view model.
        pendingSettingsTransform?.let { pending ->
            pendingSettingsTransform = null
            persistenceScope.launch { preferences.updateSettings(pending) }
        }
        // Read-aloud keeps playing in the background on purpose; it is
        // stopped from its notification or the mini player.
        imageCache.clear()
        val pdf = _pdfDocument.value
        _pdfDocument.value = null
        if (pdf != null) persistenceScope.launch { pdf.close() }
        super.onCleared()
    }

    companion object {
        private const val TAG = "ReaderViewModel"
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val SETTINGS_DEBOUNCE_MS = 120L

        /** Guards statistics against one huge report (e.g. a fast fling). */
        private const val MAX_WORDS_PER_REPORT = 5_000
        /** «Жёлтый» of the highlight palette; the legacy #FFEB3B is drawn the same. */
        val DEFAULT_HIGHLIGHT_HEX: String = HighlightPalette.Yellow.hex
    }
}

/** Chapter [index] as read aloud: the original paragraphs, so indices match the pages. */
private fun ttsChapterOf(index: Int, chapter: Chapter): TtsChapter =
    TtsChapter(index = index, title = displayChapterTitle(chapter.title, index), paragraphs = chapter.paragraphs)

/**
 * Supplies the following chapters to read-aloud. Holds only the chapter list
 * (never the view model), so playback may outlive the reader screen.
 */
private class BookTtsSource(private val chapters: List<Chapter>) : TtsChapterSource {
    override suspend fun chapter(index: Int): TtsChapter? =
        chapters.getOrNull(index)?.let { ttsChapterOf(index, it) }
}
