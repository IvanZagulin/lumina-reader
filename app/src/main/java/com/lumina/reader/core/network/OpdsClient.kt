package com.lumina.reader.core.network

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class OpdsClient {
    companion object {
        private val BASE_URLS = listOf(
            "https://flibusta.is",
            "https://flibusta.site"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun searchBooks(query: String, searchType: String = "books"): List<OpdsBook> = withContext(Dispatchers.IO) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        var lastError: Throwable? = null

        for (baseUrl in BASE_URLS) {
            val url = "$baseUrl/opds/search?searchType=$searchType&searchTerm=$encodedQuery"

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                    .header("User-Agent", "LuminaReader/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("OPDS $baseUrl returned HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Empty OPDS response from $baseUrl")
                    return@withContext body.byteStream().use { parseOpds(it, baseUrl) }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw Exception(
            "Не удалось подключиться к OPDS-каталогу",
            lastError
        )
    }

    suspend fun downloadBook(url: String): ByteArray = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        for (candidateUrl in buildDownloadCandidates(url)) {
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

    private fun buildDownloadCandidates(url: String): List<String> {
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

        BASE_URLS.forEach { baseUrl ->
            val resolved = if (pathAndQuery.startsWith("/")) {
                "$baseUrl$pathAndQuery"
            } else {
                "$baseUrl/$pathAndQuery"
            }
            candidates.add(resolved)
        }

        return candidates.distinct()
    }

    private fun parseOpds(inputStream: InputStream, baseUrl: String): List<OpdsBook> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        val books = mutableListOf<OpdsBook>()
        var eventType = parser.eventType

        var currentTitle = ""
        var currentAuthor = ""
        var epubUrl: String? = null
        var fb2Url: String? = null
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

                                if (rel?.contains("acquisition") == true && !href.isNullOrBlank()) {
                                    val absoluteHref = resolveUrl(baseUrl, href)
                                    if (type?.contains("epub", ignoreCase = true) == true) {
                                        epubUrl = absoluteHref
                                    } else if (type?.contains("fb2", ignoreCase = true) == true) {
                                        fb2Url = absoluteHref
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

        return books
    }

    private fun resolveUrl(baseUrl: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href
        }

        return if (href.startsWith("/")) {
            "$baseUrl$href"
        } else {
            "$baseUrl/$href"
        }
    }
}
