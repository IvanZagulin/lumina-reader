package com.lumina.reader.ui.social

import com.lumina.reader.core.library.AppMessages
import com.lumina.reader.core.social.SocialClient
import com.lumina.reader.core.social.TransferInbox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * One pass is enough at a time. The shell asks for a pass every time the app
 * comes to the foreground, so two can otherwise overlap: the pipeline would
 * recognise the second copy as a duplicate, but only after downloading the
 * same book twice.
 */
private val passLock = Mutex()

/**
 * Collects books friends sent, once.
 *
 * There is no push in this app, so this is what makes "it just appeared on my
 * shelf" true: the shell runs a pass whenever it comes to the foreground, and
 * the circle screen runs one when it opens. A device that never joined a circle
 * does nothing at all, and a failure is silent - a reader opening their library
 * has not asked about the circle and should not be told it is unreachable.
 */
suspend fun collectIncomingBooks(
    services: SocialServices = SocialServicesHolder.services,
    client: SocialClient = SocialClient(),
): TransferInbox.Collected {
    val settings = services.preferences.settings.first()
    if (!settings.joined) return TransferInbox.Collected()
    // A pass already running will pick up anything this one would have.
    if (!passLock.tryLock()) return TransferInbox.Collected()

    val collected = try {
        TransferInbox(
            preferences = services.preferences,
            pipeline = services.importPipeline,
            bookDao = services.bookDao,
            client = client,
        ).collect()
    } finally {
        passLock.unlock()
    }

    // Only an arrival is worth interrupting the library with; a book that was
    // already here, or one that would not import, says nothing at start-up.
    if (collected.imported > 0) {
        AppMessages.post("Друзья прислали ${booksSummary(collected.imported)}")
    }
    return collected
}
