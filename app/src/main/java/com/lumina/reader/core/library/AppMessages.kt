package com.lumina.reader.core.library

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What tapping a message's action does. */
sealed interface AppMessageAction {
    data class OpenBook(val bookId: Long) : AppMessageAction
}

/** A short message for the app-wide snackbar. */
data class AppMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: AppMessageAction? = null,
    val isError: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    /** Messages that waited too long (the app was in the background) are not shown. */
    fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - createdAtMillis <= MAX_AGE_MILLIS

    companion object {
        const val MAX_AGE_MILLIS = 2 * 60_000L
    }
}

/**
 * App-wide message bus shown by the snackbar host at the top of the UI, so a
 * result is visible on whatever screen the user is on. It is a buffered
 * channel rather than a SharedFlow: a message posted while no screen collects
 * (activity recreated, app in background) waits instead of being dropped.
 * Only one collector (the activity) is expected.
 */
object AppMessages {
    private val messageChannel = Channel<AppMessage>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val openBookChannel = Channel<Long>(capacity = Channel.CONFLATED)

    val messages: Flow<AppMessage> = messageChannel.receiveAsFlow()

    /** Requests to open a book in the reader (notification taps, "Открыть" actions). */
    val openBookRequests: Flow<Long> = openBookChannel.receiveAsFlow()

    fun post(message: AppMessage) {
        messageChannel.trySend(message)
    }

    fun post(text: String, isError: Boolean = false) = post(AppMessage(text = text, isError = isError))

    /** A message with an "Открыть" action that opens [bookId] in the reader. */
    fun postWithOpen(text: String, bookId: Long) =
        post(AppMessage(text = text, actionLabel = "Открыть", action = AppMessageAction.OpenBook(bookId)))

    fun requestOpenBook(bookId: Long) {
        if (bookId > 0) openBookChannel.trySend(bookId)
    }
}
