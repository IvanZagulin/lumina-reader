package com.lumina.reader.core.social

import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.library.ImportLimits
import com.lumina.reader.core.library.ImportPipeline
import com.lumina.reader.core.library.ImportResult
import com.lumina.reader.core.library.ImportSource
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.preferences.SocialPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okio.Buffer
import okio.Source

/** Bytes already in memory as something [ImportPipeline] can add to the library. */
class ByteArrayImportSource(
    private val bytes: ByteArray,
    override val displayName: String?,
    override val mimeType: String? = null,
) : ImportSource {

    override val sizeBytes: Long = bytes.size.toLong()

    override fun open(): Source = Buffer().apply { write(bytes) }
}

/**
 * Books friends sent, collected into this library.
 *
 * The server is only a relay, and the order here is what makes that safe: the
 * file is downloaded, added to the library, and only then claimed - and a claim
 * is what deletes it from the server. An import that fails is left unclaimed on
 * purpose, so the next run tries it again instead of losing the book.
 *
 * Nothing here decides what the user sees; the caller reports the [Collected]
 * summary, which keeps this testable without a UI.
 */
class TransferInbox(
    private val preferences: SocialPreferences,
    private val pipeline: ImportPipeline,
    private val bookDao: BookDao,
    private val client: SocialClient = SocialClient(),
) {

    /**
     * What one pass did. [failed] stays on the server for the next pass;
     * [error] is set when the list itself could not be fetched.
     */
    data class Collected(
        val imported: Int = 0,
        val alreadyHad: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
    ) {
        val arrived: Int get() = imported + alreadyHad
        val isEmpty: Boolean get() = imported == 0 && alreadyHad == 0 && failed == 0
    }

    suspend fun collect(): Collected {
        val settings = preferences.settings.first()
        if (!settings.joined) return Collected()

        val waiting = try {
            client.incomingTransfers(settings.serverUrl, settings.token).transfers
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Collected(error = e.sentence())
        }
        if (waiting.isEmpty()) return Collected()

        var imported = 0
        var alreadyHad = 0
        var failed = 0
        var firstError: String? = null

        for (transfer in waiting) {
            try {
                when (val result = receive(settings.serverUrl, settings.token, transfer)) {
                    is ImportResult.Imported -> imported++
                    is ImportResult.Duplicate -> alreadyHad++
                    is ImportResult.Failed -> {
                        failed++
                        if (firstError == null) firstError = result.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One book that would not come through must not stop the rest.
                failed++
                if (firstError == null) firstError = e.sentence()
            }
        }
        return Collected(imported, alreadyHad, failed, firstError)
    }

    private suspend fun receive(serverUrl: String, token: String, transfer: TransferInfo): ImportResult {
        val bytes = client.downloadTransfer(serverUrl, token, transfer.id, ImportLimits.MAX_BOOK_BYTES)
        val result = pipeline.importSource(
            ByteArrayImportSource(bytes, displayName = fileNameFor(transfer)),
        )
        when (result) {
            is ImportResult.Imported -> {
                applyOrganization(result.book, transfer)
                claim(serverUrl, token, transfer.id)
            }
            // Already in this library: there is no reason for the server to keep
            // holding the bytes, so the transfer is finished either way.
            is ImportResult.Duplicate -> claim(serverUrl, token, transfer.id)
            // Left unclaimed on purpose: the next pass gets another go at it.
            is ImportResult.Failed -> Unit
        }
        return result
    }

    /**
     * Puts the book where the sender had it: the series it belongs to, and the
     * shelf they had named. Neither is necessarily in the file - a shelf never
     * is - so both travel with the transfer, and whatever the sender did not
     * send is left as the import decided.
     */
    private suspend fun applyOrganization(book: Book, transfer: TransferInfo) {
        val series = transfer.seriesName?.trim().orEmpty().ifEmpty { book.seriesName }
        val shelf = transfer.shelf?.trim().orEmpty().ifEmpty { book.collection }
        if (series == book.seriesName && shelf == book.collection) return
        bookDao.updateOrganization(
            id = book.id,
            collection = shelf,
            seriesName = series,
            seriesOrder = if (series == book.seriesName) book.seriesOrder else transfer.seriesOrder,
        )
    }

    /** A claim that fails is not worth failing the import for: the sweeper will tidy up. */
    private suspend fun claim(serverUrl: String, token: String, id: String) {
        try {
            client.claimTransfer(serverUrl, token, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The book is already in the library; the server copy expires by itself.
        }
    }

    /** The name the pipeline reads a format hint from, e.g. «Дюна.epub». */
    private fun fileNameFor(transfer: TransferInfo): String {
        val extension = transfer.format.trim().lowercase().ifEmpty { "epub" }
        val base = transfer.title.trim().ifEmpty { "book" }
        return "$base.$extension"
    }

    private fun Exception.sentence(): String =
        (this as? SocialException)?.message
            ?: message?.takeIf { it.isNotBlank() }
            ?: "Не удалось получить книгу"
}
