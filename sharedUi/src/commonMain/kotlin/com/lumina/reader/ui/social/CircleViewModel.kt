package com.lumina.reader.ui.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.preferences.SocialSettings
import com.lumina.reader.core.social.Member
import com.lumina.reader.core.social.SharedShelf
import com.lumina.reader.core.social.SocialClient
import com.lumina.reader.core.social.SocialException
import com.lumina.reader.core.social.TransferInbox
import com.lumina.reader.core.social.TransferOutbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the circle screen draws.
 *
 * [settings] is the stored membership, so `settings.joined` decides between the
 * join form and the feed, and `settings.sharingEnabled` drives the switch.
 * [sendingTo] is non-null while the "send a book" sheet is open for that member.
 */
data class CircleUiState(
    val settings: SocialSettings = SocialSettings(),
    val members: List<Member> = emptyList(),
    /** A feed fetch (and inbox collection) is in flight. */
    val loading: Boolean = false,
    /** Joining, leaving or saving a choice is in flight; the controls wait for it. */
    val busy: Boolean = false,
    /** A failure to show, already worded by [SocialClient]. */
    val error: String? = null,
    /** Something that went right and is worth a line: books arrived, books sent. */
    val notice: String? = null,
    /** True once a fetch succeeded, so an empty feed can say "никого больше нет". */
    val loadedOnce: Boolean = false,
    /** This library, loaded when the send sheet opens. */
    val myBooks: List<Book> = emptyList(),
    /** The member the open send sheet is addressed to; null when it is closed. */
    val sendingTo: Member? = null,
    val sending: Boolean = false,
)

/**
 * The reading circle as a screen: join with an invite, decide whether to share,
 * read what everyone else is reading, and hand a book or a series to one of
 * them.
 *
 * Every push of this device's shelf goes through [SharedShelf], never straight
 * from the library, because that is where the privacy rules live: sharing off
 * builds an empty shelf, which is also how "stop sharing" erases the copy the
 * server holds. So [pushShelf] is correct to call whatever the current choice
 * is - it sends the books, or it sends nothing.
 *
 * Collaborators have defaults so Android's `viewModel()` builds it without a
 * factory; the services come from [SocialServicesHolder] because a shared view
 * model cannot open the database or the preference file itself.
 */
class CircleViewModel(
    social: SocialServices = SocialServicesHolder.services,
    private val client: SocialClient = SocialClient(),
    inbox: TransferInbox? = null,
    outbox: TransferOutbox? = null,
) : ViewModel() {

    private val preferences = social.preferences
    private val bookDao: BookDao = social.bookDao

    private val inbox: TransferInbox = inbox ?: TransferInbox(
        preferences = social.preferences,
        pipeline = social.importPipeline,
        bookDao = social.bookDao,
        client = client,
    )
    private val outbox: TransferOutbox = outbox ?: TransferOutbox(
        preferences = social.preferences,
        client = client,
    )

    private val _state = MutableStateFlow(CircleUiState())
    val state: StateFlow<CircleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
    }

    /** Exchanges an invite for membership; sharing stays off until asked for. */
    fun join(serverUrl: String, code: String, displayName: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val joined = client.redeem(serverUrl, code, displayName)
                preferences.saveMembership(
                    serverUrl = serverUrl,
                    userId = joined.userId,
                    token = joined.token,
                    displayName = displayName,
                )
                _state.update { it.copy(busy = false) }
                // Nothing of this device is shared yet, so this only reads.
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.displayMessage()) }
            }
        }
    }

    /**
     * One pass over everything the circle owes this device: send the shelf (or
     * nothing, when sharing is off), read the feed, and collect books friends
     * sent. Collecting is part of opening the screen because there is no push:
     * this is the moment a sent book can actually arrive.
     */
    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        val settings = preferences.settings.first()
        if (!settings.joined) return
        _state.update { it.copy(loading = true, error = null) }
        try {
            // Push first, so the others read current numbers rather than last week's.
            pushShelf(settings)
            val circle = client.circle(settings.serverUrl, settings.token)
            _state.update { it.copy(members = circle.members, loadedOnce = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.displayMessage()) }
        }

        // Collected after the feed: a book that arrives changes the library, and
        // a failure here must not hide the feed that was just fetched.
        val collected = inbox.collect()
        _state.update { current ->
            current.copy(
                loading = false,
                notice = collected.describe() ?: current.notice,
                error = current.error ?: collected.error.takeIf { collected.failed > 0 },
            )
        }
    }

    /**
     * Turns sharing on or off and immediately makes the server match: on sends
     * the shelf, off sends an empty one, which is what erases it.
     */
    fun setSharingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            preferences.setSharingEnabled(enabled)
            pushCurrentShelf()
            _state.update { it.copy(busy = false) }
            refreshNow()
        }
    }

    /** Keeps one book out of the circle (or puts it back) and republishes the shelf. */
    fun setBookHidden(bookKey: String, hidden: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            preferences.setBookHidden(bookKey, hidden)
            pushCurrentShelf()
            _state.update { it.copy(busy = false) }
        }
    }

    // ---- Sending a book ------------------------------------------------------------

    /** Opens the send sheet for [member] and loads this library to choose from. */
    fun startSending(member: Member) {
        viewModelScope.launch {
            val books = bookDao.getAllBooksOnce()
                .filter { it.title.isNotBlank() }
                .sortedBy { it.title.lowercase() }
            _state.update { it.copy(sendingTo = member, myBooks = books, error = null) }
        }
    }

    fun cancelSending() {
        _state.update { it.copy(sendingTo = null, myBooks = emptyList(), sending = false) }
    }

    /**
     * Sends the chosen books to the member the sheet is open for. A series is
     * just several books: each carries its own name and number, and the other
     * side's shelf regroups them.
     */
    fun sendChosenBooks(bookIds: Set<Long>) {
        val member = _state.value.sendingTo ?: return
        if (bookIds.isEmpty() || _state.value.sending) return
        val books = _state.value.myBooks.filter { it.id in bookIds }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, error = null) }
            val result = outbox.send(member.userId, books)
            _state.update { current ->
                current.copy(
                    sending = false,
                    sendingTo = if (result.failed == 0) null else current.sendingTo,
                    myBooks = if (result.failed == 0) emptyList() else current.myBooks,
                    notice = if (result.sent > 0) {
                        "Отправлено ${booksSummary(result.sent)} для ${member.displayName}"
                    } else {
                        current.notice
                    },
                    error = result.error,
                )
            }
        }
    }

    /**
     * Leaves the circle. The shelf on the server is cleared first, because after
     * [com.lumina.reader.core.preferences.SocialPreferences.leave] the token is
     * gone and there is no way left to ask the server for anything.
     */
    fun leave() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val settings = preferences.settings.first()
            if (settings.joined) {
                try {
                    client.pushBooks(settings.serverUrl, settings.token, emptyList())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Leaving has to work with no network too. The server then keeps
                    // the last shelf until this device joins again and pushes.
                }
            }
            preferences.leave()
            _state.update {
                it.copy(
                    busy = false,
                    members = emptyList(),
                    loadedOnce = false,
                    error = null,
                    notice = null,
                    sendingTo = null,
                    myBooks = emptyList(),
                )
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** Reads the choices back before pushing, so a just-saved switch is the one that applies. */
    private suspend fun pushCurrentShelf() {
        val settings = preferences.settings.first()
        if (!settings.joined) return
        try {
            pushShelf(settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.displayMessage()) }
        }
    }

    private suspend fun pushShelf(settings: SocialSettings) {
        val shelf = SharedShelf.build(
            books = bookDao.getAllBooksOnce(),
            sharingEnabled = settings.sharingEnabled,
            hiddenKeys = settings.hiddenBookKeys,
        )
        client.pushBooks(settings.serverUrl, settings.token, shelf)
    }

    /** [SocialException] already carries the sentence to show; anything else gets a plain one. */
    private fun Exception.displayMessage(): String = when {
        this is SocialException -> message ?: FALLBACK_ERROR
        else -> message?.takeIf { it.isNotBlank() } ?: FALLBACK_ERROR
    }

    /** «Пришло 2 книги» — or null when the pass brought nothing worth saying. */
    private fun TransferInbox.Collected.describe(): String? = when {
        arrived == 0 -> null
        imported > 0 && alreadyHad > 0 ->
            "Пришло ${booksSummary(imported)}, ${booksSummary(alreadyHad)} уже были в библиотеке"
        imported > 0 -> "Пришло ${booksSummary(imported)}"
        else -> "${booksSummary(alreadyHad)} уже были в библиотеке"
    }

    private companion object {
        const val FALLBACK_ERROR = "Не удалось связаться с кругом"
    }
}
