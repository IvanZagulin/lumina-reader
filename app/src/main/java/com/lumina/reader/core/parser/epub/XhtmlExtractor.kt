package com.lumina.reader.core.parser.epub

import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.parser.common.MarkupToken
import com.lumina.reader.core.parser.common.MarkupTokenizer
import com.lumina.reader.core.parser.common.NoteSupport
import com.lumina.reader.core.parser.common.ParagraphAccumulator
import com.lumina.reader.core.parser.common.TextSupport
import java.io.StringReader

/** Text extracted from one XHTML document of an EPUB. */
internal class XhtmlDocument(
    val paragraphs: List<String>,
    /** Element id -> index of the paragraph where that element starts. */
    val anchors: Map<String, Int>,
    /** Text of `<head><title>`. */
    val headTitle: String,
    /** Indices of the HEADING paragraphs that open the document (at most 3). */
    val leadingHeadings: List<Int>
)

/**
 * Footnote bookkeeping shared by all documents of one book. Keys are
 * "archivePath#elementId", so notes from different files never collide.
 */
internal class EpubNotes {
    /** Note key -> label of the first reference to it. */
    val referenced = LinkedHashMap<String, String>()

    /** Note key -> note text (inline markup allowed, paragraphs joined with "\n"). */
    val bodies = HashMap<String, String>()
}

/**
 * Turns XHTML into reader paragraphs.
 *
 * Mapping:
 * - block elements (p, div, li, h1-h6, blockquote, tr, ...) end paragraphs;
 * - h1-h6 -> HEADING, blockquote / epigraph -> EPIGRAPH, poem/stanza/verse classes -> VERSE,
 *   author/signature classes -> TEXT_AUTHOR, "* * *" or `<hr>` -> SUBTITLE "* * *";
 * - em/i -> emphasis, strong/b -> strong;
 * - `<br>` splits a paragraph into separate lines; every line of a block that
 *   contained a `<br>` becomes a VERSE paragraph (no indent, no justification).
 *   Two `<br>` in a row, or an empty `<p><br/></p>`, give a blank "" gap paragraph;
 *   inside a heading `<br>` joins the lines with ". ";
 * - table cells are joined with " | ", one paragraph per row;
 * - `<img>` and SVG `<image>` become `[IMG:<id>]` paragraphs in place;
 * - footnote references become [ParagraphMarkup.noteRef] and their bodies are
 *   collected into [EpubNotes]; `<aside>` footnotes are hidden from the flow.
 */
internal class XhtmlExtractor(
    private val docPath: String,
    private val notes: EpubNotes,
    private val resolveImage: (String) -> String?
) {
    private class Capture(val key: String) {
        val parts = ArrayList<String>()
    }

    private class NoteRefState(val key: String, val explicit: Boolean, val hint: Boolean) {
        val label = StringBuilder()
        var abandoned = false
    }

    private class Frame(
        val name: String,
        val block: Boolean,
        val style: BlockStyle?,
        val skip: Boolean,
        val headTitle: Boolean,
        val hidden: Boolean,
        val emphasis: Boolean,
        val strong: Boolean,
        val stanza: Boolean,
        val capture: Capture?,
        val noteRef: NoteRefState?,
        val list: Boolean,
        val ordered: Boolean
    ) {
        var hasBreaks = false
        var itemCount = 0
    }

    private val acc = ParagraphAccumulator()
    private val stack = ArrayList<Frame>()
    private val paragraphs = ArrayList<String>()
    private val anchors = HashMap<String, Int>()
    private val leadingHeadings = ArrayList<Int>()
    private val headTitle = StringBuilder()
    private val captures = ArrayList<Capture>()
    private val pendingInlineKeys = ArrayList<String>()
    private var hiddenDepth = 0
    private var pendingBreaks = 0
    private var pendingPrefix: String? = null
    private var activeNoteRef: NoteRefState? = null
    private var bodyStarted = false

    fun extract(text: String): XhtmlDocument {
        val tokenizer = MarkupTokenizer(StringReader(text), rawTextElements = RAW_TEXT)
        while (true) {
            when (val token = tokenizer.next() ?: break) {
                is MarkupToken.StartTag -> onStart(token)
                is MarkupToken.EndTag -> onEnd(token.name)
                is MarkupToken.Text -> onText(token.text)
            }
        }
        while (stack.isNotEmpty()) popFrame()
        flushBlockBoundary()
        while (paragraphs.isNotEmpty() && paragraphs.last().isEmpty()) paragraphs.removeAt(paragraphs.lastIndex)
        return XhtmlDocument(
            paragraphs = paragraphs,
            anchors = anchors,
            headTitle = TextSupport.collapse(headTitle.toString()),
            leadingHeadings = leadingHeadings
        )
    }

    // ---- events --------------------------------------------------------

    private fun onStart(tag: MarkupToken.StartTag) {
        val name = tag.name
        val parent = stack.lastOrNull()
        val parentSkip = parent?.skip == true

        if (!parentSkip || name == "image") {
            recordAnchor(tag)
        }
        when (name) {
            "br" -> {
                if (!parentSkip) onBreak()
                return
            }
            "hr" -> {
                if (!parentSkip) {
                    flushBlockBoundary()
                    emitSceneBreak()
                }
                return
            }
            "img", "image" -> {
                onImage(tag)
                if (name == "img" || tag.selfClosing) return
            }
        }
        if (name in VOID) return

        val cls = tag.attr("class")?.lowercase().orEmpty()
        val epubType = (tag.attr("epub:type") ?: tag.attributes.firstOrNull { it.first.endsWith(":type") }?.second)
            ?.lowercase().orEmpty()
        val role = tag.attr("role")?.lowercase().orEmpty()
        val block = name in BLOCKS
        val skip = parentSkip || name in SKIPPED
        val isHeadTitle = name == "title" && stack.any { it.name == "head" }

        if (block && !skip) flushBlockBoundary()

        val isNoteElement = NOTE_TYPES.any { epubType.contains(it) } ||
            role.contains("doc-footnote") || role.contains("doc-endnote")
        val hidden = !skip && name == "aside" && isNoteElement
        val id = tag.attr("id")
        var capture: Capture? = null
        if (!skip && id != null && id.isNotEmpty()) {
            val key = "$docPath#$id"
            val referenced = notes.referenced.containsKey(key)
            when {
                isNoteElement || (referenced && block && name !in NOT_CAPTURED) -> capture = Capture(key)
                referenced && !block -> pendingInlineKeys.add(key)
            }
        }

        val style: BlockStyle? = if (skip || !block) null else when {
            name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> BlockStyle.HEADING
            name == "blockquote" -> BlockStyle.EPIGRAPH
            epubType.contains("epigraph") || cls.contains("epigraph") -> BlockStyle.EPIGRAPH
            VERSE_HINTS.any { cls.contains(it) } || epubType.contains("z3998:poem") ||
                epubType.contains("z3998:verse") -> BlockStyle.VERSE
            cls.contains("author") || cls.contains("signature") -> BlockStyle.TEXT_AUTHOR
            cls.contains("subtitle") -> BlockStyle.SUBTITLE
            else -> null
        }

        var noteRef: NoteRefState? = null
        if (!skip && name == "a") {
            val href = tag.attr("href")
            if (href != null && href.contains('#') && !EpubPaths.isExternal(href)) {
                val (path, fragment) = EpubPaths.resolve(docPath, href)
                if (fragment != null) {
                    val explicit = epubType.contains("noteref") || role.contains("doc-noteref")
                    val inSup = stack.any { it.name == "sup" }
                    val hint = inSup || NOTE_HINT.containsMatchIn(cls) || NOTE_HINT.containsMatchIn(fragment)
                    noteRef = NoteRefState("$path#$fragment", explicit, hint)
                }
            }
        }

        val emphasis = !skip && name in EMPHASIS
        val strong = !skip && name in STRONG
        val frame = Frame(
            name = name,
            block = block && !skip,
            style = style,
            skip = skip,
            headTitle = isHeadTitle,
            hidden = hidden,
            emphasis = emphasis,
            strong = strong,
            stanza = !skip && block && cls.contains("stanza"),
            capture = capture,
            noteRef = noteRef,
            list = !skip && (name == "ul" || name == "ol"),
            ordered = name == "ol"
        )
        if (tag.selfClosing) {
            // <p/>, <a id="x"/> and friends: nothing inside.
            return
        }
        stack.add(frame)
        if (hidden) hiddenDepth++
        if (capture != null) captures.add(capture)
        if (emphasis) acc.beginEmphasis()
        if (strong) acc.beginStrong()
        if (noteRef != null && activeNoteRef == null) activeNoteRef = noteRef
        if (!skip && name == "td" || !skip && name == "th") {
            if (acc.hasContent) acc.appendSeparator(" | ")
        }
        if (!skip && name == "li") {
            val list = stack.getOrNull(stack.size - 2)?.takeIf { it.list }
            val insideNav = stack.any { it.name == "nav" }
            if (list != null && !insideNav) {
                list.itemCount++
                pendingPrefix = if (list.ordered) "${list.itemCount}. " else "• "
            }
        }
    }

    private fun onEnd(name: String) {
        val index = stack.indexOfLast { it.name == name }
        if (index < 0) return
        while (stack.size > index) popFrame()
    }

    private fun popFrame() {
        val frame = stack.last()
        if (frame.noteRef != null) finishNoteRef(frame.noteRef)
        if (frame.block) flushBlockBoundary()
        if (frame.emphasis) acc.endEmphasis()
        if (frame.strong) acc.endStrong()
        stack.removeAt(stack.lastIndex)
        if (frame.hidden) hiddenDepth--
        if (frame.capture != null) finishCapture(frame.capture)
        if (frame.name == "li") pendingPrefix = null
        if (frame.stanza) emitGap()
    }

    private fun onText(text: String) {
        val top = stack.lastOrNull()
        if (top != null && top.skip) {
            if (top.headTitle) headTitle.append(text)
            return
        }
        val noteRef = activeNoteRef
        if (noteRef != null && !noteRef.abandoned) {
            noteRef.label.append(text)
            if (noteRef.label.count { !it.isWhitespace() } > MAX_LABEL) {
                noteRef.abandoned = true
                beforeVisibleText(noteRef.label)
                acc.appendText(noteRef.label)
            }
            return
        }
        beforeVisibleText(text)
        acc.appendText(text)
    }

    /** Applies pending line breaks and list prefixes once real text arrives. */
    private fun beforeVisibleText(text: CharSequence) {
        if (text.isBlank()) return
        if (pendingBreaks > 0) applyPendingBreaks()
        pendingPrefix?.let { prefix ->
            pendingPrefix = null
            if (!acc.hasContent) acc.appendText(prefix)
        }
    }

    private fun onBreak() {
        if (stack.any { it.style == BlockStyle.HEADING }) {
            acc.appendSeparator(if (acc.lastVisibleChar in ".!?:;…") " " else ". ")
            return
        }
        pendingBreaks++
    }

    private fun applyPendingBreaks() {
        val count = pendingBreaks
        pendingBreaks = 0
        if (acc.hasContent) {
            stack.lastOrNull { it.block }?.hasBreaks = true
            flush()
            if (count >= 2) emitGap()
        } else if (count >= 2) {
            emitGap()
        }
    }

    private fun onImage(tag: MarkupToken.StartTag) {
        if (hiddenDepth > 0) return
        val src = (if (tag.name == "img") tag.attr("src") else tag.hrefAttr())?.trim()
        if (src.isNullOrEmpty() || src.startsWith("data:", ignoreCase = true) || EpubPaths.isExternal(src)) return
        val path = EpubPaths.resolve(docPath, src).first
        val id = resolveImage(path) ?: return
        activeNoteRef?.let { state ->
            if (!state.abandoned) {
                state.abandoned = true
                acc.appendText(state.label)
            }
        }
        pendingBreaks = 0
        flush()
        paragraphs.add("[IMG:$id]")
    }

    private fun recordAnchor(tag: MarkupToken.StartTag) {
        val id = tag.attr("id") ?: (if (tag.name == "a") tag.attr("name") else null)
        if (!id.isNullOrEmpty() && !anchors.containsKey(id)) anchors[id] = paragraphs.size
    }

    // ---- notes ---------------------------------------------------------

    private fun finishNoteRef(state: NoteRefState) {
        if (activeNoteRef === state) activeNoteRef = null
        if (state.abandoned) return
        val raw = state.label.toString()
        val trimmed = TextSupport.collapse(raw)
        val isNote = trimmed.isNotEmpty() && (
            state.explicit ||
                (NoteSupport.looksLikeNoteLabel(trimmed) && (state.hint || NoteSupport.isBracketed(trimmed)))
            )
        if (isNote) {
            val label = NoteSupport.cleanLabel(trimmed)
            if (pendingBreaks > 0) applyPendingBreaks()
            acc.appendNoteRef(label, state.key)
            notes.referenced.putIfAbsent(state.key, label)
        } else {
            beforeVisibleText(raw)
            acc.appendText(raw)
        }
    }

    private fun finishCapture(capture: Capture) {
        captures.remove(capture)
        if (capture.parts.isEmpty() || capture.parts.size > MAX_NOTE_PARAGRAPHS) return
        val text = capture.parts.joinToString("\n").trim()
        if (text.isNotEmpty()) notes.bodies[capture.key] = text
    }

    // ---- paragraph output ------------------------------------------------

    private fun flushBlockBoundary() {
        val hadContent = acc.hasContent
        flush()
        if (!hadContent && pendingBreaks > 0) emitGap()
        pendingBreaks = 0
    }

    private fun flush() {
        val content = acc.take() ?: return
        emit(currentStyle(), content)
    }

    private fun currentStyle(): BlockStyle {
        for (i in stack.indices.reversed()) {
            stack[i].style?.let { return it }
        }
        return if (stack.lastOrNull { it.block }?.hasBreaks == true) BlockStyle.VERSE else BlockStyle.NORMAL
    }

    private fun emit(style: BlockStyle, content: String) {
        val plain = ParagraphMarkup.plainText(content).trim()
        if (plain.isEmpty()) return
        val isBreak = TextSupport.isSceneBreak(plain)
        val finalStyle = if (isBreak) BlockStyle.SUBTITLE else style
        val finalContent = if (isBreak) ParagraphMarkup.SCENE_BREAK_TEXT else content

        for (capture in captures) {
            if (capture.parts.size <= MAX_NOTE_PARAGRAPHS) capture.parts.add(finalContent)
        }
        if (pendingInlineKeys.isNotEmpty()) {
            for (key in pendingInlineKeys) notes.bodies.putIfAbsent(key, finalContent)
            pendingInlineKeys.clear()
        }
        if (hiddenDepth > 0) return

        if (!bodyStarted) {
            if (finalStyle == BlockStyle.HEADING && leadingHeadings.size < 3) {
                leadingHeadings.add(paragraphs.size)
            } else {
                bodyStarted = true
            }
        }
        paragraphs.add(ParagraphMarkup.block(finalStyle, finalContent))
    }

    private fun emitSceneBreak() {
        if (hiddenDepth > 0) return
        if (paragraphs.isEmpty() || paragraphs.last() == ParagraphMarkup.sceneBreak()) return
        bodyStarted = true
        paragraphs.add(ParagraphMarkup.sceneBreak())
    }

    private fun emitGap() {
        if (hiddenDepth > 0) return
        if (paragraphs.isEmpty() || paragraphs.last().isEmpty()) return
        paragraphs.add("")
    }

    private companion object {
        const val MAX_LABEL = 24
        const val MAX_NOTE_PARAGRAPHS = 40

        val RAW_TEXT = setOf("script", "style")
        val SKIPPED = setOf("head", "script", "style", "noscript", "template", "svg", "math", "object", "title")
        val VOID = setOf(
            "br", "hr", "img", "meta", "link", "input", "col", "area", "base", "wbr",
            "source", "embed", "param", "track", "image"
        )
        val BLOCKS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd",
            "blockquote", "section", "article", "aside", "header", "footer", "nav", "figure",
            "figcaption", "pre", "address", "table", "tr", "thead", "tbody", "tfoot", "caption",
            "center", "body", "html", "main", "hgroup", "details", "summary", "fieldset", "legend", "form"
        )
        val NOT_CAPTURED = setOf(
            "h1", "h2", "h3", "h4", "h5", "h6", "body", "html", "section", "article", "main", "nav", "table"
        )
        val EMPHASIS = setOf("em", "i", "cite", "dfn", "var")
        val STRONG = setOf("strong", "b")
        val NOTE_TYPES = listOf("footnote", "endnote", "rearnote")
        val VERSE_HINTS = listOf("poem", "stanza", "verse", "poetry")
        val NOTE_HINT = Regex("note|fn|ftn|comment", RegexOption.IGNORE_CASE)
    }
}
