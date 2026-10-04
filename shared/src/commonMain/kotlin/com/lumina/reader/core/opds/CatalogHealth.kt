package com.lumina.reader.core.opds

import com.lumina.reader.platform.AppClock
import com.lumina.reader.platform.PlatformLock

/**
 * Which catalogues have just failed to answer.
 *
 * A catalogue that is blocked for the user (Flibusta without a VPN) does not refuse
 * the connection, it just never answers, so every search or download that asked it
 * first spent its whole timeout before moving on. After a failure the catalogue is
 * treated as down for [DOWN_FOR_MILLIS]; callers that rank catalogues then go to the
 * others at once instead of waiting for it again. A success clears the mark, and the
 * catalogue is still tried in the background, so it comes back by itself.
 */
object CatalogHealth {
    const val DOWN_FOR_MILLIS = 10L * 60 * 1000

    private val lock = PlatformLock()
    private val downSince = HashMap<String, Long>()

    fun markUnreachable(catalogId: String) {
        lock.withLock { downSince[catalogId] = AppClock.nowMillis() }
    }

    fun markReachable(catalogId: String) {
        lock.withLock { downSince.remove(catalogId) }
    }

    /** True when [catalogId] failed to answer within the last [DOWN_FOR_MILLIS]. */
    fun isLikelyDown(catalogId: String): Boolean = lock.withLock {
        val since = downSince[catalogId] ?: return@withLock false
        if (AppClock.nowMillis() - since > DOWN_FOR_MILLIS) {
            downSince.remove(catalogId)
            false
        } else {
            true
        }
    }
}
