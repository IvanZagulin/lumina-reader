package com.lumina.reader.core.social

import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.preferences.SocialPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Answers the asks other members left for books off this device's shelf.
 *
 * The server holds no files, so this is where an ask becomes a book: the
 * library is searched by [SharedShelf.bookKey] and the match is sent as an
 * ordinary transfer. The reader is not interrupted to approve it - inside a
 * circle, a shared shelf is taken to mean its books may be had for the asking.
 *
 * What is *not* shared is still not shared. The set of keys that may be sent is
 * built by [SharedShelf] from the same rules the shelf itself is built from, so
 * sharing switched off, or a book the reader hid, cannot be obtained by asking
 * for it directly. An ask that cannot be answered is dropped rather than left
 * to be retried for ever.
 */
class RequestFulfiller(
    private val preferences: SocialPreferences,
    private val bookDao: BookDao,
    private val outbox: TransferOutbox,
    private val client: SocialClient = SocialClient(),
) {

    /** [declined] covers both "not in this library" and "not shared with you". */
    data class Fulfilled(
        val sent: Int = 0,
        val declined: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
    ) {
        val isEmpty: Boolean get() = sent == 0 && declined == 0 && failed == 0
    }

    suspend fun run(): Fulfilled {
        val settings = preferences.settings.first()
        if (!settings.joined) return Fulfilled()

        val asks = try {
            client.pendingRequests(settings.serverUrl, settings.token).requests
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fulfilled(error = e.sentence())
        }
        if (asks.isEmpty()) return Fulfilled()

        val library = bookDao.getAllBooksOnce()
        // Exactly what the circle can see, by the same rules it is shown with.
        val shareable = SharedShelf
            .build(library, settings.sharingEnabled, settings.hiddenBookKeys)
            .map { it.bookKey }
            .toSet()

        var sent = 0
        var declined = 0
        var failed = 0
        var firstError: String? = null

        for (ask in asks) {
            val book = library.firstOrNull { SharedShelf.bookKey(it.title, it.author) == ask.bookKey }
            if (book == null || ask.bookKey !in shareable) {
                drop(settings.serverUrl, settings.token, ask.id)
                declined++
                continue
            }
            try {
                val result = outbox.send(ask.fromUserId, listOf(book))
                if (result.sent > 0) {
                    drop(settings.serverUrl, settings.token, ask.id)
                    sent++
                } else {
                    // Left standing: the next pass tries again.
                    failed++
                    if (firstError == null) firstError = result.error
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e.sentence()
            }
        }
        return Fulfilled(sent, declined, failed, firstError)
    }

    /** A note that cannot be removed is not worth failing over; it expires with the circle's own tidying. */
    private suspend fun drop(serverUrl: String, token: String, id: String) {
        try {
            client.deleteRequest(serverUrl, token, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Answered anyway; a repeat ask would find the book already sent.
        }
    }

    private fun Exception.sentence(): String =
        (this as? SocialException)?.message
            ?: message?.takeIf { it.isNotBlank() }
            ?: "Не удалось ответить на запрос книги"
}
