package com.lumina.reader.core.model

/**
 * Lightweight markup carried inside the plain-string paragraphs of a
 * [Chapter]. Parsers emit it, the reader renders it, and everything else
 * (search, statistics, text-to-speech, bookmark snippets, AI context) works on
 * [plainText].
 *
 * The markers are Unicode private-use characters, so they never collide with
 * real book text:
 *
 * - an optional block marker as the very first character sets the paragraph
 *   style ([BlockStyle]);
 * - paired inline markers wrap emphasised or strong text;
 * - a footnote reference is `NOTE_START label NOTE_ID_SEP id NOTE_END`, where
 *   `label` is what the reader shows (usually a number) and `id` is the key in
 *   [ParsedBook.footnotes].
 *
 * Image paragraphs keep the older `[IMG:<id>]` convention and are not affected.
 */
object ParagraphMarkup {
    const val SUBTITLE = ''
    const val VERSE = ''
    const val EPIGRAPH = ''
    const val TEXT_AUTHOR = ''
    const val HEADING = ''

    const val EMPHASIS_START = ''
    const val EMPHASIS_END = ''
    const val STRONG_START = ''
    const val STRONG_END = ''

    const val NOTE_START = ''
    const val NOTE_END = ''
    const val NOTE_ID_SEP = ''

    enum class BlockStyle(val marker: Char?) {
        NORMAL(null),
        /** Centered section subtitle or scene break such as "* * *". */
        SUBTITLE(ParagraphMarkup.SUBTITLE),
        /** A line of verse: no first-line indent, no justification. */
        VERSE(ParagraphMarkup.VERSE),
        /** Epigraph or quotation: italic, indented from the start. */
        EPIGRAPH(ParagraphMarkup.EPIGRAPH),
        /** Author line under an epigraph or poem: right-aligned italic. */
        TEXT_AUTHOR(ParagraphMarkup.TEXT_AUTHOR),
        /** In-chapter heading that did not start a new chapter. */
        HEADING(ParagraphMarkup.HEADING)
    }

    enum class InlineKind { EMPHASIS, STRONG, NOTE_REF }

    /** [start] inclusive, [end] exclusive, both in [Parsed.text] coordinates. */
    data class InlineSpan(
        val start: Int,
        val end: Int,
        val kind: InlineKind,
        val noteId: String? = null
    )

    data class Parsed(
        val style: BlockStyle,
        val text: String,
        val spans: List<InlineSpan>
    )

    private val blockMarkers: Map<Char, BlockStyle> =
        BlockStyle.entries.mapNotNull { style -> style.marker?.let { it to style } }.toMap()

    fun isMarker(c: Char): Boolean = c in ''..'' || c in ''..'' || c in ''..''

    fun hasMarkup(raw: String): Boolean = raw.any(::isMarker)

    // ---- Builders used by the parsers -------------------------------------

    fun block(style: BlockStyle, text: String): String =
        style.marker?.let { "$it$text" } ?: text

    fun emphasis(text: String): String =
        if (text.isEmpty()) text else "$EMPHASIS_START$text$EMPHASIS_END"

    fun strong(text: String): String =
        if (text.isEmpty()) text else "$STRONG_START$text$STRONG_END"

    fun noteRef(label: String, id: String): String =
        "$NOTE_START${label.filterNot(::isMarker)}$NOTE_ID_SEP${id.filterNot(::isMarker)}$NOTE_END"

    // ---- Readers ---------------------------------------------------------

    fun blockStyle(raw: String): BlockStyle =
        raw.firstOrNull()?.let(blockMarkers::get) ?: BlockStyle.NORMAL

    /**
     * Splits [raw] into display text and styling. Unbalanced markers are
     * tolerated: an unclosed span runs to the end of the paragraph and a stray
     * closing marker is dropped.
     */
    fun parse(raw: String): Parsed {
        if (!hasMarkup(raw)) return Parsed(BlockStyle.NORMAL, raw, emptyList())

        val style = blockStyle(raw)
        val out = StringBuilder(raw.length)
        val spans = mutableListOf<InlineSpan>()
        var emphasisStart = -1
        var strongStart = -1
        var index = if (style == BlockStyle.NORMAL) 0 else 1

        while (index < raw.length) {
            val c = raw[index]
            when (c) {
                EMPHASIS_START -> if (emphasisStart < 0) emphasisStart = out.length
                EMPHASIS_END -> if (emphasisStart >= 0) {
                    if (out.length > emphasisStart) {
                        spans += InlineSpan(emphasisStart, out.length, InlineKind.EMPHASIS)
                    }
                    emphasisStart = -1
                }
                STRONG_START -> if (strongStart < 0) strongStart = out.length
                STRONG_END -> if (strongStart >= 0) {
                    if (out.length > strongStart) {
                        spans += InlineSpan(strongStart, out.length, InlineKind.STRONG)
                    }
                    strongStart = -1
                }
                NOTE_START -> {
                    val close = raw.indexOf(NOTE_END, startIndex = index + 1)
                    if (close < 0) {
                        // Broken reference: keep whatever text follows.
                        index++
                        continue
                    }
                    val body = raw.substring(index + 1, close)
                    val sep = body.indexOf(NOTE_ID_SEP)
                    val label = (if (sep >= 0) body.substring(0, sep) else body).filterNot(::isMarker)
                    val id = (if (sep >= 0) body.substring(sep + 1) else body).filterNot(::isMarker)
                    if (label.isNotEmpty()) {
                        val start = out.length
                        out.append(label)
                        spans += InlineSpan(start, out.length, InlineKind.NOTE_REF, noteId = id.ifEmpty { label })
                    }
                    index = close + 1
                    continue
                }
                NOTE_END, NOTE_ID_SEP -> Unit
                else -> if (!isMarker(c)) out.append(c)
            }
            index++
        }

        if (emphasisStart in 0 until out.length) {
            spans += InlineSpan(emphasisStart, out.length, InlineKind.EMPHASIS)
        }
        if (strongStart in 0 until out.length) {
            spans += InlineSpan(strongStart, out.length, InlineKind.STRONG)
        }
        return Parsed(style, out.toString(), spans.sortedBy { it.start })
    }

    /** Display text without any markers; footnote references keep their label. */
    fun plainText(raw: String): String =
        if (!hasMarkup(raw)) raw else parse(raw).text

    /** True for the `[IMG:<id>]` paragraphs that stand for an illustration. */
    fun isImage(raw: String): Boolean = raw.startsWith("[IMG:") && raw.endsWith("]")

    fun imageId(raw: String): String? =
        if (isImage(raw)) raw.substring(5, raw.length - 1) else null
}
