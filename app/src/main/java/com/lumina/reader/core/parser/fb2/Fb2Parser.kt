package com.lumina.reader.core.parser.fb2

import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.TocItem
import com.lumina.reader.core.parser.BookParser
import com.lumina.reader.core.parser.common.Base64StreamDecoder
import com.lumina.reader.core.parser.common.MarkupToken
import com.lumina.reader.core.parser.common.MarkupTokenizer
import com.lumina.reader.core.parser.common.NoteSupport
import com.lumina.reader.core.parser.common.ParagraphAccumulator
import com.lumina.reader.core.parser.common.ParserLimits
import com.lumina.reader.core.parser.common.TextEncoding
import com.lumina.reader.core.parser.common.TextSupport
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal fun fb2ChapterTitle(explicitTitle: String, chapterIndex: Int): String =
    explicitTitle.ifBlank { "Глава ${chapterIndex + 1}" }

/**
 * FictionBook 2 parser (plain `.fb2` and zipped `.fb2.zip`).
 *
 * The XML is read in a streaming fashion with a forgiving tokenizer: HTML named
 * entities are understood, malformed markup does not abort parsing, and a file
 * that ends unexpectedly keeps everything read so far plus a final note that
 * the file is damaged. Encoding: BOM, then the XML declaration, else UTF-8 when
 * the bytes are valid UTF-8, else windows-1251.
 *
 * Chapters follow the `<section>` structure: a section with its own text is a
 * chapter; a titled section nested in a chapter that already has text becomes
 * a HEADING inside it (with its own TOC entry); sections that only group
 * others (e.g. "Часть 1" with an epigraph) contribute TOC entries one level up.
 * The notes body (`<body name="notes">`) fills [ParsedBook.footnotes].
 */
class Fb2Parser : BookParser {

    override fun parse(file: File): ParsedBook = parseFile(file, file.name)

    override fun parse(inputStream: InputStream, fileName: String): ParsedBook {
        val temp = File.createTempFile("lumina_fb2_", ".tmp")
        try {
            inputStream.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } }
            return parseFile(temp, fileName)
        } finally {
            temp.delete()
        }
    }

    private fun parseFile(file: File, displayName: String): ParsedBook {
        val isZip = isZipArchive(file) ||
            displayName.lowercase().let { it.endsWith(".zip") || it.endsWith(".fb2_zip") }
        return try {
            if (isZip) {
                ZipFile(file).use { zip ->
                    val entry = findFb2Entry(zip)
                        ?: return createEmptyBook(displayName, true, "В архиве не найден файл FB2")
                    parseSource({ zip.getInputStream(entry) }, displayName, true)
                }
            } else {
                parseSource({ FileInputStream(file) }, displayName, false)
            }
        } catch (e: Exception) {
            createEmptyBook(displayName, isZip, "Не удалось прочитать файл: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun isZipArchive(file: File): Boolean {
        val head = ByteArray(4)
        val read = try {
            FileInputStream(file).use { it.read(head) }
        } catch (e: Exception) {
            -1
        }
        return read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte()
    }

    private fun findFb2Entry(zip: ZipFile): ZipEntry? {
        var xmlEntry: ZipEntry? = null
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name.lowercase()
            if (name.endsWith(".fb2")) return entry
            if (xmlEntry == null && name.endsWith(".xml")) xmlEntry = entry
        }
        return xmlEntry
    }

    private fun parseSource(open: () -> InputStream, fileName: String, isZip: Boolean): ParsedBook {
        val charset = TextEncoding.detect(open)
        val builder = Fb2BookBuilder(fileName, isZip)
        return open().use { raw ->
            val reader = InputStreamReader(BufferedInputStream(raw, 64 * 1024), charset)
            val tokenizer = MarkupTokenizer(reader)
            var failed = false
            try {
                while (true) {
                    val token = tokenizer.next() ?: break
                    builder.accept(token)
                }
            } catch (e: Exception) {
                failed = true
            }
            builder.build(damaged = failed || tokenizer.truncated)
        }
    }

    companion object {
        internal const val DAMAGED_NOTE = "Файл книги повреждён: дальше этого места текст прочитать не удалось."

        internal fun createEmptyBook(
            fileName: String,
            isZip: Boolean,
            message: String = "Пустой или неподдерживаемый файл"
        ): ParsedBook {
            val chapter = Chapter(index = 0, title = "Ошибка", paragraphs = listOf(message))
            return ParsedBook(
                title = fileName.substringBeforeLast("."),
                author = "Неизвестный автор",
                chapters = listOf(chapter),
                tableOfContents = listOf(TocItem(id = "err_0", title = "Ошибка", chapterIndex = 0)),
                format = if (isZip) BookFormat.FB2_ZIP else BookFormat.FB2
            )
        }
    }
}

/** Streaming state machine that turns FB2 tokens into a [ParsedBook]. */
internal class Fb2BookBuilder(private val fileName: String, private val isZip: Boolean) {

    private class Author {
        var first = ""
        var middle = ""
        var last = ""
        var nick = ""

        fun display(): String =
            listOf(last, first, middle).filter { it.isNotBlank() }.joinToString(" ").ifBlank { nick }
    }

    private class Section(val depth: Int) {
        var title: String? = null
        /** null until known: true when the section continues the open chapter as a sub-heading. */
        var inline: Boolean? = null
        var hasDirectText = false
    }

    private class NoteSection(val id: String?) {
        val parts = ArrayList<String>()
        var title: String? = null
    }

    private class ChapterBuilder(val title: String, val owner: Section?) {
        val paragraphs = ArrayList<String>()
        var chars = 0
    }

    private enum class TitleContext { BODY, SECTION, NOTE }

    // metadata
    private var bookTitle = ""
    private val authors = ArrayList<String>()
    private var author: Author? = null
    private val annotation = ArrayList<String>()
    private var seriesName = ""
    private var seriesOrder = 0
    private var coverImageId = ""
    private var coverBytes: ByteArray? = null
    private val images = LinkedHashMap<String, ByteArray>()
    private var imageBytes = 0L

    // structure
    private val stack = ArrayList<String>()
    private var leaf: StringBuilder? = null
    private var leafName = ""
    private var inBody = false
    private var notesBody = false
    private var bodyTitle: String? = null
    private val sections = ArrayList<Section>()
    private val noteSections = ArrayList<NoteSection>()
    private val chapters = ArrayList<Chapter>()
    private var current: ChapterBuilder? = null
    private val toc = ArrayList<TocItem>()
    private val pendingToc = ArrayList<Pair<String, Int>>()
    private val footnotes = LinkedHashMap<String, String>()
    private val noteLabels = HashMap<String, String>()
    private var rootClosed = false

    // text
    private val acc = ParagraphAccumulator()
    private val textBlocks = ArrayList<Pair<String, BlockStyle>>()
    private var titleParts: ArrayList<String>? = null
    private var titleContext = TitleContext.SECTION
    private var titleDepth = -1
    private val stanzaCounts = ArrayList<Int>()
    private var noteLabel: StringBuilder? = null
    private var noteTarget = ""
    private var noteExplicit = false
    private var binary: Base64StreamDecoder? = null
    private var binaryId = ""

    fun accept(token: MarkupToken) {
        when (token) {
            is MarkupToken.StartTag -> onStart(token)
            is MarkupToken.EndTag -> onEnd(token.name)
            is MarkupToken.Text -> onText(token.text)
        }
    }

    private fun inside(name: String): Boolean = stack.contains(name)

    // ---- start tags ------------------------------------------------------

    private fun onStart(tag: MarkupToken.StartTag) {
        val name = tag.name
        val parent = stack.lastOrNull()
        when (name) {
            "author" -> if (parent == "title-info") author = Author()
            "first-name", "middle-name", "last-name", "nickname" -> if (author != null && parent == "author") startLeaf(name)
            "book-title" -> if (parent == "title-info") startLeaf(name)
            "sequence" -> if (parent == "title-info" && seriesName.isBlank()) {
                seriesName = tag.attr("name")?.trim().orEmpty()
                seriesOrder = tag.attr("number")?.trim()?.toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
            }
            "image" -> onImage(tag)
            "body" -> {
                inBody = true
                val bodyName = tag.attr("name")?.lowercase().orEmpty()
                notesBody = bodyName.contains("note") || bodyName.contains("comment")
                bodyTitle = null
            }
            "section" -> if (inBody) startSection(tag)
            "title" -> if (inBody) startTitle(parent)
            "p" -> startTextBlock(name, paragraphStyle())
            "v" -> startTextBlock(name, BlockStyle.VERSE)
            "subtitle" -> startTextBlock(name, BlockStyle.SUBTITLE)
            "text-author" -> startTextBlock(name, BlockStyle.TEXT_AUTHOR)
            "date" -> if (inBody && inside("poem")) startTextBlock(name, BlockStyle.TEXT_AUTHOR)
            "tr" -> if (inBody) startTextBlock(name, BlockStyle.NORMAL)
            "td", "th" -> if (acc.hasContent) acc.appendSeparator(" | ")
            "poem" -> stanzaCounts.add(0)
            "stanza" -> if (stanzaCounts.isNotEmpty()) {
                val last = stanzaCounts.lastIndex
                if (stanzaCounts[last] > 0) emitGap()
                stanzaCounts[last] = stanzaCounts[last] + 1
            }
            "empty-line" -> if (inBody && titleParts == null) {
                flushInline()
                emitGap()
            }
            "emphasis" -> acc.beginEmphasis()
            "strong" -> acc.beginStrong()
            "a" -> startLink(tag)
            "binary" -> {
                binaryId = tag.attr("id").orEmpty()
                binary = Base64StreamDecoder(ParserLimits.MAX_IMAGE_BYTES)
            }
        }
        if (!tag.selfClosing) {
            stack.add(name)
        } else {
            // A self-closing element ends immediately.
            when (name) {
                "emphasis" -> acc.endEmphasis()
                "strong" -> acc.endStrong()
                "binary" -> binary = null
                "a" -> finishLink()
                "section" -> if (inBody) endSection()
                "p", "v", "subtitle", "text-author", "date", "tr" ->
                    if (textBlocks.isNotEmpty() && textBlocks.last().first == name) endTextBlock()
                "poem" -> if (stanzaCounts.isNotEmpty()) stanzaCounts.removeAt(stanzaCounts.lastIndex)
                "title" -> if (titleParts != null && titleDepth == stack.size) endTitle()
            }
        }
    }

    private fun startLeaf(name: String) {
        leaf = StringBuilder()
        leafName = name
    }

    private fun paragraphStyle(): BlockStyle = when {
        titleParts == null && inside("title") -> BlockStyle.SUBTITLE // poem / stanza titles
        inside("epigraph") || inside("cite") -> BlockStyle.EPIGRAPH
        inBody && inside("annotation") -> BlockStyle.EPIGRAPH
        else -> BlockStyle.NORMAL
    }

    private fun startTextBlock(name: String, style: BlockStyle) {
        flushInline()
        textBlocks.add(name to style)
    }

    private fun startTitle(parent: String?) {
        when {
            notesBody && parent == "section" -> {
                titleContext = TitleContext.NOTE
                titleParts = ArrayList()
            }
            parent == "section" -> {
                titleContext = TitleContext.SECTION
                titleParts = ArrayList()
            }
            parent == "body" -> {
                titleContext = TitleContext.BODY
                titleParts = ArrayList()
            }
            // poem/stanza titles stay null: their <p> become SUBTITLE paragraphs
        }
        if (titleParts != null) titleDepth = stack.size
    }

    private fun startLink(tag: MarkupToken.StartTag) {
        val href = tag.hrefAttr()?.trim() ?: return
        if (!href.startsWith("#") || href.length < 2) return
        noteLabel = StringBuilder()
        noteTarget = href.substring(1)
        noteExplicit = tag.attr("type")?.equals("note", ignoreCase = true) == true
    }

    private fun onImage(tag: MarkupToken.StartTag) {
        val id = tag.hrefAttr()?.trim()?.removePrefix("#").orEmpty()
        if (id.isEmpty()) return
        if (inside("coverpage")) {
            if (coverImageId.isEmpty()) coverImageId = id
            return
        }
        if (!inBody || notesBody || titleParts != null) return
        flushInline()
        emitRaw("[IMG:$id]", direct = false)
    }

    // ---- end tags --------------------------------------------------------

    private fun onEnd(name: String) {
        val index = stack.lastIndexOf(name)
        if (index < 0) return
        while (stack.size > index) {
            val closing = stack.removeAt(stack.lastIndex)
            onElementEnd(closing)
        }
    }

    private fun onElementEnd(name: String) {
        val leafText = if (leaf != null && leafName == name) {
            val text = TextSupport.collapse(leaf.toString())
            leaf = null
            text
        } else {
            null
        }
        when (name) {
            "first-name" -> if (leafText != null) author?.first = leafText
            "middle-name" -> if (leafText != null) author?.middle = leafText
            "last-name" -> if (leafText != null) author?.last = leafText
            "nickname" -> if (leafText != null) author?.nick = leafText
            "book-title" -> if (leafText != null && bookTitle.isEmpty()) bookTitle = leafText
            "author" -> {
                val display = author?.display().orEmpty()
                if (display.isNotBlank() && authors.none { it.equals(display, ignoreCase = true) }) authors.add(display)
                author = null
            }
            "p", "v", "subtitle", "text-author", "date", "tr" ->
                if (textBlocks.isNotEmpty() && textBlocks.last().first == name) endTextBlock()
            "title" -> if (titleParts != null && titleDepth == stack.size) endTitle()
            "section" -> if (inBody) endSection()
            "poem" -> {
                if (stanzaCounts.isNotEmpty()) stanzaCounts.removeAt(stanzaCounts.lastIndex)
                emitGap()
            }
            "emphasis" -> acc.endEmphasis()
            "strong" -> acc.endStrong()
            "a" -> finishLink()
            "body" -> endBody()
            "binary" -> endBinary()
            "fictionbook" -> rootClosed = true
        }
    }

    private fun endTextBlock() {
        val (_, style) = textBlocks.removeAt(textBlocks.lastIndex)
        val content = acc.take() ?: return
        val parts = titleParts
        when {
            parts != null -> parts.add(ParagraphMarkup.plainText(content))
            !inBody && inside("annotation") -> {
                if (inside("title-info")) annotation.add(ParagraphMarkup.plainText(content))
            }
            inBody -> emit(style, content)
        }
    }

    private fun endTitle() {
        val parts = titleParts ?: return
        titleParts = null
        titleDepth = -1
        val title = TextSupport.joinTitleParts(parts)
        if (title.isEmpty()) return
        when (titleContext) {
            TitleContext.NOTE -> noteSections.lastOrNull()?.title = title
            TitleContext.BODY -> if (!notesBody) bodyTitle = title
            TitleContext.SECTION -> sections.lastOrNull()?.let { onSectionTitle(it, title) }
        }
    }

    private fun finishLink() {
        val label = noteLabel ?: return
        noteLabel = null
        val raw = label.toString()
        val trimmed = TextSupport.collapse(raw)
        val isNote = trimmed.isNotEmpty() &&
            ((noteExplicit && trimmed.length <= 24) || NoteSupport.looksLikeNoteLabel(trimmed))
        if (isNote) {
            val clean = NoteSupport.cleanLabel(trimmed)
            acc.appendNoteRef(clean, noteTarget)
            noteLabels.putIfAbsent(noteTarget, clean)
        } else {
            acc.appendText(raw)
        }
    }

    private fun endBinary() {
        val decoder = binary ?: return
        binary = null
        val bytes = decoder.result() ?: return
        val id = binaryId
        if (id.isEmpty()) return
        val isCover = if (coverImageId.isNotEmpty()) {
            id.equals(coverImageId, ignoreCase = true)
        } else {
            coverBytes == null && id.contains("cover", ignoreCase = true)
        }
        if (isCover) coverBytes = bytes
        if (imageBytes + bytes.size <= ParserLimits.MAX_TOTAL_IMAGE_BYTES) {
            images[id] = bytes
            imageBytes += bytes.size
        }
    }

    // ---- text ------------------------------------------------------------

    private fun onText(text: String) {
        leaf?.let {
            it.append(text)
            return
        }
        binary?.let {
            it.feed(text)
            return
        }
        noteLabel?.let {
            it.append(text)
            return
        }
        if (textBlocks.isNotEmpty()) acc.appendText(text)
    }

    /** Emits text collected so far inside the current text block (before an inline image etc.). */
    private fun flushInline() {
        if (!acc.hasContent) return
        val style = textBlocks.lastOrNull()?.second ?: BlockStyle.NORMAL
        val content = acc.take() ?: return
        val parts = titleParts
        if (parts != null) {
            parts.add(ParagraphMarkup.plainText(content))
        } else if (inBody) {
            emit(style, content)
        }
    }

    // ---- sections and chapters -----------------------------------------

    private fun startSection(tag: MarkupToken.StartTag) {
        if (notesBody) {
            noteSections.add(NoteSection(tag.attr("id")?.trim()?.takeIf { it.isNotEmpty() }))
            return
        }
        sections.lastOrNull()?.let { parent -> if (parent.inline == null) decide(parent, titled = false) }
        if (current?.owner == null) closeChapter() // body-level front matter ends here
        sections.add(Section(sections.size + 1))
    }

    private fun endSection() {
        if (notesBody) {
            if (noteSections.isEmpty()) return
            val note = noteSections.removeAt(noteSections.lastIndex)
            val id = note.id ?: return
            val text = note.parts.joinToString("\n").trim().ifEmpty { note.title.orEmpty() }
            if (text.isNotEmpty()) footnotes[id] = text
            return
        }
        if (sections.isEmpty()) return
        val section = sections.removeAt(sections.lastIndex)
        if (current?.owner === section) closeChapter()
    }

    /**
     * Decides whether [section] continues the open chapter (sub-heading) or
     * starts its own. Untitled sections inside a chapter with text are scene
     * breaks; titled ones become headings while the chapter is not too long.
     */
    private fun decide(section: Section, titled: Boolean) {
        val chapter = current
        val owner = chapter?.owner
        val inline = chapter != null && owner != null && owner.hasDirectText &&
            chapter.chars < INLINE_SECTION_LIMIT
        section.inline = inline
        if (!inline) {
            closeChapter()
        } else if (!titled) {
            emitGap()
        }
    }

    private fun onSectionTitle(section: Section, title: String) {
        if (section.inline == null) decide(section, titled = true)
        section.title = title
        val level = section.depth - 1
        val chapter = current
        if (section.inline == true && chapter != null) {
            toc.add(
                TocItem(
                    id = "toc_${toc.size}",
                    title = title,
                    chapterIndex = chapters.size,
                    level = level,
                    paragraphIndex = chapter.paragraphs.size
                )
            )
            chapter.paragraphs.add(ParagraphMarkup.block(BlockStyle.HEADING, title))
            chapter.chars += title.length
        } else {
            pendingToc.add(title to level)
        }
    }

    private fun ensureChapter(): ChapterBuilder {
        sections.lastOrNull()?.let { if (it.inline == null) decide(it, titled = false) }
        current?.let { return it }
        val owner = sections.lastOrNull { it.inline != true }
        // An untitled section right below a titled one ("Часть 1" > untitled text) takes that title.
        val inheritedTitle = pendingToc.lastOrNull()?.first
        val title = if (owner == null) {
            bodyTitle ?: bookTitle.ifBlank { "Начало" }
        } else {
            owner.title ?: inheritedTitle ?: fb2ChapterTitle("", chapters.size)
        }
        val needsOwnTocEntry = owner?.title == null && pendingToc.isEmpty()
        val chapter = ChapterBuilder(title, owner)
        current = chapter
        val index = chapters.size
        for ((pendingTitle, level) in pendingToc) {
            toc.add(TocItem(id = "toc_${toc.size}", title = pendingTitle, chapterIndex = index, level = level))
        }
        pendingToc.clear()
        if (needsOwnTocEntry) {
            toc.add(TocItem(id = "toc_${toc.size}", title = title, chapterIndex = index, level = ((owner?.depth ?: 1) - 1)))
        }
        return chapter
    }

    private fun closeChapter() {
        val chapter = current ?: return
        current = null
        val paragraphs = chapter.paragraphs
        while (paragraphs.isNotEmpty() && paragraphs.last().isEmpty()) paragraphs.removeAt(paragraphs.lastIndex)
        if (paragraphs.isEmpty()) return
        chapters.add(Chapter(index = chapters.size, title = chapter.title, paragraphs = ArrayList(paragraphs)))
    }

    private fun emit(style: BlockStyle, content: String) {
        val plain = ParagraphMarkup.plainText(content).trim()
        if (plain.isEmpty()) return
        val isBreak = TextSupport.isSceneBreak(plain)
        val raw = if (isBreak) ParagraphMarkup.sceneBreak() else ParagraphMarkup.block(style, content)
        if (notesBody) {
            noteSections.lastOrNull()?.parts?.add(if (isBreak) ParagraphMarkup.SCENE_BREAK_TEXT else content)
            return
        }
        val direct = style != BlockStyle.EPIGRAPH && style != BlockStyle.TEXT_AUTHOR
        emitRaw(raw, direct)
    }

    private fun emitRaw(raw: String, direct: Boolean) {
        if (notesBody || !inBody) return
        val chapter = ensureChapter()
        chapter.paragraphs.add(raw)
        chapter.chars += raw.length
        if (direct) {
            val innermost = sections.lastOrNull()
            if (innermost != null && innermost === chapter.owner) innermost.hasDirectText = true
        }
    }

    private fun emitGap() {
        if (!inBody || notesBody) return
        val chapter = current ?: return
        if (chapter.paragraphs.isEmpty() || chapter.paragraphs.last().isEmpty()) return
        chapter.paragraphs.add("")
    }

    private fun endBody() {
        flushInline()
        textBlocks.clear()
        closeChapter()
        pendingToc.clear()
        sections.clear()
        noteSections.clear()
        inBody = false
        notesBody = false
    }

    // ---- result ------------------------------------------------------------

    fun build(damaged: Boolean): ParsedBook {
        if (inBody) endBody()
        val isDamaged = damaged || !rootClosed
        if (chapters.isEmpty()) {
            val message = if (isDamaged) {
                "Текст не найден. Возможно, файл повреждён."
            } else {
                "Текст не найден. Возможно, файл повреждён или пуст."
            }
            return Fb2Parser.createEmptyBook(fileName, isZip, message)
        }
        if (isDamaged) {
            val last = chapters.last()
            chapters[chapters.lastIndex] = last.copy(
                paragraphs = last.paragraphs + ParagraphMarkup.block(BlockStyle.SUBTITLE, Fb2Parser.DAMAGED_NOTE)
            )
        }

        // Footnotes: strip repeated labels, then turn references without a note into plain text.
        val notes = LinkedHashMap<String, String>()
        for ((id, text) in footnotes) {
            notes[id] = NoteSupport.stripLeadingLabel(text, noteLabels[id].orEmpty())
        }
        for (id in notes.keys.toList()) {
            notes[id] = NoteSupport.dropUnknownRefs(notes.getValue(id)) { it in notes }
        }
        val finalChapters = chapters.map { chapter ->
            if (chapter.paragraphs.none { it.indexOf(ParagraphMarkup.NOTE_START) >= 0 }) {
                chapter
            } else {
                chapter.copy(paragraphs = chapter.paragraphs.map { p -> NoteSupport.dropUnknownRefs(p) { it in notes } })
            }
        }
        val tocItems = toc.filter { it.chapterIndex in finalChapters.indices }.ifEmpty {
            finalChapters.map { TocItem(id = "ch_${it.index}", title = it.title, chapterIndex = it.index) }
        }

        return ParsedBook(
            title = bookTitle.ifBlank { fileName.substringBeforeLast(".") },
            author = authors.joinToString(", ").ifBlank { "Неизвестный автор" },
            description = annotation.joinToString("\n"),
            seriesName = seriesName,
            seriesOrder = seriesOrder,
            coverBytes = coverBytes,
            chapters = finalChapters,
            tableOfContents = tocItems,
            images = images,
            format = if (isZip) BookFormat.FB2_ZIP else BookFormat.FB2,
            footnotes = notes
        )
    }

    private companion object {
        /** A titled sub-section starts a new chapter once the open one has this many characters. */
        const val INLINE_SECTION_LIMIT = 300_000
    }
}
