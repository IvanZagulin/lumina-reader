package com.lumina.reader.core.social

import com.lumina.reader.core.library.BookFileNames
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.preferences.LibraryPreferences.Companion.MAIN_SHELF
import com.lumina.reader.core.preferences.SocialPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

/**
 * Sending books from this library to someone in the circle.
 *
 * A series is nothing special here: it is several books that each carry their
 * own [Book.seriesName] and [Book.seriesOrder], so the other side's shelf
 * regroups them with the logic it already has. The server only relays.
 *
 * The size is checked before the file is read, not after: a book is loaded into
 * memory to be uploaded, and a phone must not try that with a 300 MB scan.
 */
class TransferOutbox(
    private val preferences: SocialPreferences,
    private val client: SocialClient = SocialClient(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {

    data class Sent(val sent: Int = 0, val failed: Int = 0, val error: String? = null)

    /**
     * Sends every book of [books] to [recipientId], carrying on past one that
     * fails. The returned [Sent] is what the caller tells the user.
     *
     * [onProgress] reports after each book, because a handful of them is
     * minutes of uploading and one unmoving spinner looks like a hung app.
     */
    suspend fun send(
        recipientId: String,
        books: List<Book>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Sent {
        val settings = preferences.settings.first()
        if (!settings.joined) return Sent(error = "Сначала нужно войти в круг")
        if (books.isEmpty()) return Sent()

        var sent = 0
        var failed = 0
        var firstError: String? = null

        onProgress(0, books.size)
        for ((index, book) in books.withIndex()) {
            try {
                sendOne(settings.serverUrl, settings.token, recipientId, book)
                sent++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e.sentence(book)
            }
            onProgress(index + 1, books.size)
        }
        return Sent(sent, failed, firstError)
    }

    private suspend fun sendOne(serverUrl: String, token: String, recipientId: String, book: Book) {
        val bytes = readBook(book)
        client.sendBook(
            serverUrl = serverUrl,
            token = token,
            recipientId = recipientId,
            title = book.title,
            author = book.author,
            format = BookFileNames.extensionFor(book.format),
            bytes = bytes,
            seriesName = book.seriesName.takeIf { it.isNotBlank() },
            seriesOrder = book.seriesOrder,
            // A shelf the reader named themselves; the main one is everyone's
            // default and says nothing worth carrying.
            shelf = book.collection.takeIf { it.isNotBlank() && !it.equals(MAIN_SHELF, ignoreCase = true) },
        )
    }

    private suspend fun readBook(book: Book): ByteArray = withContext(Dispatchers.IO) {
        val path = book.filePath.toPath()
        val size = fileSystem.metadataOrNull(path)?.size
            ?: throw SocialException("Файл книги «${book.title}» не найден на устройстве")
        if (size > MAX_SEND_BYTES) {
            throw SocialException(
                "«${book.title}» больше ${MAX_SEND_BYTES / (1024 * 1024)} МБ — такую книгу переслать нельзя",
            )
        }
        fileSystem.source(path).buffer().use { it.readByteArray() }
    }

    private fun Exception.sentence(book: Book): String =
        (this as? SocialException)?.message
            ?: message?.takeIf { it.isNotBlank() }
            ?: "Не удалось отправить «${book.title}»"

    companion object {
        /**
         * Mirrors the server's own `MAX_TRANSFER_BYTES` default. Checking it here
         * too turns a wasted upload of a huge file into an immediate, clear
         * refusal.
         */
        const val MAX_SEND_BYTES: Long = 50L * 1024 * 1024
    }
}
