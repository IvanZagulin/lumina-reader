package com.lumina.social

import java.io.File
import java.io.InputStream

/** An upload the server declined to keep; [message] is the sentence to show. */
class TransferRejected(message: String) : Exception(message)

/**
 * The books in transit, on disk.
 *
 * A sent book lives here only until the other person's app collects it, so this
 * is a relay and not a library: nothing is kept after a claim, and the sweeper
 * drops whatever was never collected. The caps come from [TransferLimits] and
 * are enforced while the bytes arrive rather than from `Content-Length`, which
 * a client controls and can lie about.
 */
class TransferStore(private val limits: TransferLimits) {

    private val root = File(limits.directory)

    init {
        if (!root.exists() && !root.mkdirs()) {
            error("Cannot create the transfer directory ${limits.directory}")
        }
    }

    /**
     * Streams [source] into storage for [id].
     *
     * [alreadyStored] is what the database says is waiting to be collected; the
     * write stops the moment either cap would be crossed, and the partial file
     * is removed, so a refused upload leaves nothing behind.
     */
    fun write(id: String, source: InputStream, alreadyStored: Long): Long {
        val room = limits.maxStoreBytes - alreadyStored
        if (room <= 0) {
            throw TransferRejected(
                "На сервере нет места для передачи: подождите, пока друзья заберут отправленное ранее",
            )
        }
        val ceiling = minOf(limits.maxFileBytes, room)
        val target = fileFor(id)
        var written = 0L
        try {
            target.outputStream().buffered().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > ceiling) {
                        throw TransferRejected(
                            if (ceiling == limits.maxFileBytes) {
                                "Книга больше ${limits.maxFileBytes / (1024 * 1024)} МБ — такую переслать нельзя"
                            } else {
                                "На сервере нет места для передачи: подождите, пока друзья заберут отправленное ранее"
                            },
                        )
                    }
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (written == 0L) {
            target.delete()
            throw TransferRejected("Пустой файл книги")
        }
        return written
    }

    /** The stored file for [id], or null when it is gone (claimed, swept, or never written). */
    fun file(id: String): File? = fileFor(id).takeIf { it.isFile }

    /** Removes the file; a missing one is not an error, since a claim may race the sweeper. */
    fun delete(id: String) {
        fileFor(id).delete()
    }

    /**
     * Files with no row left in the database. The two can drift when a write
     * succeeded and the insert did not, and nothing else would ever remove
     * those bytes.
     */
    fun orphans(knownIds: Set<String>): List<String> =
        root.listFiles().orEmpty()
            .filter { it.isFile && it.name !in knownIds }
            .map { it.name }

    // The id is server-generated (see Secrets.newTransferId), so it cannot walk
    // out of the directory; the check is here so that stays true if that changes.
    private fun fileFor(id: String): File {
        require(id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Unsafe transfer id: $id"
        }
        return File(root, id)
    }
}
