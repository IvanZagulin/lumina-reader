package com.lumina.reader.core.parser.epub

import com.lumina.reader.core.parser.common.MarkupToken
import com.lumina.reader.core.parser.common.MarkupTokenizer
import com.lumina.reader.core.parser.common.TextSupport
import com.lumina.reader.core.text.StringCharReader

internal class ManifestItem(
    val id: String,
    /** Archive path (resolved against the OPF folder, percent-decoded). */
    val path: String,
    val mediaType: String,
    val properties: String
) {
    val isHtml: Boolean
        get() = mediaType.contains("html", ignoreCase = true) ||
            path.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm")

    val isImage: Boolean
        get() = mediaType.startsWith("image/", ignoreCase = true) ||
            path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
}

internal class SpineRef(val idref: String, val linear: Boolean)

internal class OpfPackage(
    val title: String,
    val creators: List<String>,
    val description: String,
    val coverId: String?,
    val seriesName: String,
    val seriesOrder: Int,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineRef>,
    val ncxId: String?
)

internal class RawTocEntry(val label: String, val path: String, val fragment: String?, val level: Int)

/** Parsers for container.xml, the OPF package and the two TOC formats. */
internal object EpubPackageParser {

    private fun tokens(text: String): Sequence<MarkupToken> {
        val tokenizer = MarkupTokenizer(StringCharReader(text))
        return generateSequence { tokenizer.next() }
    }

    fun findOpfPath(containerXml: String): String? {
        for (token in tokens(containerXml)) {
            if (token is MarkupToken.StartTag && token.name == "rootfile") {
                val path = token.attr("full-path")
                if (!path.isNullOrBlank()) return EpubPaths.normalize(EpubPaths.percentDecode(path.trim()))
            }
        }
        return null
    }

    fun parseOpf(text: String, opfPath: String): OpfPackage {
        var title = ""
        val creators = ArrayList<String>()
        var description = ""
        var coverId: String? = null
        var seriesName = ""
        var seriesOrder = 0
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<SpineRef>()
        var ncxId: String? = null

        var captureName: String? = null
        var captureKind = ""
        val capture = StringBuilder()
        var inMetadata = false

        for (token in tokens(text)) {
            when (token) {
                is MarkupToken.StartTag -> {
                    when (token.name) {
                        "metadata" -> inMetadata = true
                        "title", "creator", "description" -> if (inMetadata && captureName == null && !token.selfClosing) {
                            val role = token.attrByLocal("role")
                            val skipCreator = token.name == "creator" && role != null && !role.equals("aut", ignoreCase = true)
                            if (!skipCreator) {
                                captureName = token.name
                                captureKind = token.name
                                capture.setLength(0)
                            }
                        }
                        "meta" -> {
                            val name = token.attr("name")
                            val content = token.attr("content")
                            val property = token.attr("property")
                            when {
                                name.equals("cover", ignoreCase = true) && !content.isNullOrBlank() -> coverId = content.trim()
                                name.equals("calibre:series", ignoreCase = true) && content != null ->
                                    seriesName = content.trim()
                                name.equals("calibre:series_index", ignoreCase = true) && content != null ->
                                    seriesOrder = content.toSeriesOrder()
                                property.equals("belongs-to-collection", ignoreCase = true) && !token.selfClosing -> {
                                    captureName = "meta"
                                    captureKind = "series"
                                    capture.setLength(0)
                                }
                                property.equals("group-position", ignoreCase = true) && !token.selfClosing -> {
                                    captureName = "meta"
                                    captureKind = "position"
                                    capture.setLength(0)
                                }
                            }
                        }
                        "item" -> {
                            val id = token.attr("id").orEmpty()
                            val href = token.attr("href").orEmpty()
                            if (id.isNotBlank() && href.isNotBlank() && !EpubPaths.isExternal(href)) {
                                manifest[id] = ManifestItem(
                                    id = id,
                                    path = EpubPaths.resolve(opfPath, href).first,
                                    mediaType = token.attr("media-type").orEmpty(),
                                    properties = token.attr("properties").orEmpty()
                                )
                            }
                        }
                        "spine" -> ncxId = token.attr("toc")?.takeIf { it.isNotBlank() }
                        "itemref" -> {
                            val idref = token.attr("idref")
                            if (!idref.isNullOrBlank()) {
                                val linear = !token.attr("linear").equals("no", ignoreCase = true)
                                spine.add(SpineRef(idref, linear))
                            }
                        }
                    }
                }
                is MarkupToken.EndTag -> {
                    if (token.name == "metadata") inMetadata = false
                    if (captureName != null && token.name == captureName) {
                        val value = TextSupport.collapse(capture.toString())
                        when (captureKind) {
                            "title" -> if (title.isEmpty()) title = value
                            "creator" -> if (value.isNotEmpty() && creators.none { it.equals(value, ignoreCase = true) }) creators.add(value)
                            "description" -> if (description.isEmpty()) description = cleanDescription(capture.toString())
                            "series" -> if (value.isNotEmpty()) seriesName = value
                            "position" -> seriesOrder = value.toSeriesOrder()
                        }
                        captureName = null
                    }
                }
                is MarkupToken.Text -> if (captureName != null) capture.append(token.text)
            }
        }
        return OpfPackage(title, creators, description, coverId, seriesName, seriesOrder, manifest, spine, ncxId)
    }

    /** Descriptions often carry escaped HTML; keep the text, one line per block. */
    private fun cleanDescription(raw: String): String {
        val withBreaks = raw.replace(Regex("(?i)<\\s*(br|/p|/div|/li)[^>]*>"), "\n")
        val noTags = withBreaks.replace(Regex("<[^>]*>"), " ")
        return noTags.split('\n').map { TextSupport.collapse(it) }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun String.toSeriesOrder(): Int =
        trim().toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0

    /** EPUB 3 navigation document: the `<nav epub:type="toc">` list (or the first nav with links). */
    fun parseNav(text: String, navPath: String): List<RawTocEntry> {
        class NavList(val type: String) {
            val entries = ArrayList<RawTocEntry>()
        }
        val navs = ArrayList<NavList>()
        var current: NavList? = null
        var navDepth = 0
        var olDepth = 0
        var linkHref: String? = null
        val label = StringBuilder()

        for (token in tokens(text)) {
            when (token) {
                is MarkupToken.StartTag -> when (token.name) {
                    "nav" -> if (!token.selfClosing) {
                        navDepth++
                        if (navDepth == 1) {
                            val type = (token.attr("epub:type") ?: token.attrByLocal("type")).orEmpty().lowercase()
                            current = NavList(type).also { navs.add(it) }
                            olDepth = 0
                        }
                    }
                    "ol", "ul" -> if (current != null && !token.selfClosing) olDepth++
                    "a" -> if (current != null && !token.selfClosing) {
                        linkHref = token.attr("href")
                        label.setLength(0)
                    }
                }
                is MarkupToken.EndTag -> when (token.name) {
                    "nav" -> if (navDepth > 0) {
                        navDepth--
                        if (navDepth == 0) current = null
                    }
                    "ol", "ul" -> if (current != null && olDepth > 0) olDepth--
                    "a" -> {
                        val href = linkHref
                        val nav = current
                        if (href != null && nav != null && !EpubPaths.isExternal(href)) {
                            val title = TextSupport.collapse(label.toString())
                            if (title.isNotEmpty()) {
                                val (path, fragment) = EpubPaths.resolve(navPath, href)
                                nav.entries.add(RawTocEntry(title, path, fragment, (olDepth - 1).coerceAtLeast(0)))
                            }
                        }
                        linkHref = null
                    }
                }
                is MarkupToken.Text -> if (linkHref != null) label.append(token.text)
            }
        }
        val toc = navs.firstOrNull { it.type.contains("toc") && it.entries.isNotEmpty() }
            ?: navs.firstOrNull { it.type.isEmpty() && it.entries.isNotEmpty() }
        return toc?.entries ?: emptyList()
    }

    /** EPUB 2 NCX: nested navPoints of the navMap. */
    fun parseNcx(text: String, ncxPath: String): List<RawTocEntry> {
        class Point {
            val label = StringBuilder()
            var src: String? = null
            var emitted = false
        }
        val entries = ArrayList<RawTocEntry>()
        val points = ArrayList<Point>()
        var inNavMap = false
        var inText = false

        fun emit(point: Point, level: Int) {
            if (point.emitted) return
            point.emitted = true
            val src = point.src ?: return
            val title = TextSupport.collapse(point.label.toString())
            if (title.isEmpty() || EpubPaths.isExternal(src)) return
            val (path, fragment) = EpubPaths.resolve(ncxPath, src)
            entries.add(RawTocEntry(title, path, fragment, level))
        }

        for (token in tokens(text)) {
            when (token) {
                is MarkupToken.StartTag -> when (token.name) {
                    "navmap" -> inNavMap = true
                    "navpoint" -> if (inNavMap && !token.selfClosing) {
                        points.lastOrNull()?.let { emit(it, points.size - 1) }
                        points.add(Point())
                    }
                    "text" -> if (points.isNotEmpty() && !token.selfClosing) inText = true
                    "content" -> points.lastOrNull()?.let { point ->
                        if (point.src == null) point.src = token.attr("src")
                    }
                }
                is MarkupToken.EndTag -> when (token.name) {
                    "navmap" -> inNavMap = false
                    "text" -> inText = false
                    "navpoint" -> if (points.isNotEmpty()) {
                        emit(points.last(), points.size - 1)
                        points.removeAt(points.lastIndex)
                    }
                }
                is MarkupToken.Text -> if (inText) points.lastOrNull()?.label?.append(token.text)
            }
        }
        return entries
    }
}
