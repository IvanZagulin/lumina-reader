package com.lumina.reader.core.parser.epub

import android.util.Xml
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EpubParser : BookParser {

    override fun parse(file: File): ParsedBook {
        return parse(FileInputStream(file), file.name)
    }

    override fun parse(inputStream: InputStream, fileName: String): ParsedBook {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    entries[entry.name.replace("\\", "/")] = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // 1. Locate container.xml
        val opfPath = findOpfPath(entries) ?: "content.opf"
        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

        // 2. Parse OPF
        val opfBytes = entries[opfPath] ?: entries.entries.firstOrNull { it.key.endsWith(".opf") }?.value
        val (metadata, manifest, spine) = if (opfBytes != null) {
            parseOpf(opfBytes)
        } else {
            Triple(EpubMetadata(title = fileName.substringBeforeLast(".")), emptyMap<String, ManifestItem>(), emptyList<String>())
        }

        // 3. Extract cover
        var coverBytes: ByteArray? = null
        val coverItem = manifest.values.firstOrNull { it.isCover || it.id.contains("cover", ignoreCase = true) || it.href.contains("cover", ignoreCase = true) }
        if (coverItem != null) {
            val resolvedCoverPath = normalizePath(opfDir + coverItem.href)
            coverBytes = entries[resolvedCoverPath] ?: entries[coverItem.href]
        }

        // 4. Parse Spine chapters
        val chapters = mutableListOf<Chapter>()
        val tocList = mutableListOf<TocItem>()

        val spineHrefs = spine.mapNotNull { idref -> manifest[idref]?.href }
            .ifEmpty {
                // fallback to all html/xhtml files sorted
                entries.keys.filter { it.endsWith(".html") || it.endsWith(".xhtml") || it.endsWith(".htm") }.sorted()
            }

        spineHrefs.forEachIndexed { index, href ->
            val fullPath = normalizePath(opfDir + href)
            val htmlBytes = entries[fullPath] ?: entries[href]
            if (htmlBytes != null) {
                val (chapterTitle, paragraphs) = parseHtmlContent(htmlBytes)
                val effectiveTitle = chapterTitle.ifBlank { "Глава ${index + 1}" }
                val content = paragraphs.joinToString("\n\n")

                if (paragraphs.size > 50) {
                    val chunks = paragraphs.chunked(40)
                    chunks.forEachIndexed { subIdx, chunkParagraphs ->
                        val subTitle = if (chunks.size > 1) "$effectiveTitle (часть ${subIdx + 1})" else effectiveTitle
                        val chapter = Chapter(
                            index = chapters.size,
                            title = subTitle,
                            content = chunkParagraphs.joinToString("\n\n"),
                            href = "$href#part_$subIdx",
                            paragraphs = chunkParagraphs
                        )
                        chapters.add(chapter)
                        tocList.add(TocItem(id = "${href}_$subIdx", title = subTitle, chapterIndex = chapter.index))
                    }
                } else if (content.isNotBlank()) {
                    val chapter = Chapter(
                        index = chapters.size,
                        title = effectiveTitle,
                        content = content,
                        href = href,
                        paragraphs = paragraphs
                    )
                    chapters.add(chapter)
                    tocList.add(TocItem(id = href, title = effectiveTitle, chapterIndex = chapter.index))
                }
            }
        }

        if (chapters.isEmpty()) {
            chapters.add(Chapter(index = 0, title = "Текст", content = "Не удалось извлечь текст из книги", paragraphs = listOf("Не удалось извлечь текст из книги")))
        }

        return ParsedBook(
            title = metadata.title.ifBlank { fileName.substringBeforeLast(".") },
            author = metadata.creator.ifBlank { "Неизвестный автор" },
            description = metadata.description,
            seriesName = metadata.seriesName,
            seriesOrder = metadata.seriesOrder,
            coverBytes = coverBytes,
            chapters = chapters,
            tableOfContents = tocList,
            format = BookFormat.EPUB
        )
    }

    private fun findOpfPath(entries: Map<String, ByteArray>): String? {
        val containerXml = entries["META-INF/container.xml"] ?: return null
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(containerXml), "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) return fullPath
            }
            event = parser.next()
        }
        return null
    }

    private data class EpubMetadata(
        var title: String = "",
        var creator: String = "",
        var description: String = "",
        var coverId: String? = null,
        var seriesName: String = "",
        var seriesOrder: Int = 0
    )

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val isCover: Boolean = false
    )

    private fun parseOpf(opfBytes: ByteArray): Triple<EpubMetadata, Map<String, ManifestItem>, List<String>> {
        val metadata = EpubMetadata()
        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(opfBytes), "UTF-8")
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "title", "dc:title" -> metadata.title = parser.nextText().trim()
                    "creator", "dc:creator" -> metadata.creator = parser.nextText().trim()
                    "description", "dc:description" -> metadata.description = parser.nextText().trim()
                    "meta" -> {
                        val name = parser.getAttributeValue(null, "name")
                        val content = parser.getAttributeValue(null, "content")
                        val property = parser.getAttributeValue(null, "property")
                        when {
                            name.equals("cover", ignoreCase = true) && content != null -> {
                                metadata.coverId = content
                            }
                            name.equals("calibre:series", ignoreCase = true) && content != null -> {
                                metadata.seriesName = content.trim()
                            }
                            name.equals("calibre:series_index", ignoreCase = true) && content != null -> {
                                metadata.seriesOrder = content.toSeriesOrder()
                            }
                            property.equals("belongs-to-collection", ignoreCase = true) -> {
                                val collectionName = parser.nextText().trim()
                                if (collectionName.isNotBlank()) metadata.seriesName = collectionName
                            }
                            property.equals("group-position", ignoreCase = true) -> {
                                metadata.seriesOrder = parser.nextText().toSeriesOrder()
                            }
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        val properties = parser.getAttributeValue(null, "properties") ?: ""
                        val isCover = properties.contains("cover-image") || id == metadata.coverId
                        if (id.isNotBlank() && href.isNotBlank()) {
                            manifest[id] = ManifestItem(id, href, mediaType, isCover)
                        }
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (!idref.isNullOrBlank()) {
                            spine.add(idref)
                        }
                    }
                }
            }
            event = parser.next()
        }

        return Triple(metadata, manifest, spine)
    }

    private fun String.toSeriesOrder(): Int =
        trim().toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0

    private fun parseHtmlContent(htmlBytes: ByteArray): Pair<String, List<String>> {
        val html = String(htmlBytes, Charsets.UTF_8)
        var title = ""

        // Extract title from <title> or <h1>
        val titleMatcher = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)
        if (titleMatcher != null) {
            title = stripHtml(titleMatcher.groupValues[1])
        }
        if (title.isBlank()) {
            val h1Matcher = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.IGNORE_CASE).find(html)
            if (h1Matcher != null) {
                title = stripHtml(h1Matcher.groupValues[1])
            }
        }

        // Clean up body content
        val bodyContent = html.substringAfter("<body", html).substringBefore("</body>")
        val rawParagraphs = bodyContent.split(Regex("</?(?:p|div|h[1-6]|li|blockquote)[^>]*>", RegexOption.IGNORE_CASE))

        val paragraphs = rawParagraphs.map { p ->
            stripHtml(p).trim()
        }.filter { it.isNotBlank() }

        return Pair(title, paragraphs)
    }

    private fun stripHtml(input: String): String {
        return input
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("&#([0-9]+);")) { match ->
                try {
                    match.groupValues[1].toInt().toChar().toString()
                } catch (e: Exception) {
                    ""
                }
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
                try {
                    match.groupValues[1].toInt(16).toChar().toString()
                } catch (e: Exception) {
                    ""
                }
            }
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun normalizePath(path: String): String {
        val parts = path.split("/")
        val stack = mutableListOf<String>()
        for (part in parts) {
            if (part == "." || part.isEmpty()) continue
            if (part == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else {
                stack.add(part)
            }
        }
        return stack.joinToString("/")
    }
}
