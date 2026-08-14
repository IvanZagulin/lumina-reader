package com.lumina.reader.ui.library

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.ReadingStatus
import com.lumina.reader.core.parser.BookParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

internal data class ShelfSelection(
    val collection: String? = null,
    val seriesName: String? = null
)

internal fun normalizeShelfName(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")

internal val seriesBookComparator: Comparator<Book> = compareBy<Book>(
    { if (it.seriesOrder > 0) 0 else 1 },
    { if (it.seriesOrder > 0) it.seriesOrder else Int.MAX_VALUE },
    { it.title.lowercase() }
)

internal fun List<Book>.sortedForSeries(): List<Book> = sortedWith(seriesBookComparator)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val bookDao = db.bookDao()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _selectedFormat = MutableStateFlow<BookFormat?>(null)
    val selectedFormat = _selectedFormat.asStateFlow()

    private val _selectedStatus = MutableStateFlow(ReadingStatus.ALL)
    val selectedStatus = _selectedStatus.asStateFlow()

    private val _selectedCollection = MutableStateFlow<String?>(null)
    val selectedCollection = _selectedCollection.asStateFlow()

    private val _selectedSeries = MutableStateFlow<String?>(null)
    val selectedSeries = _selectedSeries.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage = _userMessage.asSharedFlow()

    val collections: StateFlow<List<String>> = bookDao.getCollections().map { list ->
        val result = list.filter { it.isNotBlank() }.toMutableList()
        if (!result.contains("Основная")) result.add(0, "Основная")
        if (!result.contains("Избранное")) result.add("Избранное")
        if (!result.contains("Учеба")) result.add("Учеба")
        if (!result.contains("Художественная")) result.add("Художественная")
        result.distinct()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("Основная", "Избранное", "Учеба", "Художественная"))

    val seriesNames: StateFlow<List<String>> = bookDao.getSeriesNames().map { names ->
        names.map(::normalizeShelfName)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val shelfSelection = combine(_selectedCollection, _selectedSeries, ::ShelfSelection)

    val books: StateFlow<List<Book>> = combine(
        bookDao.getAllBooks(),
        _searchQuery,
        _selectedFormat,
        _selectedStatus,
        shelfSelection
    ) { allBooks, query, format, status, shelf ->
        var filtered = allBooks

        // 1. Filter by Status / Tabs
        filtered = when (status) {
            ReadingStatus.ALL -> filtered
            ReadingStatus.READING -> filtered.filter { it.currentProgressPercent > 0f && !it.isCompleted }
            ReadingStatus.FAVORITES -> filtered.filter { it.isFavorite }
            ReadingStatus.COMPLETED -> filtered.filter { it.isCompleted || it.currentProgressPercent >= 99f }
            ReadingStatus.COLLECTIONS -> {
                when {
                    shelf.seriesName != null -> filtered
                        .filter { it.seriesName.equals(shelf.seriesName, ignoreCase = true) }
                        .sortedForSeries()
                    shelf.collection != null -> filtered
                        // A book assigned to a series is shown only in that series.
                        // Its former shelf is kept as a fallback for when the series is removed.
                        .filter {
                            it.seriesName.isBlank() &&
                                it.collection.equals(shelf.collection, ignoreCase = true)
                        }
                        .sortedWith(compareBy<Book>(
                            { it.seriesName.lowercase() },
                            { if (it.seriesOrder > 0) it.seriesOrder else Int.MAX_VALUE },
                            { it.title.lowercase() }
                        ))
                    else -> filtered
                }
            }
        }

        // 2. Filter by Search Query
        if (query.isNotBlank()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.author.contains(query, ignoreCase = true) ||
                it.collection.contains(query, ignoreCase = true) ||
                it.seriesName.contains(query, ignoreCase = true)
            }
        }

        // 3. Filter by Format
        if (format != null) {
            filtered = filtered.filter {
                if (format == BookFormat.FB2) {
                    it.format == BookFormat.FB2 || it.format == BookFormat.FB2_ZIP
                } else {
                    it.format == format
                }
            }
        }

        filtered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentBooks: StateFlow<List<Book>> = bookDao.getAllBooks().map { list ->
        list.filter { it.currentProgressPercent > 0f }.take(3)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        checkAndSeedSampleBooks()
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
        _selectedCollection.value = coll
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
        viewModelScope.launch(Dispatchers.IO) {
            bookDao.updateFavorite(book.id, !book.isFavorite)
        }
    }

    fun toggleCompleted(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            bookDao.updateCompleted(book.id, !book.isCompleted)
        }
    }

    fun updateBookOrganization(
        book: Book,
        collection: String,
        seriesName: String,
        seriesOrder: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedCollection = normalizeShelfName(collection).ifBlank { "Основная" }
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
            _userMessage.emit(message)
        }
    }

    /**
     * Persists the full series as one ordered operation.  Calling individual
     * view-model methods from the AI workflow used to launch several unrelated
     * coroutines, so Room updates could become visible in download order.
     */
    fun organizeSeries(seriesName: String, orderedBooks: List<Book>) {
        val normalizedSeries = normalizeShelfName(seriesName)
        if (normalizedSeries.isBlank() || orderedBooks.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            orderedBooks.forEachIndexed { index, book ->
                bookDao.updateOrganization(
                    id = book.id,
                    collection = normalizeShelfName(book.collection).ifBlank { "Основная" },
                    seriesName = normalizedSeries,
                    seriesOrder = index + 1
                )
            }
            _userMessage.emit("Серия «$normalizedSeries» сохранена в правильном порядке")
        }
    }

    fun importBookFromUri(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    val contentResolver = context.contentResolver

                    var fileName = "book_${System.currentTimeMillis()}"
                    var mimeType = contentResolver.getType(uri)

                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) {
                                fileName = name
                            }
                        }
                    }

                    val format = when {
                        mimeType == "application/pdf" || fileName.lowercase().endsWith(".pdf") -> BookFormat.PDF
                        mimeType == "application/epub+zip" || fileName.lowercase().endsWith(".epub") -> BookFormat.EPUB
                        mimeType?.contains("fictionbook") == true || fileName.lowercase().endsWith(".fb2") -> BookFormat.FB2
                        fileName.lowercase().endsWith(".fb2.zip") -> BookFormat.FB2_ZIP
                        fileName.lowercase().endsWith(".txt") || fileName.lowercase().endsWith(".md") -> BookFormat.TXT
                        else -> BookFormat.fromFileName(fileName)
                    }

                    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                    val destFile = File(booksDir, fileName)

                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Parse Book
                    val parser = BookParserFactory.getParser(format)
                    val parsed = parser.parse(destFile)
                    com.lumina.reader.core.repository.BookCacheRepository.put(destFile.absolutePath, parsed)

                    var coverPath: String? = null
                    if (parsed.coverBytes != null && parsed.coverBytes.isNotEmpty()) {
                        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                        val coverFile = File(coversDir, "cover_${System.currentTimeMillis()}.jpg")
                        coverFile.writeBytes(parsed.coverBytes)
                        coverPath = coverFile.absolutePath
                    }

                    val book = Book(
                        title = parsed.title,
                        author = parsed.author,
                        filePath = destFile.absolutePath,
                        coverPath = coverPath,
                        format = format,
                        totalChapters = parsed.chapters.size,
                        fileSizeBytes = destFile.length(),
                        description = parsed.description,
                        seriesName = normalizeShelfName(parsed.seriesName),
                        seriesOrder = parsed.seriesOrder.coerceAtLeast(0)
                    )

                    bookDao.insertBook(book)
                    _userMessage.emit("Книга «${parsed.title}» успешно добавлена!")
                } catch (e: Exception) {
                    e.printStackTrace()
                    _userMessage.emit("Ошибка при импорте: ${e.localizedMessage ?: "не удалось прочесть файл"}")
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun downloadAndImportBook(url: String, format: BookFormat, title: String) {
        viewModelScope.launch {
            _isLoading.value = true
            withContext(Dispatchers.IO) {
                try {
                    val client = com.lumina.reader.core.network.OpdsClient()
                    val bytes = client.downloadBook(url)
                    
                    var actualBytes = bytes
                    var finalFormat = format

                    if (format == BookFormat.FB2_ZIP) {
                        try {
                            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    if (!entry.isDirectory && (entry.name.lowercase().endsWith(".fb2") || entry.name.lowercase().endsWith(".xml"))) {
                                        actualBytes = zis.readBytes()
                                        finalFormat = BookFormat.FB2
                                        break
                                    }
                                    entry = zis.nextEntry
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val context = getApplication<Application>()
                    val fileName = "book_${System.currentTimeMillis()}.${finalFormat.name.lowercase()}"
                    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                    val destFile = File(booksDir, fileName)
                    
                    FileOutputStream(destFile).use { output ->
                        output.write(actualBytes)
                    }

                    val parser = BookParserFactory.getParser(finalFormat)
                    val parsed = parser.parse(destFile)
                    com.lumina.reader.core.repository.BookCacheRepository.put(destFile.absolutePath, parsed)

                    var coverPath: String? = null
                    if (parsed.coverBytes != null && parsed.coverBytes.isNotEmpty()) {
                        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                        val coverFile = File(coversDir, "cover_${System.currentTimeMillis()}.jpg")
                        coverFile.writeBytes(parsed.coverBytes)
                        coverPath = coverFile.absolutePath
                    }

                    val book = Book(
                        title = parsed.title.ifBlank { title },
                        author = parsed.author,
                        filePath = destFile.absolutePath,
                        coverPath = coverPath,
                        format = finalFormat,
                        totalChapters = parsed.chapters.size,
                        fileSizeBytes = destFile.length(),
                        description = parsed.description,
                        seriesName = normalizeShelfName(parsed.seriesName),
                        seriesOrder = parsed.seriesOrder.coerceAtLeast(0)
                    )

                    bookDao.insertBook(book)
                    _userMessage.emit("Книга «${book.title}» успешно скачана и добавлена!")
                } catch (e: Exception) {
                    e.printStackTrace()
                    _userMessage.emit("Ошибка при скачивании: ${e.localizedMessage}")
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(book.filePath)
                if (file.exists()) file.delete()
                book.coverPath?.let {
                    val cover = File(it)
                    if (cover.exists()) cover.delete()
                }
                bookDao.deleteBook(book)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun checkAndSeedSampleBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = bookDao.getAllBooks().first().size
            if (count == 0) {
                seedDemoBook()
            }
        }
    }

    private suspend fun seedDemoBook() {
        val context = getApplication<Application>()
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val sampleFile = File(booksDir, "Добро пожаловать в Lumina.txt")

        val sampleContent = """
Глава 1. Добро пожаловать в Lumina Reader

Lumina Reader — это современная, быстрая и эстетичная читалка, созданная специально для флагманских дисплеев Samsung Dynamic AMOLED 2X с частотой 120 Гц.

Здесь всё создано для комфортного чтения:
• Режим Pure OLED Black (#000000) для глубокого погружения и максимальной экономии батареи.
• Плавнейшее листание страниц и поддержка непрерывного вертикального скролла.
• Режим Bionic Reading, который ускоряет восприятие текста, выделяя опорные буквы каждого слова.
• Тонкая настройка типографики: меняйте размер шрифта, межстрочный интервал, поля и гарнитуру.

Глава 2. Возможности и управление

Коснитесь центра экрана во время чтения, чтобы открыть панель управления.
В нижней части экрана доступны:
1. Выбор тем оформления: OLED Black, Slate Dark, Сепия, Бумага, Тёплый янтарь.
2. Оглавление с возможностью мгновенного перехода к нужной главе.
3. Добавление закладок и создание заметок.
4. Озвучивание текста голосом (Text-to-Speech).

Глава 3. Приятного чтения!

Вы можете добавить любые ваши книги в форматах EPUB, FB2, FB2.ZIP, PDF или TXT через системный проводник или просто открыв файл из любого мессенджера.

Погружайтесь в мир любимых историй вместе с Lumina Reader!
        """.trimIndent()

        sampleFile.writeText(sampleContent, Charsets.UTF_8)

        val parser = BookParserFactory.getParser(BookFormat.TXT)
        val parsed = parser.parse(sampleFile)

        val book = Book(
            title = "Добро пожаловать в Lumina",
            author = "Lumina Team",
            filePath = sampleFile.absolutePath,
            format = BookFormat.TXT,
            totalChapters = parsed.chapters.size,
            fileSizeBytes = sampleFile.length(),
            description = "Руководство пользователя и демонстрация возможностей читалки Lumina Reader."
        )
        bookDao.insertBook(book)
    }
}
