package com.lumina.reader.core.opds

import com.lumina.reader.platform.AppClock
import com.lumina.reader.platform.PlatformLock

/**
 * Which hosts of a catalogue answered last and which did not.
 *
 * [CatalogHealth] remembers whole catalogues; this remembers single hosts, which
 * is what the mirrors of one catalogue are. A blocked domain (flibusta.is without
 * a VPN) does not refuse the connection, it never answers, so every request that
 * asked it first spent its whole connect timeout before the mirror was tried —
 * on every feed, every search and every download. Browsing hid that: the links of
 * a feed are resolved against the host that actually answered, so only the first
 * page paid. The searches did not: their URLs are built from the catalogue's own
 * base ([OpdsCatalogConfig.siteBaseUrl]), always the same blocked domain, and the
 * timeout of a search ([OpdsRepository.SEARCH_TIMEOUT_MS]) ran out while the dead
 * host was still being waited for — the search failed although the catalogue was
 * perfectly reachable through its mirror.
 *
 * So a host that failed to answer is remembered as down for [DOWN_FOR_MILLIS] and
 * [order] moves it behind the hosts that are not. A host that answers at all —
 * "404" is an answer — is reachable again at once, so a mirror coming back needs
 * no restart, and nothing is ever skipped: a host that is down is tried last, not
 * dropped, in case it is the only one left.
 */
object HostHealth {
    const val DOWN_FOR_MILLIS = 10L * 60 * 1000

    private val lock = PlatformLock()
    private val downSince = HashMap<String, Long>()

    /** Remembers that [url]'s host did not answer (a connection or timeout failure). */
    fun markUnreachable(url: String) {
        val host = OpdsUrls.host(url) ?: return
        lock.withLock { downSince[host] = AppClock.nowMillis() }
    }

    /** Remembers that [url]'s host answered, whatever the answer was. */
    fun markReachable(url: String) {
        val host = OpdsUrls.host(url) ?: return
        lock.withLock { downSince.remove(host) }
    }

    /** True when [url]'s host failed to answer within the last [DOWN_FOR_MILLIS]. */
    fun isLikelyDown(url: String): Boolean {
        val host = OpdsUrls.host(url) ?: return false
        return lock.withLock {
            val since = downSince[host] ?: return@withLock false
            if (AppClock.nowMillis() - since > DOWN_FOR_MILLIS) {
                downSince.remove(host)
                false
            } else {
                true
            }
        }
    }

    /**
     * [urls] with the ones on a host that just failed moved to the end, their
     * order among themselves kept (so the caller's preference still decides
     * between two hosts that are equally healthy).
     */
    fun order(urls: List<String>): List<String> {
        if (urls.size < 2) return urls
        val (down, up) = urls.partition { isLikelyDown(it) }
        return if (down.isEmpty()) urls else up + down
    }

    /** Forgets everything (tests, and the "retry" of a catalogue the user asks for). */
    fun reset() {
        lock.withLock { downSince.clear() }
    }
}
