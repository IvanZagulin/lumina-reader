package com.lumina.reader.core.library

import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.database.BookmarkDao
import com.lumina.reader.core.database.ReadingStatsDao
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.parser.BookParser
import com.lumina.reader.core.parser.BookParserFactory
import com.lumina.reader.core.preferences.ReaderPreferences
import com.lumina.reader.core.repository.BookCacheRepository
import com.lumina.reader.core.tts.ReadAloudController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.SYSTEM

/**
 * Everything the reader's view model needs, built once per process and
 * reached as [AppServices.reader]. Like [LibraryServices] it exists because
 * the platforms build the same objects differently: Android needs a `Context`
 * for the database, the preference file and read-aloud, iOS needs nothing.
 *
 * The defaulted members are the same on both platforms; they are here so the
 * view model, which will live in :sharedUi's common code, need not name them.
 */
class ReaderServices(
    val bookDao: BookDao,
    val bookmarkDao: BookmarkDao,
    val statsDao: ReadingStatsDao,
    val preferences: ReaderPreferences,
    val readAloud: ReadAloudController,
    /** Recently parsed books, so reopening a book is instant. */
    val bookCache: BookCacheRepository = BookCacheRepository,
    /** The parser for a stored book's format. */
    val parserFor: (BookFormat) -> BookParser = BookParserFactory::getParser,
    /** Where book files are looked up; the books themselves are parsed from the real disk. */
    val fileSystem: FileSystem = FileSystem.SYSTEM,
    /** Database and file work: Dispatchers.IO, which :sharedUi's common code cannot see. */
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
)

/**
 * True for an out-of-memory error, which the reader reports instead of
 * crashing (Android's OutOfMemoryError; Kotlin/Native aborts instead, so never
 * on iOS). Public for the reader in :app, which cannot see [isOutOfMemory].
 */
fun isOutOfMemoryError(error: Throwable): Boolean = isOutOfMemory(error)

/**
 * The message shown for [error]: `Throwable.localizedMessage` on Android, as
 * the reader always used, `message` elsewhere. Public for the same reason as
 * [isOutOfMemoryError].
 */
fun errorMessageOf(error: Throwable): String? = localizedMessageOf(error)
