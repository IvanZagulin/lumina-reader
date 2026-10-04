package com.lumina.reader.core.parser.epub

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import com.lumina.reader.core.parser.common.ImageSniffer
import com.lumina.reader.core.parser.common.NaturalOrderComparator
import com.lumina.reader.core.parser.common.NoteSupport
import com.lumina.reader.core.parser.common.ParserLimits
import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.parser.common.TextSupport
import com.lumina.reader.core.parser.common.withTempCopy
import okio.Path
import okio.Source
import okio.use

/**
 * EPUB 2/3 parser.
 *
 * - The archive is read lazily ([openEpubArchive]: only the documents and
 *   images that are used get inflated, each with a size cap); on Android,
 *   archives that ZipFile rejects fall back to a capped one-pass stream read.
 * - One chapter per spine document; a document longer than
 *   [ParserLimits.MAX_CHAPTER_CHARS] is split at paragraph boundaries into
 *   "<title> (часть N)" parts. `linear="no"` documents are skipped unless the
 *   TOC points at them (they are still read for footnotes the text links to).
 * - The TOC comes from the EPUB 3 nav document or the EPUB 2 NCX, keeps its
 *   nesting as [TocItem.level] and points at the paragraph of the target anchor.
 * - Chapter titles: TOC label of the document, else its opening heading(s),
 *   else `<title>` when it differs from the book title.
 */
class EpubParser internal constructor(
    /** The container reader; tests swap in the portable one to compare it with Android's. */
    private val openArchive: (Path) -> EpubArchive
) : BookParser {

    constructor() : this(::openEpubArchive)

    override fun parse(path: Path, fileName: String): ParsedBook = parseFile(path, fileName)

    override fun parse(source: Source, fileName: String): ParsedBook =
        withTempCopy(source, "lumina_epub_", ".epub") { temp -> parseFile(temp, fileName) }

    private fun parseFile(path: Path, displayName: String): ParsedBook =
        openArchive(path).use { parseArchive(it, displayName) }

    private class DocEntry(val path: String, val spineIndex: Int, var doc: XhtmlDocument)

    private class Placement(val chapterIndex: Int, val start: Int, val end: Int)

    private fun parseArchive(archive: EpubArchive, fileName: String): ParsedBook {
        val fallbackTitle = fileName.substringBeforeLast('.')

        // 1. Package document
        val opfPath = archive.read("META-INF/container.xml", ParserLimits.MAX_DOCUMENT_BYTES.toLong())
            ?.let { EpubPackageParser.findOpfPath(TextEncoding.decode(it)) }
            ?.let { archive.resolveName(it) }
            ?: archive.entryNames.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        val opf = opfPath
            ?.let { archive.read(it, ParserLimits.MAX_DOCUMENT_BYTES.toLong()) }
            ?.let { EpubPackageParser.parseOpf(TextEncoding.decode(it), opfPath) }

        val bookTitle = opf?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val manifest = opf?.manifest ?: emptyMap()

        // 2. Table of contents (EPUB 3 nav first, then NCX)
        val rawToc = readToc(archive, opf)
        val tocPaths = rawToc.map { canonical(archive, it.path) }.toHashSet()

        // 3. Images, loaded on demand with caps
        val images = LinkedHashMap<String, ByteArray>()
        val rejectedImages = HashSet<String>()
        var imageBytes = 0L
        fun loadImage(path: String): String? {
            val name = archive.resolveName(path) ?: return null
            if (images.containsKey(name)) return name
            if (name in rejectedImages) return null
            val size = archive.size(name)
            val bytes = if (size > ParserLimits.MAX_IMAGE_BYTES || imageBytes >= ParserLimits.MAX_TOTAL_IMAGE_BYTES) {
                null
            } else {
                archive.read(name, ParserLimits.MAX_IMAGE_BYTES.toLong())
            }
            if (bytes == null || !ImageSniffer.isRasterImage(bytes) ||
                imageBytes + bytes.size > ParserLimits.MAX_TOTAL_IMAGE_BYTES
            ) {
                rejectedImages.add(name)
                return null
            }
            images[name] = bytes
            imageBytes += bytes.size
            return name
        }

        // 4. Spine documents
        val spineItems: List<Pair<String, Boolean>> = run {
            val fromSpine = opf?.spine.orEmpty().mapNotNull { ref ->
                val item = manifest[ref.idref] ?: return@mapNotNull null
                if (!item.isHtml && !item.isImage) return@mapNotNull null
                canonical(archive, item.path) to ref.linear
            }
            fromSpine.ifEmpty {
                archive.entryNames
                    .filter { name -> name.substringAfterLast('.', "").lowercase() in setOf("xhtml", "html", "htm") }
                    .sortedWith(NaturalOrderComparator)
                    .map { it to true }
            }
        }
        val spineIndexOf = HashMap<String, Int>()
        spineItems.forEachIndexed { index, (path, _) -> if (!spineIndexOf.containsKey(path)) spineIndexOf[path] = index }

        val notes = EpubNotes()
        val docs = ArrayList<DocEntry>()
        val seen = HashSet<String>()
        val canonicalPath: (String) -> String = { canonical(archive, it) }
        spineItems.forEachIndexed { spineIndex, (path, linear) ->
            if (!seen.add(path)) return@forEachIndexed
            if (!linear && path !in tocPaths) {
                // Not part of the reading order, but it may hold the footnotes
                // the text links to (a common EPUB 3 layout): read it for them only.
                val prefix = "$path#"
                if (notes.referenced.keys.any { it.startsWith(prefix) }) {
                    readDocument(archive, path, notes, canonicalPath) { null }
                }
                return@forEachIndexed
            }
            val doc = readDocument(archive, path, notes, canonicalPath, ::loadImage) ?: return@forEachIndexed
            if (doc.paragraphs.isEmpty()) return@forEachIndexed
            docs.add(DocEntry(path, spineIndex, doc))
        }

        // 5. Footnotes: keep referenced bodies, then drop references without a body.
        val footnotes = LinkedHashMap<String, String>()
        for (key in notes.referenced.keys) {
            val body = notes.bodies[key] ?: continue
            footnotes[key] = body
        }
        for (key in footnotes.keys.toList()) {
            val cleaned = NoteSupport.dropUnknownRefs(footnotes.getValue(key)) { it in footnotes }
            footnotes[key] = NoteSupport.stripLeadingLabel(cleaned, notes.referenced[key].orEmpty())
        }
        for (entry in docs) {
            val doc = entry.doc
            if (doc.paragraphs.none { it.indexOf(ParagraphMarkup.NOTE_START) >= 0 }) continue
            entry.doc = XhtmlDocument(
                paragraphs = doc.paragraphs.map { p -> NoteSupport.dropUnknownRefs(p) { it in footnotes } },
                anchors = doc.anchors,
                headTitle = doc.headTitle,
                leadingHeadings = doc.leadingHeadings
            )
        }

        // 6. Chapters
        val chapters = ArrayList<Chapter>()
        val placements = HashMap<String, List<Placement>>()
        val anchorsByPath = HashMap<String, Map<String, Int>>()
        for (entry in docs) {
            val doc = entry.doc
            val tocLabel = tocLabelFor(entry.path, doc, rawToc, archive)
            val headingTitle = TextSupport.joinTitleParts(doc.leadingHeadings.map { ParagraphMarkup.plainText(doc.paragraphs[it]) })
            val headTitle = doc.headTitle.takeIf {
                it.isNotBlank() && !it.equals(bookTitle, ignoreCase = true) && !it.equals(fallbackTitle, ignoreCase = true)
            }
            val removed = HashSet<Int>()
            val title: String = when {
                tocLabel != null -> {
                    val label = TextSupport.normalizeForCompare(tocLabel)
                    val first = doc.leadingHeadings.firstOrNull()
                    when {
                        doc.leadingHeadings.isNotEmpty() && label == TextSupport.normalizeForCompare(headingTitle) ->
                            removed.addAll(doc.leadingHeadings)
                        first != null && label == TextSupport.normalizeForCompare(doc.paragraphs[first]) ->
                            removed.add(first)
                    }
                    tocLabel
                }
                headingTitle.isNotBlank() -> {
                    removed.addAll(doc.leadingHeadings)
                    headingTitle
                }
                headTitle != null -> headTitle
                doc.paragraphs.all { ParagraphMarkup.isImage(it) || it.isEmpty() } ->
                    if (chapters.isEmpty()) "Обложка" else "Иллюстрация"
                else -> "Глава ${chapters.size + 1}"
            }
            // Never leave a title-only page empty.
            if (removed.size >= doc.paragraphs.count { it.isNotEmpty() }) removed.clear()

            val paragraphs = ArrayList<String>(doc.paragraphs.size)
            val remap = IntArray(doc.paragraphs.size + 1)
            for (i in doc.paragraphs.indices) {
                remap[i] = paragraphs.size
                if (i !in removed) paragraphs.add(doc.paragraphs[i])
            }
            remap[doc.paragraphs.size] = paragraphs.size
            while (paragraphs.isNotEmpty() && paragraphs.first().isEmpty()) {
                paragraphs.removeAt(0)
                for (i in remap.indices) remap[i] = (remap[i] - 1).coerceAtLeast(0)
            }
            anchorsByPath[entry.path] = doc.anchors.mapValues { (_, index) ->
                remap[index.coerceIn(0, doc.paragraphs.size)].coerceAtMost((paragraphs.size - 1).coerceAtLeast(0))
            }

            val parts = splitLongChapter(paragraphs)
            val list = ArrayList<Placement>(parts.size)
            var start = 0
            parts.forEachIndexed { partIndex, part ->
                val chapterTitle = if (parts.size > 1) "$title (часть ${partIndex + 1})" else title
                list.add(Placement(chapters.size, start, start + part.size))
                chapters.add(Chapter(index = chapters.size, title = chapterTitle, href = entry.path, paragraphs = part))
                start += part.size
            }
            placements[entry.path] = list
        }

        if (chapters.isEmpty()) {
            val message = "Не удалось извлечь текст из книги"
            chapters.add(Chapter(index = 0, title = "Текст", paragraphs = listOf(message)))
        }

        // 7. TOC items
        val toc = ArrayList<TocItem>()
        val docOrder = docs.map { it.spineIndex to it.path }
        for (raw in rawToc) {
            val path = canonical(archive, raw.path)
            val direct = placements[path]
            val parts: List<Placement>
            val paragraph: Int
            if (direct != null) {
                parts = direct
                paragraph = raw.fragment?.let { anchorsByPath[path]?.get(it) } ?: 0
            } else {
                // Target document was skipped (empty): point at the next one in reading order.
                val spineIndex = spineIndexOf[path] ?: continue
                val next = docOrder.firstOrNull { it.first >= spineIndex } ?: continue
                parts = placements[next.second] ?: continue
                paragraph = 0
            }
            val part = parts.lastOrNull { paragraph >= it.start } ?: parts.first()
            toc.add(
                TocItem(
                    id = "toc_${toc.size}",
                    title = raw.label,
                    chapterIndex = part.chapterIndex,
                    level = raw.level,
                    paragraphIndex = (paragraph - part.start).coerceAtLeast(0)
                )
            )
        }
        if (toc.isEmpty()) {
            chapters.forEach { chapter -> toc.add(TocItem(id = "ch_${chapter.index}", title = chapter.title, chapterIndex = chapter.index)) }
        }

        // 8. Cover
        val coverBytes = findCover(archive, opf, images, docs)

        return ParsedBook(
            title = bookTitle,
            author = opf?.creators?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: "Неизвестный автор",
            description = opf?.description.orEmpty(),
            seriesName = opf?.seriesName.orEmpty(),
            seriesOrder = opf?.seriesOrder ?: 0,
            coverBytes = coverBytes,
            chapters = chapters,
            tableOfContents = toc,
            images = images,
            format = BookFormat.EPUB,
            footnotes = footnotes
        )
    }

    private fun canonical(archive: EpubArchive, path: String): String = archive.resolveName(path) ?: path

    private fun readToc(archive: EpubArchive, opf: OpfPackage?): List<RawTocEntry> {
        if (opf == null) return emptyList()
        val nav = opf.manifest.values.firstOrNull { item -> item.properties.split(' ').any { it == "nav" } }
        if (nav != null) {
            val bytes = archive.read(nav.path, ParserLimits.MAX_DOCUMENT_BYTES.toLong())
            if (bytes != null) {
                val entries = EpubPackageParser.parseNav(TextEncoding.decode(bytes), canonical(archive, nav.path))
                if (entries.isNotEmpty()) return entries
            }
        }
        val ncx = opf.ncxId?.let { opf.manifest[it] }
            ?: opf.manifest.values.firstOrNull { it.mediaType.contains("dtbncx", ignoreCase = true) }
            ?: opf.manifest.values.firstOrNull { it.path.endsWith(".ncx", ignoreCase = true) }
        if (ncx != null) {
            val bytes = archive.read(ncx.path, ParserLimits.MAX_DOCUMENT_BYTES.toLong())
            if (bytes != null) return EpubPackageParser.parseNcx(TextEncoding.decode(bytes), canonical(archive, ncx.path))
        }
        return emptyList()
    }

    private fun readDocument(
        archive: EpubArchive,
        path: String,
        notes: EpubNotes,
        canonicalPath: (String) -> String,
        loadImage: (String) -> String?
    ): XhtmlDocument? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) {
            val id = loadImage(path) ?: return null
            return XhtmlDocument(listOf("[IMG:$id]"), emptyMap(), "", emptyList())
        }
        val bytes = archive.read(path, ParserLimits.MAX_DOCUMENT_BYTES.toLong()) ?: return null
        val text = TextEncoding.decode(bytes)
        return XhtmlExtractor(
            docPath = path,
            notes = notes,
            canonicalPath = canonicalPath,
            resolveImage = loadImage
        ).extract(text)
    }

    /** Label of the TOC entry that opens [path] (no fragment, or an anchor before the body text). */
    private fun tocLabelFor(path: String, doc: XhtmlDocument, toc: List<RawTocEntry>, archive: EpubArchive): String? {
        val limit = doc.leadingHeadings.lastOrNull() ?: 0
        return toc.firstOrNull { entry ->
            canonical(archive, entry.path) == path &&
                (entry.fragment == null || (doc.anchors[entry.fragment] ?: 0) <= limit)
        }?.label
    }

    private fun splitLongChapter(paragraphs: List<String>): List<List<String>> {
        val total = paragraphs.sumOf { it.length.toLong() }
        if (total <= ParserLimits.MAX_CHAPTER_CHARS) return listOf(paragraphs)
        val partCount = ((total + ParserLimits.MAX_CHAPTER_CHARS - 1) / ParserLimits.MAX_CHAPTER_CHARS).toInt()
        val target = total / partCount
        val parts = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var size = 0L
        for (paragraph in paragraphs) {
            current.add(paragraph)
            size += paragraph.length
            if (size >= target && parts.size < partCount - 1) {
                parts.add(current)
                current = ArrayList()
                size = 0L
            }
        }
        if (current.isNotEmpty()) parts.add(current)
        return parts
    }

    private fun findCover(
        archive: EpubArchive,
        opf: OpfPackage?,
        images: Map<String, ByteArray>,
        docs: List<DocEntry>
    ): ByteArray? {
        val candidates = ArrayList<String>()
        if (opf != null) {
            val manifest = opf.manifest
            opf.coverId?.let { coverId ->
                val item = manifest[coverId] ?: manifest.values.firstOrNull { it.path.endsWith(coverId) }
                if (item != null && item.isImage) candidates.add(item.path)
            }
            manifest.values.filter { it.properties.split(' ').any { p -> p == "cover-image" } }
                .forEach { candidates.add(it.path) }
            manifest.values.filter {
                it.isImage && !it.mediaType.contains("svg", ignoreCase = true) &&
                    (it.id.contains("cover", ignoreCase = true) || it.path.substringAfterLast('/').contains("cover", ignoreCase = true))
            }.forEach { candidates.add(it.path) }
        }
        docs.firstOrNull()?.let { first ->
            val firstImage = first.doc.paragraphs.firstOrNull()?.let { ParagraphMarkup.imageId(it) }
            if (firstImage != null && (first.path.contains("cover", ignoreCase = true) || first.doc.paragraphs.size == 1)) {
                candidates.add(firstImage)
            }
        }
        for (candidate in candidates) {
            val name = archive.resolveName(candidate) ?: continue
            val bytes = images[name] ?: archive.read(name, ParserLimits.MAX_IMAGE_BYTES.toLong())
            if (ImageSniffer.isRasterImage(bytes)) return bytes
        }
        return null
    }
}
