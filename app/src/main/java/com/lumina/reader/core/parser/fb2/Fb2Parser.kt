package com.lumina.reader.core.parser.fb2

import android.util.Base64
import android.util.Log
import android.util.Xml
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal fun fb2ChapterTitle(explicitTitle: String, chapterIndex: Int): String =
    explicitTitle.ifBlank { "Глава ${chapterIndex + 1}" }

class Fb2Parser : BookParser {

    override fun parse(file: File): ParsedBook {
        return parse(FileInputStream(file), file.name)
    }

    override fun parse(inputStream: InputStream, fileName: String): ParsedBook {
        val isZip = fileName.lowercase().endsWith(".zip")
        var effectiveStream: InputStream? = null
        var zipStream: ZipInputStream? = null

        try {
            if (isZip) {
                zipStream = ZipInputStream(inputStream)
                var entry: ZipEntry? = zipStream.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    if (!entry.isDirectory && (name.endsWith(".fb2") || name.endsWith(".xml"))) {
                        effectiveStream = zipStream
                        break
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
                if (effectiveStream == null) {
                    return createEmptyBook(fileName, isZip)
                }
            } else {
                effectiveStream = inputStream
            }

            // Wrap in BufferedInputStream to allow mark/reset for encoding detection
            val bufferedStream = BufferedInputStream(effectiveStream, 64 * 1024)
            bufferedStream.mark(2048)

            val buffer = ByteArray(2048)
            val bytesRead = bufferedStream.read(buffer, 0, buffer.size)
            bufferedStream.reset()

            var charsetName = "UTF-8"
            if (bytesRead > 0) {
                val headerSnippet = String(buffer, 0, bytesRead, Charsets.US_ASCII)
                val encodingMatch = Regex("encoding=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(headerSnippet)
                if (encodingMatch != null) {
                    charsetName = encodingMatch.groupValues[1]
                }
            }

            return try {
                parseXmlStream(bufferedStream, fileName, charsetName, isZip)
            } catch (e: Exception) {
                e.printStackTrace()
                createEmptyBook(fileName, isZip, "Ошибка чтения XML: ${e.message}")
            }

        } finally {
            if (isZip) {
                zipStream?.close()
            } else {
                effectiveStream?.close()
                inputStream.close()
            }
        }
    }

    private fun parseXmlStream(stream: InputStream, fileName: String, charsetName: String, isZip: Boolean): ParsedBook {
        var title = ""
        var firstName = ""
        var lastName = ""
        var middleName = ""
        var annotation = ""
        var coverImageId = ""
        var seriesName = ""
        var seriesOrder = 0

        var coverBase64: String? = null
        val images = mutableMapOf<String, ByteArray>()
        val chapters = mutableListOf<Chapter>()
        val tocList = mutableListOf<TocItem>()

        val currentChapterParagraphs = mutableListOf<String>()
        var currentChapterTitle = ""

        var inTitleInfo = false
        var inAuthor = false
        var inAnnotation = false
        var inCoverpage = false
        var inBody = false
        var inSection = false
        var inTitle = false
        var inCoverBinary = false
        var currentBinaryId = ""
        val binaryBuilder = StringBuilder()

        val parser = Xml.newPullParser()
        parser.setInput(stream, charsetName)

        var event = parser.eventType

        fun flushChapter() {
            if (currentChapterParagraphs.isNotEmpty() || currentChapterTitle.isNotEmpty()) {
                val chapterIndex = chapters.size
                val finalTitle = fb2ChapterTitle(currentChapterTitle, chapterIndex)
                val content = currentChapterParagraphs.joinToString("\n\n")
                val chapter = Chapter(
                    index = chapterIndex,
                    title = finalTitle,
                    content = content,
                    paragraphs = ArrayList(currentChapterParagraphs)
                )
                chapters.add(chapter)
                tocList.add(TocItem(id = "ch_$chapterIndex", title = finalTitle, chapterIndex = chapterIndex))
                currentChapterParagraphs.clear()
                currentChapterTitle = ""
            }
        }

        fun readText(parser: XmlPullParser): String {
            val sb = java.lang.StringBuilder()
            var depth = 1
            while (depth > 0) {
                val ev = parser.next()
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (parser.name.lowercase() == "image") {
                            val href = parser.getAttributeValue(null, "l:href")
                                ?: parser.getAttributeValue(null, "href")
                                ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                            if (href != null) {
                                val id = href.removePrefix("#")
                                sb.append("\n[IMG:$id]\n")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT -> sb.append(parser.text)
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return sb.toString()
        }

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    when (tag) {
                        "title-info" -> inTitleInfo = true
                        "book-title" -> if (inTitleInfo) title = readText(parser).trim()
                        "author" -> if (inTitleInfo) inAuthor = true
                        "first-name" -> if (inAuthor) firstName = readText(parser).trim()
                        "last-name" -> if (inAuthor) lastName = readText(parser).trim()
                        "middle-name" -> if (inAuthor) middleName = readText(parser).trim()
                        "annotation" -> if (inTitleInfo) inAnnotation = true
                        "sequence" -> if (inTitleInfo && seriesName.isBlank()) {
                            seriesName = parser.getAttributeValue(null, "name")?.trim().orEmpty()
                            seriesOrder = parser.getAttributeValue(null, "number")
                                ?.trim()
                                ?.toIntOrNull()
                                ?.coerceAtLeast(0)
                                ?: 0
                        }
                        "coverpage" -> inCoverpage = true
                        "image" -> {
                            val href = parser.getAttributeValue(null, "l:href")
                                ?: parser.getAttributeValue(null, "href")
                                ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                            Log.d("Fb2Parser", "IMAGE tag: href=$href inCoverpage=$inCoverpage inSection=$inSection inBody=$inBody")
                            if (href != null) {
                                val id = href.removePrefix("#")
                                if (inCoverpage) {
                                    coverImageId = id
                                } else if (inSection || inBody) {
                                    currentChapterParagraphs.add("[IMG:$id]")
                                    Log.d("Fb2Parser", "Added IMG paragraph: [IMG:$id]")
                                }
                            }
                        }
                        "body" -> {
                            val bodyName = parser.getAttributeValue(null, "name")
                            if (bodyName == null || bodyName != "notes") {
                                inBody = true
                            }
                        }
                        "section" -> {
                            if (inBody) {
                                inSection = true
                            }
                        }
                        "title" -> {
                            if (inSection) {
                                if (currentChapterParagraphs.isNotEmpty()) {
                                    flushChapter()
                                }
                                inTitle = true
                            }
                        }
                        "p" -> {
                            val text = readText(parser).trim()
                            if (inAnnotation) {
                                annotation += (if (annotation.isNotEmpty()) "\n" else "") + text
                            } else if (inTitle) {
                                currentChapterTitle = if (currentChapterTitle.isNotEmpty()) "$currentChapterTitle - $text" else text
                            } else if (inSection || inBody) {
                                if (text.isNotEmpty()) {
                                    val parts = text.split("\n")
                                    for (part in parts) {
                                        if (part.isNotBlank()) {
                                            currentChapterParagraphs.add(part.trim())
                                        }
                                    }
                                }
                            }
                        }
                        "empty-line" -> {
                            if (inSection || inBody) {
                                currentChapterParagraphs.add("")
                            }
                        }
                        "binary" -> {
                            currentBinaryId = parser.getAttributeValue(null, "id") ?: ""
                            inCoverBinary = true // Extract all binaries
                            binaryBuilder.clear()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inCoverBinary && binaryBuilder.length < 5000000) {
                        binaryBuilder.append(parser.text.trim())
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    when (tag) {
                        "title-info" -> inTitleInfo = false
                        "author" -> inAuthor = false
                        "annotation" -> inAnnotation = false
                        "coverpage" -> inCoverpage = false
                        "title" -> inTitle = false
                        "section" -> {
                            // Do nothing, let chapters continue unless a new title appears or body ends
                        }
                        "body" -> {
                            flushChapter()
                            inBody = false
                        }
                        "binary" -> {
                            if (inCoverBinary) {
                                inCoverBinary = false
                                if (binaryBuilder.isNotEmpty()) {
                                    val base64Str = binaryBuilder.toString()
                                    Log.d("Fb2Parser", "Decoded binary id='$currentBinaryId' base64len=${base64Str.length}")
                                    try {
                                        val bytes = Base64.decode(base64Str, Base64.DEFAULT)
                                        images[currentBinaryId] = bytes
                                        Log.d("Fb2Parser", "Stored image id='$currentBinaryId' bytes=${bytes.size}")
                                    } catch (e: Exception) {
                                        Log.e("Fb2Parser", "Failed to decode binary '$currentBinaryId': ${e.message}")
                                    }

                                    val isCover = (coverImageId.isNotBlank() && currentBinaryId.equals(coverImageId, ignoreCase = true)) ||
                                            (coverImageId.isBlank() && coverBase64 == null && currentBinaryId.contains("cover", ignoreCase = true))
                                    if (isCover) {
                                        coverBase64 = base64Str
                                    }
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }

        flushChapter()

        val authorName = listOf(lastName, firstName, middleName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "Неизвестный автор" }

        var coverBytes: ByteArray? = null
        if (coverBase64 != null) {
            try {
                coverBytes = Base64.decode(coverBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                // Ignore decode errors
            }
        }

        if (chapters.isEmpty()) {
            return createEmptyBook(fileName, isZip, "Текст не найден. Возможно, файл поврежден.")
        }

        return ParsedBook(
            title = title.ifBlank { fileName.substringBeforeLast(".") },
            author = authorName,
            description = annotation,
            seriesName = seriesName,
            seriesOrder = seriesOrder,
            coverBytes = coverBytes,
            chapters = chapters,
            tableOfContents = tocList,
            images = images,
            format = if (isZip) BookFormat.FB2_ZIP else BookFormat.FB2
        )
    }

    private fun createEmptyBook(fileName: String, isZip: Boolean, message: String = "Пустой или неподдерживаемый файл"): ParsedBook {
        val title = fileName.substringBeforeLast(".")
        val chapter = Chapter(
            index = 0,
            title = "Ошибка",
            content = message,
            paragraphs = listOf(message)
        )
        return ParsedBook(
            title = title,
            author = "Неизвестный автор",
            description = "",
            coverBytes = null,
            chapters = listOf(chapter),
            tableOfContents = listOf(TocItem(id = "err_0", title = "Ошибка", chapterIndex = 0)),
            format = if (isZip) BookFormat.FB2_ZIP else BookFormat.FB2
        )
    }
}
