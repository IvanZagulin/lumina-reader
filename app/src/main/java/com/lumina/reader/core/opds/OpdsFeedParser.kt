package com.lumina.reader.core.opds

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Streaming parser for OPDS 1.x (Atom) feeds and OpenSearch descriptions.
 *
 * Namespace processing is off (prefixes are stripped by hand) so feeds with
 * undeclared prefixes still parse; DOCTYPE declarations are not processed and
 * no external entity is ever resolved.
 */
class OpdsFeedParser(
    private val parserFactory: () -> XmlPullParser = { Xml.newPullParser() }
) {

    fun parseFeed(input: InputStream, feedUrl: String): OpdsFeed {
        val parser = newParser(input)
        moveToRoot(parser)
        val rootName = localName(parser.name)
        when {
            rootName.equals("feed", ignoreCase = true) -> Unit
            rootName.equals("html", ignoreCase = true) ->
                throw OpdsFormatException("Сервер вернул веб-страницу вместо OPDS-каталога")
            else -> throw OpdsFormatException("Ответ сервера не является OPDS-каталогом")
        }

        var title = ""
        val links = mutableListOf<OpdsLink>()
        val entries = mutableListOf<OpdsEntry>()
        val rootDepth = parser.depth

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == rootDepth) break
            if (event != XmlPullParser.START_TAG) continue
            when (localName(parser.name)) {
                "title" -> title = readText(parser)
                "link" -> readLink(parser, feedUrl)?.let(links::add)
                "entry" -> readEntry(parser, feedUrl, entries.size)?.let(entries::add)
                else -> skip(parser)
            }
        }

        val searchLinks = links.filter { it.rel.equals("search", ignoreCase = true) }
        return OpdsFeed(
            title = title,
            url = feedUrl,
            entries = entries,
            nextUrl = links.firstOrNull { it.rel.equals("next", ignoreCase = true) }?.href,
            searchLink = searchLinks.firstOrNull { it.isSearchTemplate }
                ?: searchLinks.firstOrNull { it.isOpenSearchDescription }
                ?: searchLinks.firstOrNull(),
            startUrl = links.firstOrNull { it.rel.equals("start", ignoreCase = true) }?.href,
            upUrl = links.firstOrNull { it.rel.equals("up", ignoreCase = true) }?.href
        )
    }

    /** Returns the best `{searchTerms}` template of an OpenSearch description, or null. */
    fun parseOpenSearchTemplate(input: InputStream, descriptionUrl: String): String? {
        val parser = newParser(input)
        moveToRoot(parser)
        if (!localName(parser.name).equals("OpenSearchDescription", ignoreCase = true)) {
            throw OpdsFormatException("Ответ сервера не является описанием поиска OpenSearch")
        }
        val urls = mutableListOf<Pair<String?, String>>()
        val rootDepth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == rootDepth) break
            if (event != XmlPullParser.START_TAG) continue
            if (localName(parser.name).equals("Url", ignoreCase = true)) {
                val template = parser.getAttributeValue(null, "template")
                if (!template.isNullOrBlank()) {
                    urls += parser.getAttributeValue(null, "type") to
                        OpdsUrls.resolveTemplate(descriptionUrl, template.trim())
                }
            }
        }
        return OpenSearch.chooseTemplate(urls)
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val parser = parserFactory()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        // Real-world feeds contain HTML entities such as &nbsp; outside CDATA.
        runCatching { parser.setFeature(FEATURE_RELAXED, true) }
        parser.setInput(input, null)
        return parser
    }

    private fun moveToRoot(parser: XmlPullParser) {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG) {
            if (event == XmlPullParser.END_DOCUMENT) throw OpdsFormatException("Сервер вернул пустой ответ")
            event = parser.next()
        }
    }

    private fun readEntry(parser: XmlPullParser, baseUrl: String, position: Int): OpdsEntry? {
        val entryDepth = parser.depth
        var id = ""
        var title = ""
        val authors = mutableListOf<String>()
        var summary = ""
        var content = ""
        val links = mutableListOf<OpdsLink>()
        val categories = mutableListOf<String>()
        var language: String? = null
        var issued: String? = null
        var seriesName: String? = null
        var seriesIndex: Int? = null

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == entryDepth) break
            if (event != XmlPullParser.START_TAG) continue
            when (localName(parser.name).lowercase()) {
                "id" -> id = readText(parser)
                "title" -> title = readText(parser)
                "author" -> readAuthor(parser)?.let(authors::add)
                "summary" -> summary = readText(parser)
                "content" -> content = readText(parser)
                "link" -> readLink(parser, baseUrl)?.let(links::add)
                "category" -> {
                    val label = parser.getAttributeValue(null, "label")?.trim()
                        ?: parser.getAttributeValue(null, "term")?.trim()
                    if (!label.isNullOrEmpty()) categories += label
                    skip(parser)
                }
                "language" -> language = readText(parser).takeIf { it.isNotEmpty() }
                "issued", "published" -> {
                    val value = readText(parser)
                    if (issued == null && value.isNotEmpty()) issued = value
                }
                "series" -> {
                    val indexAttribute = parser.getAttributeValue(null, "index")
                        ?: parser.getAttributeValue(null, "position")
                    seriesIndex = indexAttribute?.trim()?.toDoubleOrNull()?.toInt() ?: seriesIndex
                    val name = readText(parser)
                    if (name.isNotEmpty()) seriesName = name
                }
                "series_index", "seriesindex" ->
                    seriesIndex = readText(parser).toDoubleOrNull()?.toInt() ?: seriesIndex
                else -> skip(parser)
            }
        }

        return buildEntry(
            RawEntry(
                id = id,
                title = title,
                authors = authors,
                summary = summary,
                content = content,
                links = links,
                categories = categories,
                language = language,
                issued = issued,
                seriesName = seriesName,
                seriesIndex = seriesIndex
            ),
            position
        )
    }

    private fun readAuthor(parser: XmlPullParser): String? {
        val depth = parser.depth
        var name: String? = null
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            if (localName(parser.name).equals("name", ignoreCase = true)) {
                name = readText(parser)
            } else {
                skip(parser)
            }
        }
        return name?.takeIf { it.isNotBlank() }
    }

    private fun readLink(parser: XmlPullParser, baseUrl: String): OpdsLink? {
        val href = parser.getAttributeValue(null, "href")?.trim()
        if (href.isNullOrEmpty()) return null
        val resolved = if (href.contains('{')) {
            // Search templates keep their {placeholders}.
            OpdsUrls.resolveTemplate(baseUrl, href)
        } else {
            OpdsUrls.resolve(baseUrl, href)
        }
        return OpdsLink(
            href = resolved,
            rel = parser.getAttributeValue(null, "rel")?.trim()?.takeIf { it.isNotEmpty() },
            type = parser.getAttributeValue(null, "type")?.trim()?.takeIf { it.isNotEmpty() },
            title = parser.getAttributeValue(null, "title")?.trim()?.takeIf { it.isNotEmpty() },
            length = parser.getAttributeValue(null, "length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        )
    }

    /**
     * Reads all text inside the current element, including text of nested
     * (X)HTML elements, and returns it as clean plain text. Leaves the parser on
     * the element's END_TAG.
     */
    private fun readText(parser: XmlPullParser): String {
        val type = parser.getAttributeValue(null, "type")?.lowercase().orEmpty()
        val depth = parser.depth
        val builder = StringBuilder()
        while (true) {
            val event = parser.next()
            when (event) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> builder.append(parser.text.orEmpty())
                XmlPullParser.ENTITY_REF -> builder.append(parser.text ?: "&${parser.name};")
                XmlPullParser.START_TAG -> if (localName(parser.name).lowercase() in BLOCK_TAGS) builder.append('\n')
                XmlPullParser.END_TAG -> {
                    if (parser.depth == depth) break
                    if (localName(parser.name).lowercase() in BLOCK_TAGS) builder.append('\n')
                }
            }
        }
        val raw = builder.toString()
        return if (type == "html" || type == "text/html" || TAG_LIKE.containsMatchIn(raw)) {
            OpdsText.htmlToPlain(raw)
        } else {
            OpdsText.normalize(OpdsText.decodeEntities(raw))
        }
    }

    /** Skips the current element with all its children. */
    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }

    private class RawEntry(
        val id: String,
        val title: String,
        val authors: List<String>,
        val summary: String,
        val content: String,
        val links: List<OpdsLink>,
        val categories: List<String>,
        val language: String?,
        val issued: String?,
        val seriesName: String?,
        val seriesIndex: Int?
    )

    private fun buildEntry(raw: RawEntry, position: Int): OpdsEntry? {
        val acquisitionLinks = raw.links.filter { OpdsFormats.isAcquisitionLink(it.rel, it.type) }
        val description = raw.summary.ifBlank { raw.content }
        val thumbnail = raw.links.firstOrNull { OpdsFormats.isThumbnailRel(it.rel) }?.href
        val cover = raw.links.firstOrNull { OpdsFormats.isCoverRel(it.rel) }?.href
        val title = raw.title.ifBlank { "Без названия" }

        if (acquisitionLinks.isNotEmpty()) {
            val acquisitions = mutableListOf<OpdsAcquisition>()
            val unsupported = mutableListOf<String>()
            for (link in acquisitionLinks) {
                val format = OpdsFormats.formatOf(link.type, link.href)
                if (format == null) {
                    unsupported += OpdsFormats.unsupportedLabel(link.type, link.href)
                } else if (acquisitions.none { it.format == format }) {
                    acquisitions += OpdsAcquisition(
                        url = link.href,
                        format = format,
                        mimeType = link.type,
                        sizeBytes = link.length
                    )
                }
            }
            val related = raw.links.filter { link ->
                isFeedLink(link) && link.rel?.lowercase() != "self" && !OpdsFormats.isAcquisitionLink(link.rel, link.type)
            }
            val series = raw.seriesName?.let { OpdsSeries(it, raw.seriesIndex) }
                ?: seriesFromText(raw.content)
                ?: seriesFromText(raw.summary)
                ?: seriesFromLinks(related)
            return OpdsEntry.Publication(
                key = raw.id.ifBlank { acquisitionLinks.first().href },
                title = title,
                authors = raw.authors.distinct(),
                summary = description,
                thumbnailUrl = thumbnail ?: cover,
                coverUrl = cover ?: thumbnail,
                series = series,
                acquisitions = acquisitions,
                relatedLinks = related.distinctBy { it.href },
                categories = raw.categories.distinct(),
                language = raw.language,
                issued = raw.issued,
                sizeBytes = OpdsFormats.preferred(acquisitions)?.sizeBytes
                    ?: sizeFromText(raw.content)
                    ?: acquisitions.firstNotNullOfOrNull { it.sizeBytes },
                unsupportedFormats = unsupported.distinct()
            )
        }

        val navigationLink = raw.links.firstOrNull { link ->
            isFeedLink(link) && (link.rel?.lowercase() ?: "") !in NON_NAVIGATION_RELS
        } ?: raw.links.firstOrNull { link ->
            link.type == null && (link.rel == null || link.rel.equals("alternate", true) || link.rel.equals("subsection", true))
        } ?: return null

        return OpdsEntry.Navigation(
            key = raw.id.ifBlank { navigationLink.href }.ifBlank { "nav_$position" },
            title = title,
            url = navigationLink.href,
            summary = description,
            thumbnailUrl = thumbnail ?: cover
        )
    }

    private fun isFeedLink(link: OpdsLink): Boolean {
        val type = link.type?.lowercase() ?: return false
        return type.contains("atom+xml") || type.contains("opds-catalog")
    }

    companion object {
        private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
        private val BLOCK_TAGS = setOf("p", "br", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6")
        private val NON_NAVIGATION_RELS = setOf("self", "search", "up", "start", "next", "previous", "prev")
        private val TAG_LIKE = Regex("<\\s*/?[a-zA-Z!]")

        // Kotlin adds UNICODE_CASE to IGNORE_CASE, so Cyrillic matches in any case
        // on the JVM (tests) and on Android alike.
        private val SERIES_IN_TEXT = Regex(
            "^\\s*(?:Серия|Series|Цикл)\\s*:\\s*(.+?)(?:\\s*[#№]\\s*(\\d+))?\\s*$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        private val SERIES_IN_LINK = Regex(
            "(?:книги\\s+серии|серия|series)\\s*[«\"']([^»\"']+)[»\"']",
            RegexOption.IGNORE_CASE
        )
        private val SIZE_IN_TEXT = Regex(
            "Размер\\s*:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(байт|bytes|кб|kb|мб|mb|b|k|m)(?![\\p{L}])",
            RegexOption.IGNORE_CASE
        )

        /** Strips an XML prefix: "dc:language" -> "language". */
        internal fun localName(name: String?): String = name.orEmpty().substringAfterLast(':')

        internal fun seriesFromText(text: String): OpdsSeries? {
            if (text.isBlank()) return null
            val match = SERIES_IN_TEXT.find(text) ?: return null
            val name = match.groupValues[1].trim().trim('«', '»', '"').trim()
            if (name.isEmpty()) return null
            return OpdsSeries(name, match.groupValues[2].toIntOrNull())
        }

        internal fun seriesFromLinks(links: List<OpdsLink>): OpdsSeries? {
            for (link in links) {
                val title = link.title ?: continue
                val match = SERIES_IN_LINK.find(title) ?: continue
                val name = match.groupValues[1].trim()
                if (name.isNotEmpty()) return OpdsSeries(name)
            }
            return null
        }

        internal fun sizeFromText(text: String): Long? {
            val match = SIZE_IN_TEXT.find(text) ?: return null
            val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val multiplier = when (match.groupValues[2].lowercase()) {
                "кб", "kb", "k" -> 1024.0
                "мб", "mb", "m" -> 1024.0 * 1024.0
                else -> 1.0
            }
            return (number * multiplier).toLong().takeIf { it > 0 }
        }
    }
}
