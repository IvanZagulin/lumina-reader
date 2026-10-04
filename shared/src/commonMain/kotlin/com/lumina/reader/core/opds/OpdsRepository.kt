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
    private val clientProvider: () -> HttpClient = { OpdsHttp.feedClient },
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
    suspend fun fetchFeed(url: String, catalog: OpdsCatalogConfig?): OpdsFeed = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (candidate in OpdsUrls.candidates(url, catalog?.mirrorBaseUrls.orEmpty())) {
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
        if (!unauthorised) {
            feedCache.read(url)?.let { stale ->
                runCatching { parser.parseFeed(stale.body, stale.finalUrl) }.getOrNull()?.let { return@withContext it }
            }
        }
        throw error ?: IOException("Не удалось открыть каталог")
    }

    private suspend fun fetchOnce(url: String, catalog: OpdsCatalogConfig?): OpdsFeed {
        // A copy from the last day (an hour for searches) is shown at once.
        feedCache.read(url, OpdsFeedCache.maxAgeFor(url))?.let { hit ->
            runCatching { parser.parseFeed(hit.body, hit.finalUrl) }.getOrNull()?.let { return it }
        }
        NetworkProxy.ready()
        return clientProvider().prepareGet(url) { feedHeaders(url, catalog) }.execute { response ->
            if (!response.status.isSuccess()) throw HttpStatusException(response.status.value)
            val body = response.readBodyBytes(MAX_FEED_BYTES)
                ?: throw OpdsFormatException(FEED_TOO_LARGE_MESSAGE)
            val feed = parser.parseFeed(body, response.finalUrl)
            feedCache.write(url, response.finalUrl, body)
            feed
        }
    }

    private suspend fun fetchOpenSearchTemplate(url: String, catalog: OpdsCatalogConfig?): String? =
        clientProvider().prepareGet(url) { feedHeaders(url, catalog) }.execute { response ->
            if (!response.status.isSuccess()) throw HttpStatusException(response.status.value)
            val body = response.readBodyBytes(MAX_FEED_BYTES) ?: return@execute null
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
        val template = runCatching { fetchOpenSearchTemplate(link.href, catalog) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
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
        val template = root.searchLink?.let { templateForLink(it, catalog) }
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

        if (catalog.builtIn) tryUrls(legacy)?.let { return it }
        // The template is only looked up when needed: it costs a request to the root feed.
        val template = searchTemplate(catalog)
        if (template != null) tryUrls(listOf(OpenSearch.expand(template, trimmed)))?.let { return it }
        if (!catalog.builtIn) tryUrls(legacy)?.let { return it }

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
            return findPublicationsIn(catalogs, query, SEARCH_TIMEOUT_MS)
        }
        val first = try {
            findPublicationsIn(primary, query, PRIMARY_SEARCH_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (first.any { it.publication.acquisitions.isNotEmpty() }) return first
        return first + findPublicationsIn(others, query, SEARCH_TIMEOUT_MS)
    }

    private suspend fun findPublicationsIn(
        catalogs: List<OpdsCatalogConfig>,
        query: String,
        timeoutMillis: Long
    ): List<FoundPublication> {
        val results = searchAll(catalogs, query, OpdsSearchType.BOOKS, timeoutMillis)
        val direct = results.flatMap { result ->
            result.feed?.publications.orEmpty().map { FoundPublication(result.catalog, it) }
        }
        if (direct.any { it.publication.acquisitions.isNotEmpty() }) return direct

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
        if (combined.isEmpty() && results.isNotEmpty() && results.all { it.feed == null }) {
            val failed = results.joinToString { it.catalog.name }
            throw IOException("Не удалось подключиться к OPDS-каталогам: $failed")
        }
        return combined
    }

    private fun legacySearchUrls(catalog: OpdsCatalogConfig, query: String, type: OpdsSearchType): List<String> {
        val base = catalog.siteBaseUrl?.trimEnd('/') ?: return emptyList()
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
        const val SEARCH_TIMEOUT_MS = 20_000L

        /** The first, preferred catalogue gets less time: a blocked one must not hold up the rest. */
        const val PRIMARY_SEARCH_TIMEOUT_MS = 12_000L
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
