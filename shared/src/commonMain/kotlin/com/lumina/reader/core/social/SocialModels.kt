package com.lumina.reader.core.social

import kotlinx.serialization.Serializable

/*
 * The wire format of the reading circle, the same shapes the server declares
 * (server/src/main/kotlin/com/lumina/social/Models.kt). Only reading metadata
 * ever crosses: a title, an author, a rating, how far someone got. No book
 * files, no file paths, no catalogue credentials.
 */

@Serializable
data class RedeemRequest(val code: String, val displayName: String)

@Serializable
data class RedeemResponse(val userId: String, val token: String)

@Serializable
data class MeResponse(val userId: String, val displayName: String)

/** One book as it appears in someone's shared shelf. */
@Serializable
data class SharedBook(
    /** Stable identity of the book inside one shelf; see [SharedShelf.bookKey]. */
    val bookKey: String,
    val title: String,
    val author: String,
    /** 0..5, or null when the reader has not rated it. */
    val rating: Int? = null,
    /** 0..100. */
    val progress: Int = 0,
    /** One of [ReadingStatus]. */
    val status: String = ReadingStatus.READING,
    /** When the reader last changed any of the above (epoch millis). */
    val updatedAt: Long = 0,
    /** The series this book belongs to, so the feed can group its volumes. */
    val seriesName: String? = null,
    val seriesOrder: Int = 0,
)

@Serializable
data class PushBooksRequest(val books: List<SharedBook>)

/** One member of the circle and what they are reading. */
@Serializable
data class Member(
    val userId: String,
    val displayName: String,
    val books: List<SharedBook>,
)

@Serializable
data class CircleResponse(val members: List<Member>)

@Serializable
data class ErrorResponse(val error: String? = null)

/**
 * A book someone sent, waiting on the server to be collected.
 *
 * This is the one place a book file crosses the circle, and the server is only
 * a relay: it holds the bytes until this device claims them, and deletes them
 * then. [sizeBytes] is what the download will cost.
 */
@Serializable
data class TransferInfo(
    val id: String,
    val fromUserId: String,
    val fromDisplayName: String,
    val title: String,
    val author: String,
    /** Set when it was sent as part of a series, so the shelf can regroup it. */
    val seriesName: String? = null,
    val seriesOrder: Int = 0,
    /**
     * The sender's own shelf for this book. A series rebuilds its shelf from
     * its name alone; a shelf the user named by hand has nothing else to go on,
     * so it travels with the book.
     */
    val shelf: String? = null,
    /** The stored file's extension - "epub", "fb2", "fb2.zip", "pdf" or "txt". */
    val format: String,
    val sizeBytes: Long,
    val createdAt: Long,
)

@Serializable
data class IncomingTransfersResponse(val transfers: List<TransferInfo> = emptyList())

@Serializable
data class CreateTransferResponse(val id: String)

/** A one-time invitation code, as the server mints it for the circle's owner. */
@Serializable
data class CreateInviteResponse(val code: String)

/**
 * Someone asking for a book off this device's shelf.
 *
 * The server never had the file, so this is a note rather than a download: this
 * app finds the book by [bookKey] in its own library and sends it as an
 * ordinary transfer. Nothing is asked of the reader - inside a circle, a shared
 * shelf means its books may be had for the asking.
 */
@Serializable
data class BookRequest(
    val id: String,
    val fromUserId: String,
    val fromDisplayName: String,
    /** [SharedShelf.bookKey]: normalized title and author, joined by "|". */
    val bookKey: String,
    val title: String,
    val author: String,
    val createdAt: Long,
)

@Serializable
data class PendingRequestsResponse(val requests: List<BookRequest> = emptyList())

@Serializable
data class CreateRequestResponse(val id: String)

/** The statuses the server accepts. */
object ReadingStatus {
    const val NOT_STARTED = "NOT_STARTED"
    const val READING = "READING"
    const val DONE = "DONE"
}

/**
 * A failure of the circle worth showing to the user: the message is already the
 * sentence to display, as `describeOpdsError` does for the catalogues.
 */
class SocialException(message: String) : Exception(message)
