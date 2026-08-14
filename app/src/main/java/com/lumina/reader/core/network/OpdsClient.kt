package com.lumina.reader.core.network

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class OpdsBook(
    val title: String,
    val author: String,
    val downloadUrlEpub: String?,
    val downloadUrlFb2: String?
)

class OpdsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun searchBooks(query: String, searchType: String = "books"): List<OpdsBook> = withContext(Dispatchers.IO) {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://flibusta.site/opds/search?searchType=$searchType&searchTerm=$encodedQuery"
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Failed to search OPDS: ${response.code}")
        }

        response.body?.byteStream()?.use { parseOpds(it) } ?: emptyList()
    }
    
    suspend fun downloadBook(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://flibusta.site$url").build()
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("Failed to download book: ${response.code}")
        }
        
        response.body?.bytes() ?: throw Exception("Empty body")
    }

    private fun parseOpds(inputStream: InputStream): List<OpdsBook> {
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
                                if (rel?.contains("acquisition") == true) {
                                    if (type?.contains("epub") == true) {
                                        epubUrl = href
                                    } else if (type?.contains("fb2") == true) {
                                        fb2Url = href
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
}
