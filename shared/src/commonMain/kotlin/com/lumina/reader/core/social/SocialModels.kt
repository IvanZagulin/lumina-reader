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
