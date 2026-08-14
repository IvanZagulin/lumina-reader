package com.lumina.reader.ui.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.model.*
import com.lumina.reader.core.parser.BookParserFactory
import com.lumina.reader.core.preferences.ReaderPreferences
import com.lumina.reader.core.tts.TtsManager
import com.lumina.reader.core.tts.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File

class ReaderViewModel(
    application: Application,
    private val bookId: Long
) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val bookDao = db.bookDao()
    private val bookmarkDao = db.bookmarkDao()
    private val statsDao = db.readingStatsDao()
    private val preferences = ReaderPreferences(application)

    private var _ttsManager: TtsManager? = null
    private val ttsManager: TtsManager
        get() {
            if (_ttsManager == null) {
                _ttsManager = TtsManager(getApplication())
            }
            return _ttsManager!!
        }

    val settings: StateFlow<ReaderSettings> = preferences.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ReaderSettings()
    )

    private val _ttsState = MutableStateFlow(TtsState.IDLE)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _book = MutableStateFlow<Book?>(null)
    val book = _book.asStateFlow()

    private val _parsedBook = MutableStateFlow<ParsedBook?>(null)
    val parsedBook = _parsedBook.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex = _currentChapterIndex.asStateFlow()

    private val _currentParagraphIndex = MutableStateFlow(0)
    val currentParagraphIndex = _currentParagraphIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = bookmarkDao.getBookmarksForBook(bookId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val highlights: StateFlow<List<ReadingHighlight>> = bookmarkDao.getHighlightsForBook(bookId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()
    private var sessionStartTime: Long? = null
    private var wordsReadInSession: Int = 0
    private val countedTextFragments = mutableSetOf<Triple<Int, Int, Int>>()
    private var progressUpdateJob: Job? = null

    init {
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            _isLoading.value = true
            withContext(Dispatchers.IO) {
                try {
                    val currentBook = bookDao.getBookById(bookId)
                    if (currentBook != null) {
                        _book.value = currentBook
                        _currentChapterIndex.value = currentBook.currentChapterIndex
                        _currentParagraphIndex.value = currentBook.currentParagraphIndex

                        val file = File(currentBook.filePath)
                        if (file.exists()) {
                            // Always clear cache to force fresh parse after app update
                            com.lumina.reader.core.repository.BookCacheRepository.remove(file.absolutePath)
                            val parser = BookParserFactory.getParser(currentBook.format)
                            val parsed = parser.parse(file)
                            Log.d("ReaderViewModel", "Parsed book: chapters=${parsed.chapters.size}, images=${parsed.images.size} keys=${parsed.images.keys.joinToString(",")}")
                            com.lumina.reader.core.repository.BookCacheRepository.put(file.absolutePath, parsed)
                            _parsedBook.value = parsed
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun goToChapter(index: Int) {
        val parsed = _parsedBook.value ?: return
        if (index in parsed.chapters.indices) {
            _currentChapterIndex.value = index
            _currentParagraphIndex.value = 0
            scheduleProgressUpdate(index, 0)
        }
    }

    fun nextChapter() {
        val parsed = _parsedBook.value ?: return
        if (_currentChapterIndex.value < parsed.chapters.size - 1) {
            goToChapter(_currentChapterIndex.value + 1)
        }
    }

    fun previousChapter() {
        if (_currentChapterIndex.value > 0) {
            val parsed = _parsedBook.value ?: return
            _currentChapterIndex.value -= 1
            _currentParagraphIndex.value = Int.MAX_VALUE
            scheduleProgressUpdate(_currentChapterIndex.value, 0)
        }
    }

    fun onParagraphVisible(paragraphIndex: Int) {
        val chapterIndex = _currentChapterIndex.value
        val parsed = _parsedBook.value
        val chapter = parsed?.chapters?.getOrNull(chapterIndex)
        val paragraph = chapter?.paragraphs?.getOrNull(paragraphIndex).orEmpty()

        countTextFragment(
            chapterIndex = chapterIndex,
            paragraphIndex = paragraphIndex,
            fragmentIndex = 0,
            text = paragraph
        )
        updateVisiblePosition(chapterIndex, paragraphIndex)
    }

    /**
     * Paged mode can split one source paragraph across several measured pages.
     * Count only the fragment that was actually shown instead of crediting the
     * complete source paragraph as soon as its first line becomes visible.
     */
    fun onParagraphFragmentVisible(paragraphIndex: Int, fragmentIndex: Int, text: String) {
        val chapterIndex = _currentChapterIndex.value
        countTextFragment(chapterIndex, paragraphIndex, fragmentIndex, text)
        updateVisiblePosition(chapterIndex, paragraphIndex)
    }

    private fun countTextFragment(
        chapterIndex: Int,
        paragraphIndex: Int,
        fragmentIndex: Int,
        text: String
    ) {
        synchronized(sessionLock) {
            if (countedTextFragments.add(Triple(chapterIndex, paragraphIndex, fragmentIndex))) {
                val wordCount = if (
                    text.startsWith("[IMG:") && text.endsWith("]")
                ) {
                    0
                } else {
                    text.trim()
                        .split(Regex("\\s+"))
                        .count { it.isNotBlank() }
                }
                wordsReadInSession += wordCount.coerceIn(0, 2_000)
            }
        }
    }

    private fun updateVisiblePosition(chapterIndex: Int, paragraphIndex: Int) {
        if (_currentParagraphIndex.value != paragraphIndex) {
            _currentParagraphIndex.value = paragraphIndex
            scheduleProgressUpdate(chapterIndex, paragraphIndex)
        }
    }

    private fun calculateProgress(chapterIndex: Int, paragraphIndex: Int): Float {
        val parsed = _parsedBook.value
        val totalChapters = (parsed?.chapters?.size ?: 1).coerceAtLeast(1)
        val paragraphCount = parsed?.chapters
            ?.getOrNull(chapterIndex)
            ?.paragraphs
            ?.size
            ?.coerceAtLeast(1)
            ?: 1
        val chapterFraction = if (paragraphIndex == Int.MAX_VALUE) {
            1f
        } else {
            paragraphIndex.coerceIn(0, paragraphCount).toFloat() / paragraphCount.toFloat()
        }
        return ((chapterIndex.coerceAtLeast(0) + chapterFraction) / totalChapters.toFloat() * 100f)
            .coerceIn(0f, 100f)
    }

    private fun scheduleProgressUpdate(chapterIndex: Int, paragraphIndex: Int) {
        progressUpdateJob?.cancel()
        progressUpdateJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400) // debounce DB write
            val progressPercent = calculateProgress(chapterIndex, paragraphIndex)
            bookDao.updateProgress(bookId, chapterIndex, paragraphIndex, progressPercent)
        }
    }

    fun addBookmark() {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = _parsedBook.value ?: return@launch
            val chIndex = _currentChapterIndex.value
            val currentChapter = parsed.chapters.getOrNull(chIndex) ?: return@launch
            val pIndex = _currentParagraphIndex.value
            val snippet = currentChapter.paragraphs.getOrNull(pIndex)
                ?: currentChapter.paragraphs.firstOrNull()
                ?: "Закладка"

            val bookmark = Bookmark(
                bookId = bookId,
                chapterIndex = chIndex,
                paragraphIndex = pIndex,
                chapterTitle = currentChapter.title,
                snippet = snippet.take(120)
            )
            bookmarkDao.insertBookmark(bookmark)
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.deleteBookmark(bookmark)
        }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            preferences.updateSettings(transform)
        }
    }

    fun toggleTts() {
        val parsed = _parsedBook.value ?: return
        val currentChapter = parsed.chapters.getOrNull(_currentChapterIndex.value) ?: return

        when (ttsManager.state.value) {
            TtsState.IDLE -> {
                ttsManager.play(currentChapter.paragraphs, _currentParagraphIndex.value)
                viewModelScope.launch {
                    ttsManager.state.collect { state ->
                        _ttsState.value = state
                    }
                }
            }
            TtsState.PLAYING -> {
                ttsManager.pause()
            }
            TtsState.PAUSED -> {
                ttsManager.resume()
            }
        }
    }

    fun startSession() {
        synchronized(sessionLock) {
            if (sessionStartTime == null) sessionStartTime = System.currentTimeMillis()
        }
    }

    fun saveSessionData() {
        progressUpdateJob?.cancel()

        data class SessionSnapshot(
            val chapterIndex: Int,
            val paragraphIndex: Int,
            val progressPercent: Float,
            val durationSeconds: Long,
            val words: Int
        )

        val snapshot = synchronized(sessionLock) {
            val startedAt = sessionStartTime ?: return
            val chapterIndex = _currentChapterIndex.value
            val paragraphIndex = _currentParagraphIndex.value
            SessionSnapshot(
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                progressPercent = calculateProgress(chapterIndex, paragraphIndex),
                durationSeconds = ((System.currentTimeMillis() - startedAt) / 1_000L).coerceAtLeast(0L),
                words = wordsReadInSession
            ).also {
                // A second ON_STOP/onDispose callback becomes a no-op instead of
                // creating a duplicate session. ON_START begins a fresh interval.
                sessionStartTime = null
                wordsReadInSession = 0
                countedTextFragments.clear()
            }
        }

        persistenceScope.launch {
            bookDao.updateProgress(
                bookId,
                snapshot.chapterIndex,
                snapshot.paragraphIndex,
                snapshot.progressPercent
            )
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

    override fun onCleared() {
        saveSessionData()
        _ttsManager?.release()
        _ttsManager = null
        super.onCleared()
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
