package com.lumina.reader.core.network

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

data class OpdsBook(
    val title: String,
    val author: String,
    val downloadUrlEpub: String?,
    val downloadUrlFb2: String?
)

private data class ParsedOpdsFeed(
    val books: List<OpdsBook>,
    val navigationLinks: List<String>
)

private data class OpdsCatalog(
    val name: String,
    val baseUrls: List<String>,
    val supportsScopedSearch: Boolean = false
)

private data class CatalogSearchAttempt(
    val books: List<OpdsBook>,
    val succeeded: Boolean,
    val error: Throwable? = null
)

class OpdsClient {
    companion object {
        private val CATALOGS = listOf(
            OpdsCatalog(
                name = "Flibusta",
                baseUrls = listOf(
                    "https://flibusta.is",
                    "https://flibusta.site"
                ),
                supportsScopedSearch = true
            ),
            OpdsCatalog(
                name = "iKnigi",
                baseUrls = listOf("https://iknigi.net")
            ),
            OpdsCatalog(
                name = "EKNIGA",
                baseUrls = listOf("https://ekniga.org")
            ),
            OpdsCatalog(
                name = "CoolLib",
                baseUrls = listOf("https://coollib.in")
            )
        )

        private const val MAX_NAVIGATION_FEEDS = 8
        private const val CATALOG_SEARCH_TIMEOUT_MS = 12_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun searchBooks(query: String, searchType: String = "books"): List<OpdsBook> = withContext(Dispatchers.IO) {
        val attempts = coroutineScope {
            CATALOGS.map { catalog ->
                async(Dispatchers.IO) {
                    searchCatalogSafely(catalog, query, searchType)
                }
            }.awaitAll()
        }

        val books = attempts
            .flatMap { it.books }
            .distinctBy(::bookKey)

        if (books.isNotEmpty()) {
            return@withContext books
        }

        // An empty result from at least one reachable catalogue is a valid "not found".
        if (attempts.any { it.succeeded }) {
            return@withContext emptyList()
        }

        val failedCatalogs = CATALOGS.zip(attempts)
            .filter { (_, attempt) -> !attempt.succeeded }
            .joinToString { (catalog, _) -> catalog.name }

        throw Exception(
            "Не удалось подключиться к OPDS-каталогам: $failedCatalogs",
            attempts.lastOrNull { it.error != null }?.error
        )
    }

    suspend fun downloadBook(url: String): ByteArray = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        val catalog = findCatalogForUrl(url)
        val mirrorBaseUrls = catalog?.baseUrls
            ?: extractBaseUrl(url)?.let(::listOf)
            ?: emptyList()

        for (candidateUrl in buildUrlCandidates(url, mirrorBaseUrls)) {
            try {
                val request = Request.Builder()
                    .url(candidateUrl)
                    .header("User-Agent", "LuminaReader/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to download book: HTTP ${response.code}")
                    }

                    return@withContext response.body?.bytes()
                        ?: throw Exception("Empty download body")
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw Exception("Не удалось скачать книгу", lastError)
    }

    private suspend fun searchCatalogSafely(
        catalog: OpdsCatalog,
        query: String,
        searchType: String
    ): CatalogSearchAttempt {
        return try {
            val books = withTimeout(CATALOG_SEARCH_TIMEOUT_MS) {
                searchCatalog(catalog, query, searchType)
            }
            CatalogSearchAttempt(books = books, succeeded = true)
        } catch (e: TimeoutCancellationException) {
            CatalogSearchAttempt(books = emptyList(), succeeded = false, error = e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CatalogSearchAttempt(books = emptyList(), succeeded = false, error = e)
        }
    }

    private suspend fun searchCatalog(
        catalog: OpdsCatalog,
        query: String,
        searchType: String
    ): List<OpdsBook> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val encodedPathQuery = encodedQuery.replace("+", "%20")
        var lastError: Throwable? = null
        var hadSuccessfulFeed = false

        for (baseUrl in catalog.baseUrls) {
            val searchUrls = buildSearchUrls(
                catalog = catalog,
                baseUrl = baseUrl,
                encodedQuery = encodedQuery,
                encodedPathQuery = encodedPathQuery,
                searchType = searchType
            )

            for (url in searchUrls) {
                try {
                    val feed = fetchFeed(url, catalog.baseUrls)
                    hadSuccessfulFeed = true

                    if (feed.books.isNotEmpty()) {
                        return feed.books
                    }

                    if (searchType != "books" && feed.navigationLinks.isNotEmpty()) {
                        val booksFromNavigation = fetchBooksFromNavigation(
                            navigationLinks = feed.navigationLinks,
                            catalog = catalog
                        )
                        if (booksFromNavigation.isNotEmpty()) {
                            return booksFromNavigation
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        if (hadSuccessfulFeed) {
            return emptyList()
        }

        throw Exception("${catalog.name}: OPDS недоступен", lastError)
    }

    private fun buildSearchUrls(
        catalog: OpdsCatalog,
        baseUrl: String,
        encodedQuery: String,
        encodedPathQuery: String,
        searchType: String
    ): List<String> {
        val urls = mutableListOf<String>()

        if (catalog.supportsScopedSearch) {
            urls += "$baseUrl/opds/search?searchType=$searchType&searchTerm=$encodedQuery"
        }

        // Most OPDS 1.x catalogues expose searchTerm either directly or through
        // an OpenSearch-compatible endpoint. Keep a small fallback set so the
        // app remains useful when a catalogue changes the exact route shape.
        urls += "$baseUrl/opds/search?searchTerm=$encodedQuery"
        urls += "$baseUrl/opds/search?searchType=books&searchTerm=$encodedQuery"
        urls += "$baseUrl/opds/search?q=$encodedQuery"
        urls += "$baseUrl/opds/search/$encodedPathQuery"

        return urls.distinct()
    }

    private fun fetchFeed(url: String, mirrorBaseUrls: List<String>): ParsedOpdsFeed {
        var lastError: Throwable? = null

        for (candidateUrl in buildUrlCandidates(url, mirrorBaseUrls)) {
            try {
                val request = Request.Builder()
                    .url(candidateUrl)
                    .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                    .header("User-Agent", "LuminaReader/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("OPDS returned HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Empty OPDS response")
                    val responseUrl = response.request.url.toString()
                    return body.byteStream().use { parseOpds(it, responseUrl) }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw Exception("Не удалось открыть OPDS-раздел", lastError)
    }

    private suspend fun fetchBooksFromNavigation(
        navigationLinks: List<String>,
        catalog: OpdsCatalog
    ): List<OpdsBook> = coroutineScope {
        navigationLinks
            .distinct()
            .take(MAX_NAVIGATION_FEEDS)
            .map { link ->
                async(Dispatchers.IO) {
                    runCatching {
                        fetchFeed(link, catalog.baseUrls).books
                    }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy(::bookKey)
    }

    private fun buildUrlCandidates(url: String, mirrorBaseUrls: List<String>): List<String> {
        val pathAndQuery = try {
            val uri = URI(url)
            val path = uri.rawPath ?: ""
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            "$path$query"
        } catch (_: Exception) {
            url
        }

        val candidates = mutableListOf<String>()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            candidates.add(url)
        }

        mirrorBaseUrls.forEach { baseUrl ->
            val resolved = if (pathAndQuery.startsWith("/")) {
                "$baseUrl$pathAndQuery"
            } else {
                "$baseUrl/$pathAndQuery"
            }
            candidates.add(resolved)
        }

        return candidates.distinct()
    }

    private fun parseOpds(inputStream: InputStream, feedUrl: String): ParsedOpdsFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        val books = mutableListOf<OpdsBook>()
        val navigationLinks = mutableListOf<String>()
        var eventType = parser.eventType

        var currentTitle = ""
        var currentAuthor = ""
        var epubUrl: String? = null
        var fb2Url: String? = null
        var currentNavigationLinks = mutableListOf<String>()
        var insideEntry = false
        var insideAuthor = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val name = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (name) {
                        "entry" -> {
                            insideEntry = true
                            currentTitle = ""
                            currentAuthor = ""
                            epubUrl = null
                            fb2Url = null
                            currentNavigationLinks = mutableListOf()
                        }

                        "title" -> {
                            if (insideEntry) {
                                currentTitle = parser.nextText()
                            }
                        }

                        "author" -> {
                            if (insideEntry) {
                                insideAuthor = true
                            }
                        }

                        "name" -> {
                            if (insideEntry && insideAuthor) {
                                currentAuthor = parser.nextText()
                            }
                        }

                        "link" -> {
                            if (insideEntry) {
                                val type = parser.getAttributeValue(null, "type")
                                val href = parser.getAttributeValue(null, "href")
                                val rel = parser.getAttributeValue(null, "rel")

                                if (!href.isNullOrBlank()) {
                                    val absoluteHref = resolveUrl(feedUrl, href)
                                    if (rel?.contains("acquisition", ignoreCase = true) == true) {
                                        when {
                                            isEpubLink(type, href) -> epubUrl = absoluteHref
                                            isFb2Link(type, href) -> fb2Url = absoluteHref
                                        }
                                    } else if (type?.contains("application/atom+xml", ignoreCase = true) == true) {
                                        currentNavigationLinks.add(absoluteHref)
                                    }
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (name) {
                        "entry" -> {
                            insideEntry = false
                            if (currentTitle.isNotBlank() && (epubUrl != null || fb2Url != null)) {
                                books.add(OpdsBook(currentTitle, currentAuthor, epubUrl, fb2Url))
                            } else {
                                navigationLinks.addAll(currentNavigationLinks)
                            }
                        }

                        "author" -> {
                            insideAuthor = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return ParsedOpdsFeed(
            books = books,
            navigationLinks = navigationLinks.distinct()
        )
    }

    private fun isEpubLink(type: String?, href: String): Boolean {
        val lowerType = type.orEmpty().lowercase()
        val lowerHref = href.lowercase()
        return "epub" in lowerType || lowerHref.substringBefore('?').endsWith(".epub")
    }

    private fun isFb2Link(type: String?, href: String): Boolean {
        val lowerType = type.orEmpty().lowercase()
        val lowerHref = href.lowercase().substringBefore('?')
        return "fb2" in lowerType ||
            "fictionbook" in lowerType ||
            lowerHref.endsWith(".fb2") ||
            lowerHref.endsWith(".fb2.zip")
    }

    private fun resolveUrl(feedUrl: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href
        }

        return try {
            URI(feedUrl).resolve(href).toString()
        } catch (_: Exception) {
            val baseUrl = extractBaseUrl(feedUrl).orEmpty()
            if (href.startsWith("/")) "$baseUrl$href" else "$baseUrl/$href"
        }
    }

    private fun findCatalogForUrl(url: String): OpdsCatalog? {
        val host = try {
            URI(url).host
        } catch (_: Exception) {
            null
        } ?: return null

        return CATALOGS.firstOrNull { catalog ->
            catalog.baseUrls.any { baseUrl ->
                try {
                    URI(baseUrl).host.equals(host, ignoreCase = true)
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private fun bookKey(book: OpdsBook): String = listOf(
        book.title.trim().lowercase(),
        book.author.trim().lowercase()
    ).joinToString("|")

    private fun extractBaseUrl(url: String): String? = try {
        val uri = URI(url)
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) null else "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        null
    }
}
