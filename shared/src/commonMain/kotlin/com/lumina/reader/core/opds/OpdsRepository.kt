package com.lumina.reader.core.opds

import com.lumina.reader.core.download.HttpStatusException
import com.lumina.reader.core.download.describeNetworkError
import com.lumina.reader.core.network.finalUrl
import com.lumina.reader.core.network.readBodyBytes
import com.lumina.reader.platform.PlatformLock
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.URLParserException
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.IOException
import com.lumina.reader.core.network.NetworkProxy

/** Search result of one catalogue: a feed, or a readable error. */
data class CatalogSearchResult(
    val catalog: OpdsCatalogConfig,
    val feed: OpdsFeed?,
    val error: String?
)

/** A publication found in a catalogue (the catalogue supplies credentials and mirrors). */
data class FoundPublication(
    val catalog: OpdsCatalogConfig,
    val publication: OpdsEntry.Publication
)

/** Loads, searches and pages OPDS catalogues. */
class OpdsRepository(
    // A provider, not a client: the proxy setting can change while the repository lives.
    private val clientProvider: (url: String) -> HttpClient = { OpdsHttp.feedClientFor(it) },
    private val parser: OpdsFeedParser = OpdsFeedParser(),
    private val feedCache: OpdsFeedCache = OpdsFeedCache.shared
) {
    /** Guards both caches: searches of several catalogues run in parallel. */
    private val cacheLock = PlatformLock()

    /** Search template per catalogue id ("" = the catalogue has none). */
    private val templateCache = HashMap<String, String>()

    /** Search template per search link href (OpenSearch descriptions are fetched once). */
    private val linkTemplateCache = HashMap<String, String>()

    /**
     * Loads and parses a feed. Mirrors of [catalog] are tried in turn when the
     * main domain fails; authentication errors are reported immediately.
     */
    suspend fun fetchFeed(
        url: String,
        catalog: OpdsCatalogConfig?,
        /** False for a connection check: it has to reach the server, not the stored copy. */
        useCache: Boolean = true
    ): OpdsFeed = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        // A host that just failed to answer goes last: waiting out its connect
        // timeout again, on every feed and every search, is what made the
        // catalogues slow and the searches time out (see HostHealth).
        val candidates = HostHealth.order(OpdsUrls.candidates(url, catalog?.mirrorBaseUrls.orEmpty()))
        // The same feed on any mirror is the same feed, so a fresh copy of any of
        // them answers at once. It is looked up across all of them, not only the
        // URL asked for: after a restart nothing yet remembers which mirror works,
        // and the copy lies under the one that answered last time.
        if (useCache) {
            val maxAge = OpdsFeedCache.maxAgeFor(url)
            candidates.firstNotNullOfOrNull { cached(it, maxAge) }?.let { return@withContext it }
        }
        for (candidate in candidates) {
            try {
                return@withContext fetchOnce(candidate, catalog)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code == 401 || e.code == 403) throw e
                lastError = e
            } catch (e: Exception) {
                // A cancelled call fails with an I/O error: stop instead of trying mirrors.
                ensureActive()
                lastError = e
            }
        }
        // Nothing answered: the last copy, however old, is better than an error (a
        // catalogue that is blocked or down is exactly when it is needed).
        val error = lastError
        val unauthorised = error is HttpStatusException && (error.code == 401 || error.code == 403)
        if (useCache && !unauthorised) {
            candidates.firstNotNullOfOrNull { cached(it, Long.MAX_VALUE) }?.let { return@withContext it }
        }
        throw error ?: IOException("Не удалось открыть каталог")
    }

    /** The stored copy of [url], if there is one no older than [maxAgeMillis] that still parses. */
    private fun cached(url: String, maxAgeMillis: Long): OpdsFeed? =
        feedCache.read(url, maxAgeMillis)?.let { hit ->
            runCatching { parser.parseFeed(hit.body, hit.finalUrl) }.getOrNull()
        }

    private suspend fun fetchOnce(url: String, catalog: OpdsCatalogConfig?): OpdsFeed {
        NetworkProxy.ready()
        try {
            return withTimeout(ATTEMPT_TIMEOUT_MS) {
                clientProvider(url).prepareGet(url) { feedHeaders(url, catalog) }.execute { response ->
                    // The host answered; even a 404 proves it is reachable.
                    HostHealth.markReachable(url)
                    if (!response.status.isSuccess()) throw HttpStatusException(response.status.value)
                    val body = response.readBodyBytes(MAX_FEED_BYTES)
                        ?: throw OpdsFormatException(FEED_TOO_LARGE_MESSAGE)
                    val feed = parser.parseFeed(body, response.finalUrl)
                    feedCache.write(url, response.finalUrl, body)
                    feed
                }
            }
        } catch (e: TimeoutCancellationException) {
            // This attempt ran out of time, not the caller: the next mirror must
            // still be tried, so it leaves as a failure and not as a cancellation
            // (which fetchFeed and searchCatalog pass straight through).
            HostHealth.markUnreachable(url)
            throw IOException(ATTEMPT_TIMED_OUT_MESSAGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            throw e // an answer, however unwelcome
        } catch (e: OpdsFormatException) {
            throw e // an answer too: the host served something that is not a feed
        } catch (e: Exception) {
            HostHealth.markUnreachable(url)
            throw e
        }
    }

    private suspend fun fetchOpenSearchTemplate(url: String, catalog: OpdsCatalogConfig?): String? =
        clientProvider(url).prepareGet(url) { feedHeaders(url, catalog) }.execute { response ->
            if (!response.status.isSuccess()) throw HttpStatusException(response.status.value)
            val body = response.readBodyBytes(MAX_FEED_BYTES)
                ?: throw OpdsFormatException(FEED_TOO_LARGE_MESSAGE)
            parser.parseOpenSearchTemplate(body, response.finalUrl)
        }

    /** The headers of every feed request; set, not appended, like OkHttp's Request.Builder.header. */
    private fun HttpRequestBuilder.feedHeaders(url: String, catalog: OpdsCatalogConfig?) {
        headers[HttpHeaders.Accept] = OpdsHttp.ACCEPT_FEED
        headers[HttpHeaders.UserAgent] = OpdsHttp.USER_AGENT
        // Credentials only go to the catalogue's own hosts.
        if (catalog != null && catalog.owns(url)) {
            catalog.authHeaders().forEach { (name, value) -> headers[name] = value }
        }
    }

    /** Turns a feed's search link into a `{searchTerms}` template (fetching OpenSearch if needed). */
    suspend fun templateForLink(link: OpdsLink, catalog: OpdsCatalogConfig?): String? = withContext(Dispatchers.IO) {
        if (link.isSearchTemplate) return@withContext link.href
        cacheLock.withLock { linkTemplateCache[link.href] }?.let { cached -> return@withContext cached.ifEmpty { null } }
        if (!link.isOpenSearchDescription && link.type?.contains("atom", ignoreCase = true) == true) {
            return@withContext null
        }
        // Let failures reach the caller: neither cache may remember a failed
        // request as a catalogue that permanently has no search support.
        val template = fetchOpenSearchTemplate(link.href, catalog)
        cacheLock.withLock { linkTemplateCache[link.href] = template.orEmpty() }
        template
    }

    /** The catalogue's search template from its root feed, cached per catalogue. */
    suspend fun searchTemplate(catalog: OpdsCatalogConfig): String? {
        cacheLock.withLock { templateCache[catalog.id] }?.let { return it.ifEmpty { null } }
        val root = try {
            fetchFeed(catalog.url, catalog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // not cached: the catalogue may be reachable later
        }
        val template = try {
            root.searchLink?.let { templateForLink(it, catalog) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // allow legacy search now and retry OpenSearch next time
        }
        cacheLock.withLock { templateCache[catalog.id] = template.orEmpty() }
        return template
    }

    /** Searches inside a feed that offers its own search link. */
    suspend fun searchWithLink(link: OpdsLink, catalog: OpdsCatalogConfig?, query: String): OpdsFeed {
        val template = templateForLink(link, catalog)
            ?: throw OpdsFormatException("Каталог не поддерживает поиск")
        return fetchFeed(OpenSearch.expand(template, query), catalog)
    }

    /**
     * Searches one catalogue. Built-in catalogues keep their proven URL
     * patterns first; user catalogues use their OpenSearch template first.
     * The first non-empty feed wins; an empty but valid feed is a valid
     * "nothing found".
     */
    suspend fun searchCatalog(catalog: OpdsCatalogConfig, query: String, type: OpdsSearchType): OpdsFeed {
        val trimmed = query.trim()
        val legacy = legacySearchUrls(catalog, trimmed, type)
        val tried = mutableSetOf<String>()
        var lastError: Throwable? = null
        var emptyFeed: OpdsFeed? = null

        // Returns the first feed with entries; remembers empty feeds and errors.
        suspend fun tryUrls(urls: List<String>): OpdsFeed? {
            for (url in urls) {
                if (!tried.add(url)) continue
                try {
                    val feed = fetchFeed(url, catalog)
                    if (feed.entries.isNotEmpty()) return feed
                    if (emptyFeed == null) emptyFeed = feed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpStatusException) {
                    if (e.code == 401) throw e
                    lastError = e
                } catch (e: Exception) {
                    lastError = e
                }
            }
            return null
        }

        // Only the catalogue with a scoped search of its own (Flibusta) is asked its
        // way first: "searchType=books" is the one way to search books and nothing
        // else, and the template it advertises cannot do it. Every other catalogue
        // is asked the way it says it wants to be asked, because the guessed shapes
        // are wrong for it — iknigi and ekniga declare "?query={searchTerms}" and
        // answer the guessed "?searchTerm=" with an error notice, so each of their
        // searches used to throw away four requests before the right one.
        val ownUrlsFirst = catalog.supportsScopedSearch
        if (ownUrlsFirst) tryUrls(legacy)?.let { return it }
        // The template costs a request to the root feed, which the feed cache almost
        // always already holds.
        val template = searchTemplate(catalog)
        if (template != null) tryUrls(listOf(OpenSearch.expand(template, trimmed)))?.let { return it }
        if (!ownUrlsFirst) tryUrls(legacy)?.let { return it }

        emptyFeed?.let { return it }
        if (tried.isEmpty()) throw OpdsFormatException("Каталог не поддерживает поиск")
        throw lastError ?: IOException("Каталог недоступен")
    }

    /** Searches all [catalogs] in parallel; each one reports its own error. */
    suspend fun searchAll(
        catalogs: List<OpdsCatalogConfig>,
        query: String,
        type: OpdsSearchType,
        timeoutMillis: Long = SEARCH_TIMEOUT_MS
    ): List<CatalogSearchResult> = coroutineScope {
        catalogs.map { catalog ->
            async(Dispatchers.IO) {
                try {
                    val feed = withTimeout(timeoutMillis) { searchCatalog(catalog, query, type) }
                    CatalogHealth.markReachable(catalog.id)
                    CatalogSearchResult(catalog, feed, null)
                } catch (e: TimeoutCancellationException) {
                    CatalogHealth.markUnreachable(catalog.id)
                    CatalogSearchResult(catalog, null, "Каталог не ответил вовремя")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A refused login or a missing page is an answer; anything else is not.
                    if (e !is HttpStatusException) CatalogHealth.markUnreachable(catalog.id)
                    CatalogSearchResult(catalog, null, describeOpdsError(e))
                }
            }
        }.awaitAll()
    }

    /**
     * Finds downloadable publications for [query] (used by the AI assistant).
     * When a catalogue answers with folders only (for example "books" and
     * "authors" sub-searches), up to [MAX_NAVIGATION_FEEDS] of them are opened.
     */
    suspend fun findPublications(query: String, catalogs: List<OpdsCatalogConfig>): List<FoundPublication> {
        // Flibusta first: it has almost everything and answers fastest, so the other
        // catalogues (some of which may be slow or unreachable) are only asked when it
        // found nothing to download, or could not be reached.
        val primary = catalogs.filter { it.id == BuiltInCatalogs.FLIBUSTA_ID }
        val others = catalogs.filter { it.id != BuiltInCatalogs.FLIBUSTA_ID }
        // Down a moment ago (blocked without a VPN): do not wait for it again, ask all at once.
        if (primary.isEmpty() || others.isEmpty() || primary.any { CatalogHealth.isLikelyDown(it.id) }) {
            return findPublicationsIn(catalogs, query, SEARCH_TIMEOUT_MS).foundOrThrow()
        }
        val first = findPublicationsIn(primary, query, PRIMARY_SEARCH_TIMEOUT_MS)
        if (first.found.any { it.publication.acquisitions.isNotEmpty() }) return first.found
        // The preferred catalogue found nothing to download: ask the rest, and keep
        // what it did find, so one catalogue that cannot be reached never discards
        // the results of another.
        val rest = findPublicationsIn(others, query, SEARCH_TIMEOUT_MS)
        return FindOutcome(
            found = first.found + rest.found,
            failures = first.failures + rest.failures,
            allFailed = first.allFailed && rest.allFailed
        ).foundOrThrow()
    }

    /** What the catalogues answered for one query: what was found, and who stayed silent. */
    private class FindOutcome(
        val found: List<FoundPublication>,
        /** "Flibusta — Каталог не ответил вовремя", per catalogue that failed. */
        val failures: List<String>,
        /** True when not one of the catalogues asked answered at all. */
        val allFailed: Boolean
    ) {
        /**
         * The publications, unless nothing was found because nothing answered —
         * only then is this a connection failure and not an empty result, and the
         * message names every catalogue and its own reason.
         */
        fun foundOrThrow(): List<FoundPublication> {
            if (found.isNotEmpty() || !allFailed || failures.isEmpty()) return found
            throw IOException("ни один каталог не ответил (" + failures.joinToString("; ") + ")")
        }
    }

    private suspend fun findPublicationsIn(
        catalogs: List<OpdsCatalogConfig>,
        query: String,
        timeoutMillis: Long
    ): FindOutcome {
        val results = searchAll(catalogs, query, OpdsSearchType.BOOKS, timeoutMillis)
        val failures = results.mapNotNull { result -> result.error?.let { "${result.catalog.name} — $it" } }
        val allFailed = results.isNotEmpty() && results.all { it.feed == null }
        val direct = results.flatMap { result ->
            result.feed?.publications.orEmpty().map { FoundPublication(result.catalog, it) }
        }
        if (direct.any { it.publication.acquisitions.isNotEmpty() }) {
            return FindOutcome(direct, failures, allFailed = false)
        }

        val folders = results.flatMap { result ->
            result.feed?.navigation.orEmpty().map { result.catalog to it }
        }.take(MAX_NAVIGATION_FEEDS)
        val expanded = coroutineScope {
            folders.map { (catalog, folder) ->
                async(Dispatchers.IO) {
                    try {
                        fetchFeed(folder.url, catalog).publications.map { FoundPublication(catalog, it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        val combined = (direct + expanded).distinctBy { it.catalog.id + "|" + it.publication.key }
        return FindOutcome(combined, failures, allFailed)
    }

    private fun legacySearchUrls(catalog: OpdsCatalogConfig, query: String, type: OpdsSearchType): List<String> {
        // The catalogue's preferred base, unless its host just failed to answer:
        // a search must not start on the domain that is blocked for this user when
        // a mirror of the same site is known to work. fetchFeed still tries the
        // other bases afterwards.
        val base = HostHealth.order(catalog.searchBaseUrls).firstOrNull()?.trimEnd('/') ?: return emptyList()
        val encoded = OpdsUrls.encodeQuery(query)
        val urls = mutableListOf<String>()
        if (catalog.supportsScopedSearch) {
            urls += "$base/opds/search?searchType=${type.legacyType}&searchTerm=$encoded"
        }
        if (type == OpdsSearchType.BOOKS || !catalog.supportsScopedSearch) {
            // Most OPDS 1.x catalogues accept one of these shapes.
            urls += "$base/opds/search?searchTerm=$encoded"
            urls += "$base/opds/search?searchType=books&searchTerm=$encoded"
            urls += "$base/opds/search?q=$encoded"
            urls += "$base/opds/search/$encoded"
        }
        return urls.distinct()
    }

    companion object {
        /**
         * One catalogue's whole search. It has to cover a first attempt that is
         * waited out (a blocked mirror) plus the attempt that answers, or a search
         * fails on a catalogue that browsing reaches perfectly well — browsing has
         * no deadline at all. [HostHealth] makes the dead attempt happen once
         * rather than before every request.
         */
        const val SEARCH_TIMEOUT_MS = 40_000L

        /** The first, preferred catalogue gets less time: a blocked one must not hold up the rest. */
        const val PRIMARY_SEARCH_TIMEOUT_MS = 30_000L

        /**
         * One request for a feed. The client's own call timeout is the hard limit;
         * this one is here to be caught, so a host that stops answering mid-feed is
         * remembered as down and the next mirror is tried at once.
         */
        private const val ATTEMPT_TIMEOUT_MS = 28_000L
        private const val ATTEMPT_TIMED_OUT_MESSAGE = "Сервер слишком долго не отвечает"
        private const val MAX_NAVIGATION_FEEDS = 8

        /**
         * Feeds are parsed from memory (the shared parser takes bytes); a page
         * of a real catalogue is far below this, a "feed" above it is not one.
         */
        private const val MAX_FEED_BYTES = 16L * 1024 * 1024
        private const val FEED_TOO_LARGE_MESSAGE = "Ответ сервера слишком большой для OPDS-каталога"
    }
}

/**
 * Readable message for a failed catalogue request. A malformed address fails
 * in OkHttp (IllegalArgumentException) or in Ktor's URL parser.
 */
fun describeOpdsError(error: Throwable): String = when (error) {
    is OpdsFormatException -> error.message ?: "Ответ сервера не является OPDS-каталогом"
    is IllegalArgumentException, is URLParserException -> "Некорректный адрес каталога"
    else -> describeNetworkError(error)
}
