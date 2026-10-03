package com.lumina.reader.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.download.DownloadRequest
import com.lumina.reader.core.download.DownloadState
import com.lumina.reader.core.library.BookImporter
import com.lumina.reader.core.library.LibraryRepository
import com.lumina.reader.core.library.matchSeriesBooks
import com.lumina.reader.core.library.normalizeTitleForMatch
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.ReadingStats
import com.lumina.reader.core.network.AiClient
import com.lumina.reader.core.network.AiMessage
import com.lumina.reader.core.opds.FoundPublication
import com.lumina.reader.core.opds.OpdsRepository
import com.lumina.reader.core.opds.describeOpdsError
import com.lumina.reader.core.preferences.CatalogPreferences
import com.lumina.reader.ui.downloads.DownloadMeta
import com.lumina.reader.ui.downloads.DownloadMetaRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

sealed class AiAction {
    data class DownloadBook(val query: String) : AiAction()
    data class OrganizeSeries(val seriesName: String, val books: List<String>) : AiAction()
}

/**
 * Chat with the assistant. Commands in the answers ([DOWNLOAD], [ORGANIZE])
 * are executed here, in the app scope of [BookImporter], so leaving the chat
 * does not cancel a search or download; downloads show the usual per-book
 * progress, notifications and snackbars.
 */
class AiChatViewModel(application: Application) : AndroidViewModel(application) {

    private val aiClient = AiClient()
    private val database = AppDatabase.getDatabase(application)
    private val bookDao = database.bookDao()
    private val statsDao = database.readingStatsDao()
    private val importer = BookImporter.get(application)
    private val libraryRepository = LibraryRepository(application)
    private val catalogPreferences = CatalogPreferences(application)
    private val opdsRepository = OpdsRepository()

    private val defaultSystemMessage = "Ты полезный ИИ-ассистент в приложении-читалке Lumina Reader. Ты можешь выполнять команды. ДАННЫЕ БИБЛИОТЕКИ в системном сообщении — единственный источник о том, какие книги уже скачаны: никогда не утверждай, что книга есть у пользователя, если её точного названия нет в этом списке. Для вопросов о числе книг, названиях, порядке серии, авторе, а также перед созданием команды скачивания используй результаты веб-поиска. Если поиск не подтвердил факт, честно скажи, что не можешь его проверить; не дополняй ответ догадками. Если пользователь просит найти или скачать книгу/серию, напиши в самом конце ответа команду [DOWNLOAD:название книги] только для проверенного названия. Ты можешь написать несколько команд [DOWNLOAD] подряд, чтобы скачать несколько книг сразу. Не создавай [DOWNLOAD] для уже скачанных книг. Если пользователь просит серию, перечисляй её в порядке книг и добавляй [ORGANIZE:Название серии:Книга1|Книга2] со всеми подтверждёнными томами серии в правильном порядке — приложение дождётся загрузки и расставит номера. Служебные команды не видны пользователю и выполняются приложением: не называй их «командами», не объясняй их синтаксис и не оставляй перед ними пустые заголовки. В обычном тексте кратко сообщи, что начинаешь поиск или загрузку. Строго отвечай ТОЛЬКО на русском языке! Никогда не используй китайский язык (No Chinese)."

    private val _messages = MutableStateFlow<List<AiMessage>>(
        listOf(AiMessage("system", defaultSystemMessage))
    )

    /**
     * The conversation as shown. Messages with role [ROLE_STATUS] are the app's
     * own progress notes: displayed like assistant messages, never sent to the model.
     */
    val messages = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _downloadCards = MutableStateFlow<Map<Int, String>>(emptyMap())

    /**
     * Download cards of the chat (spec §7.11): index of the status message
     * «Найдена «…». Начинаю загрузку…» in [messages] → the download key
     * (acquisition URL) in [BookImporter.downloads]. UI state only.
     */
    val downloadCards: StateFlow<Map<Int, String>> = _downloadCards.asStateFlow()

    /** Limits parallel catalogue searches when the assistant asks for many books. */
    private val searchSlots = Semaphore(2)

    init {
        // The context uses the whole library (not the filtered library tab) and
        // reads the four statistics directly instead of a full StatsViewModel.
        viewModelScope.launch {
            combine(bookDao.getAllBooks(), statsDao.getAllStats()) { books, stats ->
                buildLibraryContext(books) to buildStatsContext(stats)
            }
                .flowOn(Dispatchers.Default)
                .collect { (libraryContext, statsContext) -> setContext(libraryContext, statsContext) }
        }
    }

    fun setContext(libraryContext: String, statsContext: String) {
        val fullPrompt = buildString {
            appendLine(defaultSystemMessage)
            if (libraryContext.isNotBlank()) {
                appendLine("\nТекущая библиотека пользователя:")
                appendLine(libraryContext.take(MAX_LIBRARY_CONTEXT_CHARS))
            }
            if (statsContext.isNotBlank()) {
                appendLine("\nСтатистика чтения пользователя:")
                appendLine(statsContext)
            }
        }
        _messages.update { current -> listOf(AiMessage("system", fullPrompt)) + current.filter { it.role != "system" } }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank()) return

        _messages.update { it + AiMessage("user", userText) }

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val response = aiClient.askAssistant(
                    messages = messagesForRequest(_messages.value),
                    verifyBibliographicFacts = needsBibliographicVerification(userText)
                )
                _messages.update { it + response }
                executeActions(parseAiActions(response.content))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportExecutionResult("Произошла ошибка: ${e.localizedMessage ?: "нет ответа от сервиса"}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Adds an app status line to the chat; it is not sent to the model. */
    fun reportExecutionResult(message: String) {
        addStatus(message)
    }

    /** Appends a status line and returns its index in [messages] (-1 when blank). */
    private fun addStatus(message: String): Int {
        if (message.isBlank()) return -1
        var index = -1
        _messages.update { current ->
            index = current.size
            current + AiMessage(ROLE_STATUS, message)
        }
        return index
    }

    private fun executeActions(actions: List<AiAction>) {
        if (actions.isEmpty()) return
        val downloads = actions.filterIsInstance<AiAction.DownloadBook>()
        val organizes = actions.filterIsInstance<AiAction.OrganizeSeries>()

        importer.launchInBackground {
            // Downloads first: the series is organised once its books are in the library.
            val downloaded: Map<String, Long> = coroutineScope {
                downloads.map { action ->
                    async { action.query to findAndDownload(action.query) }
                }.awaitAll()
            }.mapNotNull { (query, bookId) -> bookId?.let { normalizeTitleForMatch(query) to it } }
                .toMap()

            organizes.forEach { action -> organizeSeries(action, downloaded) }
        }
    }

    /** Searches the enabled catalogues and downloads the best match; returns its book id. */
    private suspend fun findAndDownload(query: String): Long? {
        reportExecutionResult("Ищу «$query» в каталогах…")
        val found = try {
            searchSlots.withPermit {
                val catalogs = catalogPreferences.catalogs.first().filter { it.enabled }
                opdsRepository.findPublications(query, catalogs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportExecutionResult(
                "Не удалось обратиться к каталогу для «$query»: ${describeOpdsError(e)}. Ничего не было добавлено."
            )
            return null
        }

        val best = pickBestPublication(query, found)
        val acquisition = best?.publication?.preferredAcquisition
        if (best == null || acquisition == null) {
            reportExecutionResult("В доступных каталогах не нашлась книга «$query». Ничего не было добавлено.")
            return null
        }

        val title = best.publication.title
        val statusIndex = addStatus("Найдена «$title». Начинаю загрузку…")
        if (statusIndex >= 0) _downloadCards.update { it + (statusIndex to acquisition.url) }
        val cover = best.publication.thumbnailUrl ?: best.publication.coverUrl
        DownloadMetaRegistry.put(
            acquisition.url,
            DownloadMeta(
                title = title,
                author = best.publication.authorLine,
                coverUrl = cover,
                coverHeaders = best.catalog.authHeadersFor(cover),
                formatLabel = acquisition.label
            )
        )
        val outcome = importer.downloadAndAwait(
            DownloadRequest(
                url = acquisition.url,
                title = title,
                author = best.publication.authorLine,
                formatHint = acquisition.format,
                headers = best.catalog.authHeaders(),
                mirrorBaseUrls = best.catalog.mirrorBaseUrls
            )
        )
        return when (outcome) {
            is DownloadState.Completed -> {
                reportExecutionResult(
                    if (outcome.alreadyInLibrary) "«${outcome.title}» уже есть в библиотеке." else "«${outcome.title}» добавлена в библиотеку."
                )
                outcome.bookId
            }
            is DownloadState.Failed -> {
                reportExecutionResult("Не удалось скачать «$title»: ${outcome.message}")
                null
            }
            else -> null
        }
    }

    private suspend fun organizeSeries(action: AiAction.OrganizeSeries, downloaded: Map<String, Long>) {
        val library = bookDao.getAllBooksOnce()
        val orderedBooks = matchSeriesBooks(action.books, library, downloaded)
        if (orderedBooks.isEmpty()) {
            reportExecutionResult("Книги серии «${action.seriesName}» не найдены в библиотеке.")
            return
        }
        libraryRepository.organizeSeries(action.seriesName, orderedBooks)
        reportExecutionResult(
            if (orderedBooks.size == action.books.size) {
                "Серия «${action.seriesName}» собрана: ${orderedBooks.size} книг расставлены по порядку."
            } else {
                "В серию «${action.seriesName}» добавлено ${orderedBooks.size} из ${action.books.size} книг. Остальные не удалось найти в библиотеке."
            }
        )
    }

    companion object {
        /** Role of the app's own status messages: shown in the chat, never sent to the model. */
        const val ROLE_STATUS = "app_status"
    }
}

/** Only the real conversation (user and model turns) goes to the model, plus the system prompt. */
internal fun messagesForRequest(messages: List<AiMessage>): List<AiMessage> {
    val systemMessage = messages.firstOrNull { it.role == "system" }
    val recentConversation = messages
        .filter { it.role == "user" || it.role == "assistant" }
        .takeLast(MAX_CONVERSATION_MESSAGES)
    return listOfNotNull(systemMessage) + recentConversation
}

internal fun needsBibliographicVerification(userText: String): Boolean {
    val normalized = userText.lowercase()
    return BIBLIOGRAPHIC_QUERY_MARKERS.any(normalized::contains)
}

/** Extracts [DOWNLOAD:...] and [ORGANIZE:series:a|b] commands, downloads first. */
internal fun parseAiActions(content: String): List<AiAction> {
    val downloads = DOWNLOAD_COMMAND.findAll(content)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { AiAction.DownloadBook(it) }
        .toList()
    val organizes = ORGANIZE_COMMAND.findAll(content)
        .mapNotNull { match ->
            val seriesName = match.groupValues[1].trim()
            val books = match.groupValues[2].split("|").map { it.trim() }.filter(String::isNotBlank)
            if (seriesName.isNotBlank() && books.isNotEmpty()) AiAction.OrganizeSeries(seriesName, books) else null
        }
        .toList()
    return downloads + organizes
}

/**
 * The publication to download for an assistant's query: an exact title match
 * with a supported format first, then one whose title contains the query,
 * then any downloadable result.
 */
internal fun pickBestPublication(query: String, found: List<FoundPublication>): FoundPublication? {
    val downloadable = found.filter { it.publication.acquisitions.isNotEmpty() }
    val key = normalizeTitleForMatch(query)
    return downloadable.firstOrNull { normalizeTitleForMatch(it.publication.title) == key }
        ?: downloadable.firstOrNull { normalizeTitleForMatch(it.publication.title).contains(key) }
        ?: downloadable.firstOrNull()
}

internal fun buildLibraryContext(books: List<Book>): String =
    books.joinToString("\n") { book ->
        val series = if (book.seriesName.isNotBlank()) {
            book.seriesName + (book.seriesOrder.takeIf { it > 0 }?.let { " #$it" } ?: "")
        } else {
            "—"
        }
        val progress = when {
            book.isDone() -> "прочитана"
            book.currentProgressPercent > 0f -> "прочитано ${book.currentProgressPercent.roundToInt()}%"
            else -> "не начата"
        }
        "- ${book.title} (${book.author}) [Полка: ${book.collection}, Серия: $series, $progress]"
    }

/** The four numbers the assistant gets, computed like the statistics screen does. */
internal fun buildStatsContext(stats: List<ReadingStats>, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val wordsRead = stats.sumOf { it.wordsReadCount.toLong() }
    val pages = if (wordsRead == 0L) 0L else (wordsRead + WORDS_PER_PAGE - 1) / WORDS_PER_PAGE
    val measured = stats.filter { it.wordsReadCount > 0 && it.sessionDurationSeconds > 0 }
    val measuredSeconds = measured.sumOf { it.sessionDurationSeconds }
    val measuredWords = measured.sumOf { it.wordsReadCount.toLong() }
    val wordsPerMinute = if (measuredSeconds == 0L) 0 else (measuredWords * 60.0 / measuredSeconds).roundToInt()
    val activeDays = stats
        .filter { it.sessionDurationSeconds > 0 || it.wordsReadCount > 0 }
        .map { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }
        .distinct()
        .size
    return """
        Прочитано слов: $wordsRead
        Прочитано страниц: $pages
        Средний темп: $wordsPerMinute сл/мин
        Дней активного чтения: $activeDays
    """.trimIndent()
}

private const val WORDS_PER_PAGE = 250L
private const val MAX_LIBRARY_CONTEXT_CHARS = 12_000
private const val MAX_CONVERSATION_MESSAGES = 12
private val DOWNLOAD_COMMAND = "\\[DOWNLOAD\\s*:\\s*([^\\]\\r\\n]+)\\]".toRegex()
private val ORGANIZE_COMMAND = "\\[ORGANIZE\\s*:\\s*([^:\\]]+)\\s*:\\s*([^\\]\\r\\n]+)\\]".toRegex()
private val BIBLIOGRAPHIC_QUERY_MARKERS = listOf(
    "сколько книг", "серия", "серии", "цикле", "цикл", "порядке",
    "порядок", "том", "книг", "скач", "найди", "автор"
)
