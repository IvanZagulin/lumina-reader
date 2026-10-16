package com.lumina.social

import kotlinx.serialization.Serializable

/*
 * The wire format between the app and this server. Only reading metadata ever
 * crosses: a title, an author, a rating, how far someone got. No book files, no
 * catalogue credentials, no file paths - nothing that is the user's own copy of
 * a book. What a person shares is what they chose to push; the server keeps and
 * returns exactly that.
 */

/** A redeemed invite: the app stores [token] and sends it as a bearer from then on. */
@Serializable
data class RedeemRequest(val code: String, val displayName: String)

@Serializable
data class RedeemResponse(val userId: String, val token: String)

@Serializable
data class MeResponse(val userId: String, val displayName: String)

@Serializable
data class CreateInviteResponse(val code: String)

/** One book as it appears in someone's shared shelf. */
@Serializable
data class SharedBook(
    /** Stable identity of the book inside one shelf (normalized title + author). */
    val bookKey: String,
    val title: String,
    val author: String,
    /** 0..5, or null when the reader has not rated it. */
    val rating: Int? = null,
    /** 0..100. */
    val progress: Int = 0,
    /** READING, DONE or NOT_STARTED. */
    val status: String = "READING",
    /** When the reader last changed any of the above (epoch millis). */
    val updatedAt: Long = 0,
)

/** The whole shared shelf the app pushes up; it replaces what was there before. */
@Serializable
data class PushBooksRequest(val books: List<SharedBook>)

/** One friend and what they are reading. */
@Serializable
data class Member(
    val userId: String,
    val displayName: String,
    val books: List<SharedBook>,
)

/** Everyone in the circle except the caller - the "who is reading what" feed. */
@Serializable
data class CircleResponse(val members: List<Member>)

@Serializable
data class ErrorResponse(val error: String)

/** Statuses the server accepts; anything else is a bad request. */
val ALLOWED_STATUSES = setOf("READING", "DONE", "NOT_STARTED")
