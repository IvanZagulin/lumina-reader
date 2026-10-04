package com.lumina.reader.core.opds

import com.lumina.reader.core.parser.common.MarkupToken
import com.lumina.reader.core.parser.common.MarkupTokenizer
import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.text.StringCharReader
import com.lumina.reader.core.text.charset.TextCharset

// Event types of the feed cursor (XmlPullParser's values).
private const val START_DOCUMENT = 0
private const val END_DOCUMENT = 1
private const val START_TAG = 2
private const val END_TAG = 3
private const val TEXT = 4

/**
 * Parser for OPDS 1.x (Atom) feeds and OpenSearch descriptions.
 *
 * It reads the shared forgiving [MarkupTokenizer] through [FeedCursor], which
 * reports the events and depths of the relaxed XmlPullParser this parser was
 * written against on Android: namespace prefixes are stripped by hand, so
 * feeds with undeclared prefixes still parse; DOCTYPE declarations are skipped
 * and no external entity is ever resolved; HTML entities such as &nbsp;
 * outside CDATA are accepted. A well-formed feed gives the same events as
 * there. Malformed markup differs on purpose: see [FeedCursor].
 *
 * The body is decoded like `XmlPullParser.setInput(stream, null)`: a
 * byte-order mark, else the encoding of the XML declaration, else UTF-8.
 * Two lenient edges: a declaration after leading whitespace still counts
 * (KXmlParser only read one at the very first byte), and an unknown encoding
 * name falls back to UTF-8 (KXmlParser failed with "Invalid stream or
 * encoding").
 */
class OpdsFeedParser {

    fun parseFeed(body: ByteArray, feedUrl: String): OpdsFeed {
        val parser = newParser(body)
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
            if (event == END_DOCUMENT) break
            if (event == END_TAG && parser.depth == rootDepth) break
            if (event != START_TAG) continue
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
    fun parseOpenSearchTemplate(body: ByteArray, descriptionUrl: String): String? {
        val parser = newParser(body)
        moveToRoot(parser)
        if (!localName(parser.name).equals("OpenSearchDescription", ignoreCase = true)) {
            throw OpdsFormatException("Ответ сервера не является описанием поиска OpenSearch")
        }
        val urls = mutableListOf<Pair<String?, String>>()
        val rootDepth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == END_DOCUMENT) break
            if (event == END_TAG && parser.depth == rootDepth) break
            if (event != START_TAG) continue
            if (localName(parser.name).equals("Url", ignoreCase = true)) {
                val template = parser.getAttributeValue("template")
                if (!template.isNullOrBlank()) {
                    urls += parser.getAttributeValue("type") to
                        OpdsUrls.resolveTemplate(descriptionUrl, template.trim())
                }
            }
        }
        return OpenSearch.chooseTemplate(urls)
    }

    private fun newParser(body: ByteArray): FeedCursor =
        FeedCursor(TextEncoding.decode(body, fallback = TextCharset.UTF_8))

    private fun moveToRoot(parser: FeedCursor) {
        var event = parser.eventType
        while (event != START_TAG) {
            if (event == END_DOCUMENT) throw OpdsFormatException("Сервер вернул пустой ответ")
            event = parser.next()
        }
    }

    private fun readEntry(parser: FeedCursor, baseUrl: String, position: Int): OpdsEntry? {
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
            if (event == END_DOCUMENT) break
            if (event == END_TAG && parser.depth == entryDepth) break
            if (event != START_TAG) continue
            when (localName(parser.name).lowercase()) {
                "id" -> id = readText(parser)
                "title" -> title = readText(parser)
                "author" -> readAuthor(parser)?.let(authors::add)
                "summary" -> summary = readText(parser)
                "content" -> content = readText(parser)
                "link" -> readLink(parser, baseUrl)?.let(links::add)
                "category" -> {
                    val label = parser.getAttributeValue("label")?.trim()
                        ?: parser.getAttributeValue("term")?.trim()
                    if (!label.isNullOrEmpty()) categories += label
                    skip(parser)
                }
                "language" -> language = readText(parser).takeIf { it.isNotEmpty() }
                "issued", "published" -> {
                    val value = readText(parser)
                    if (issued == null && value.isNotEmpty()) issued = value
                }
                "series" -> {
                    val indexAttribute = parser.getAttributeValue("index")
                        ?: parser.getAttributeValue("position")
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

    private fun readAuthor(parser: FeedCursor): String? {
        val depth = parser.depth
        var name: String? = null
        while (true) {
            val event = parser.next()
            if (event == END_DOCUMENT) break
            if (event == END_TAG && parser.depth == depth) break
            if (event != START_TAG) continue
            if (localName(parser.name).equals("name", ignoreCase = true)) {
                name = readText(parser)
            } else {
                skip(parser)
            }
        }
        return name?.takeIf { it.isNotBlank() }
    }

    private fun readLink(parser: FeedCursor, baseUrl: String): OpdsLink? {
        val href = parser.getAttributeValue("href")?.trim()
        if (href.isNullOrEmpty()) return null
        val resolved = if (href.contains('{')) {
            // Search templates keep their {placeholders}.
            OpdsUrls.resolveTemplate(baseUrl, href)
        } else {
            OpdsUrls.resolve(baseUrl, href)
        }
        return OpdsLink(
            href = resolved,
            rel = parser.getAttributeValue("rel")?.trim()?.takeIf { it.isNotEmpty() },
            type = parser.getAttributeValue("type")?.trim()?.takeIf { it.isNotEmpty() },
            title = parser.getAttributeValue("title")?.trim()?.takeIf { it.isNotEmpty() },
            length = parser.getAttributeValue("length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        )
    }

    /**
     * Reads all text inside the current element, including text of nested
     * (X)HTML elements, and returns it as clean plain text. Leaves the parser on
     * the element's END_TAG.
     */
    private fun readText(parser: FeedCursor): String {
        val type = parser.getAttributeValue("type")?.lowercase().orEmpty()
        val depth = parser.depth
        val builder = StringBuilder()
        while (true) {
            when (parser.next()) {
                END_DOCUMENT -> break
                TEXT -> builder.append(parser.text.orEmpty())
                START_TAG -> if (localName(parser.name).lowercase() in BLOCK_TAGS) builder.append('\n')
                END_TAG -> {
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
    private fun skip(parser: FeedCursor) {
        if (parser.eventType != START_TAG) return
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == END_DOCUMENT) return
            if (event == END_TAG && parser.depth == depth) return
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

    /**
     * The tokenizer seen as an XmlPullParser, as far as this parser uses one:
     * [next] reports START_TAG, END_TAG, TEXT or END_DOCUMENT; [depth] counts
     * the open elements the way XmlPullParser does (an element's START_TAG and
     * its END_TAG report the same depth); an empty element `<a/>` reports a
     * START_TAG followed by an END_TAG; [name] is the raw (prefixed) name.
     *
     * Unbalanced markup is repaired: an end tag closes the nearest open element
     * of that name (ignoring case), reporting an END_TAG for each element left
     * open inside it first, and an end tag that matches no open element is
     * ignored. The end of the input is END_DOCUMENT, whatever is still open.
     *
     * This is NOT what Android's relaxed KXmlParser did: there any end tag
     * closed the innermost open element whatever its name, so an XHTML `<br>`
     * without "/" inside a summary shifted every later depth by one, the
     * entry's remaining links were read as summary text and the next entry
     * was skipped as an unknown child. Only feeds that are not well-formed
     * XML see the difference; they now keep their entries.
     */
    private class FeedCursor(text: String) {
        private val tokenizer = MarkupTokenizer(StringCharReader(text))

        /** Raw names of the open elements, outermost first. */
        private val open = ArrayList<String>()

        /** END_TAGs still owed to elements an outer end tag closed implicitly. */
        private var pendingCloses = 0

        /** An END_TAG is owed to the empty element just reported. */
        private var closeEmptyElement = false

        private var attributes: List<Pair<String, String>> = emptyList()

        var eventType: Int = START_DOCUMENT
            private set
        var depth: Int = 0
            private set
        var name: String? = null
            private set
        var text: String? = null
            private set

        /** An attribute of the current START_TAG (names are compared in lower case, values are decoded). */
        fun getAttributeValue(attribute: String): String? {
            val key = attribute.lowercase()
            return attributes.firstOrNull { it.first == key }?.second
        }

        fun next(): Int {
            // The element of the END_TAG just reported is closed now.
            if (eventType == END_TAG) open.removeAt(open.lastIndex)
            when {
                closeEmptyElement -> {
                    closeEmptyElement = false
                    reportEndTag()
                }
                pendingCloses > 0 -> {
                    pendingCloses--
                    reportEndTag()
                }
                else -> readEvent()
            }
            return eventType
        }

        private fun reportEndTag() {
            eventType = END_TAG
            name = open.last()
            text = null
            attributes = emptyList()
            depth = open.size
        }

        private fun readEvent() {
            while (true) {
                when (val token = tokenizer.next()) {
                    null -> {
                        eventType = END_DOCUMENT
                        name = null
                        text = null
                        attributes = emptyList()
                        depth = open.size
                        return
                    }
                    is MarkupToken.StartTag -> {
                        open.add(token.rawName)
                        eventType = START_TAG
                        name = token.rawName
                        text = null
                        attributes = token.attributes
                        depth = open.size
                        closeEmptyElement = token.selfClosing
                        return
                    }
                    is MarkupToken.EndTag -> {
                        val index = open.indexOfLast { it.equals(token.rawName, ignoreCase = true) }
                        if (index < 0) continue
                        pendingCloses = open.lastIndex - index
                        reportEndTag()
                        return
                    }
                    is MarkupToken.Text -> {
                        if (token.text.isEmpty()) continue
                        eventType = TEXT
                        name = null
                        text = token.text
                        attributes = emptyList()
                        depth = open.size
                        return
                    }
                }
            }
        }
    }

    companion object {
        private val BLOCK_TAGS = setOf("p", "br", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6")
        private val NON_NAVIGATION_RELS = setOf("self", "search", "up", "start", "next", "previous", "prev")
        private val TAG_LIKE = Regex("<\\s*/?[a-zA-Z!]")

        // The keywords are matched in any case with explicit character classes
        // (see anyCase) instead of RegexOption.IGNORE_CASE: the JVM folds
        // Cyrillic case only because Kotlin adds UNICODE_CASE there, which
        // Kotlin/Native's regex does not promise.
        private val SERIES_IN_TEXT = Regex(
            "^\\s*(?:${anyCase("Серия")}|${anyCase("Series")}|${anyCase("Цикл")})" +
                "\\s*:\\s*(.+?)(?:\\s*[#№]\\s*(\\d+))?\\s*$",
            RegexOption.MULTILINE
        )
        private val SERIES_IN_LINK = Regex(
            "(?:${anyCase("книги")}\\s+${anyCase("серии")}|${anyCase("серия")}|${anyCase("series")})" +
                "\\s*[«\"']([^»\"']+)[»\"']"
        )

        /** "Размер: 1,5" and the spaces up to the unit; the unit is checked in [sizeFromText]. */
        private val SIZE_IN_TEXT = Regex(anyCase("Размер") + "\\s*:\\s*([0-9]+(?:[.,][0-9]+)?)\\s*")

        /** Size units in the order the original alternation tried them. */
        private val SIZE_UNITS = listOf("байт", "bytes", "кб", "kb", "мб", "mb", "b", "k", "m")

        /** "Серия" -> "[сС][еЕ][рР][иИ][яЯ]": [word] in any letter case. Letters only. */
        private fun anyCase(word: String): String = word.map { ch ->
            val lower = ch.lowercaseChar()
            val upper = ch.uppercaseChar()
            if (lower == upper) ch.toString() else "[$lower$upper]"
        }.joinToString("")

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

        /**
         * Size from a description such as "Размер: 1,5 Мб". The unit must not
         * run on into a word ("Размер: 5 Kbit" is no size); of the units that
         * fit, the first of [SIZE_UNITS] wins. This replaces a regex
         * alternation with a negative look-ahead for any Unicode letter, a
         * character class common code avoids; Char.isLetter is the same set.
         */
        internal fun sizeFromText(text: String): Long? {
            for (match in SIZE_IN_TEXT.findAll(text)) {
                val unitStart = match.range.last + 1
                val unit = SIZE_UNITS.firstOrNull { unit ->
                    text.startsWith(unit, startIndex = unitStart, ignoreCase = true) &&
                        text.getOrNull(unitStart + unit.length)?.isLetter() != true
                } ?: continue
                val number = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
                val multiplier = when (unit) {
                    "кб", "kb", "k" -> 1024.0
                    "мб", "mb", "m" -> 1024.0 * 1024.0
                    else -> 1.0
                }
                return (number * multiplier).toLong().takeIf { it > 0 }
            }
            return null
        }
    }
}
