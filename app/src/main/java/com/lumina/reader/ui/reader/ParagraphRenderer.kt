@file:OptIn(ExperimentalTextApi::class)

package com.lumina.reader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.bionic.BionicReadingHelper
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ParagraphMarkup.BlockStyle
import com.lumina.reader.core.model.ReaderFontIds
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.core.model.ReaderTextAlign

/**
 * Everything about the text that changes its layout. Pages measured with one
 * typography are only valid for exactly the same typography.
 */
internal data class ReaderTypography(
    val fontSizeSp: Int,
    val lineSpacing: Float,
    val fontFamilyName: String,
    val justify: Boolean,
    val hyphenation: Boolean,
    val firstLineIndentEm: Float,
    val bionic: Boolean,
    /** Language of the book text (e.g. "ru") so hyphenation uses the right patterns. */
    val localeTag: String?
)

internal fun ReaderSettings.toTypography(localeTag: String?): ReaderTypography = ReaderTypography(
    fontSizeSp = fontSizeSp,
    lineSpacing = lineSpacingMultiplier,
    fontFamilyName = ReaderFontIds.migrate(fontFamily),
    justify = textAlign == ReaderTextAlign.JUSTIFY,
    hyphenation = hyphenation,
    firstLineIndentEm = firstLineIndentEm.coerceAtLeast(0f),
    bionic = isBionicReadingEnabled,
    localeTag = localeTag
)

/**
 * Colours used inside paragraphs. None of them changes the layout.
 * [searchMatch] and [ttsSentence] tint the backgrounds drawn behind the text
 * (see HighlightDrawing), [noteRef] colours footnote markers.
 */
internal data class ReaderTextColors(
    val text: Color,
    val noteRef: Color,
    val searchMatch: Color,
    val ttsSentence: Color = Color.Transparent
)

/** A range of plain-text offsets: [start] inclusive, [end] exclusive. */
internal data class OffsetRange(
    val start: Int,
    val end: Int
)

/**
 * A highlight drawn behind [start, end) of a paragraph. [id] is the saved
 * highlight (0 for none); a tap on it opens its actions.
 */
internal data class HighlightSpan(
    val start: Int,
    val end: Int,
    val color: Color,
    val id: Long = 0L,
    val hasNote: Boolean = false
)

/**
 * A paragraph ready for measuring and drawing. [text] is exactly the
 * paragraph's [ParagraphMarkup.plainText], so text offsets are positions.
 * [startIndentEm]/[endIndentEm] are block margins relative to the reader font
 * size; the caller turns them into padding and narrows the layout width.
 */
internal data class RenderedParagraph(
    val text: AnnotatedString,
    val style: TextStyle,
    val blockStyle: BlockStyle,
    val startIndentEm: Float,
    val endIndentEm: Float
)

internal const val NOTE_LINK_TAG_PREFIX = "note:"
internal const val HIGHLIGHT_LINK_TAG_PREFIX = "highlight:"
internal val DEFAULT_HIGHLIGHT_COLOR = Color(0xFFFFEB3B)

/**
 * The one function that turns a raw paragraph into styled text. Pagination
 * measures its result and the reader draws it, so both see identical layout.
 * Highlights only add click targets (their colour is drawn behind the text
 * by HighlightDrawing) and footnotes add links: nothing that changes metrics.
 */
internal fun renderParagraph(
    raw: String,
    typography: ReaderTypography,
    colors: ReaderTextColors,
    highlights: List<HighlightSpan> = emptyList(),
    onNoteClick: ((String) -> Unit)? = null,
    onHighlightClick: ((Long) -> Unit)? = null
): RenderedParagraph {
    val parsed = ParagraphMarkup.parse(raw)
    val text = parsed.text
    val builder = AnnotatedString.Builder(text)

    fun clamp(start: Int, end: Int): OffsetRange? {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        return if (to > from) OffsetRange(from, to) else null
    }

    // Highlight links go first: links added later lie on top, so a footnote
    // marker inside a highlight still opens its footnote.
    if (onHighlightClick != null) {
        for (highlight in highlights) {
            if (highlight.id == 0L) continue
            val range = clamp(highlight.start, highlight.end) ?: continue
            val id = highlight.id
            builder.addLink(
                LinkAnnotation.Clickable(
                    tag = HIGHLIGHT_LINK_TAG_PREFIX + id,
                    styles = null,
                    linkInteractionListener = LinkInteractionListener { onHighlightClick(id) }
                ),
                range.start,
                range.end
            )
        }
    }

    for (span in parsed.spans) {
        val range = clamp(span.start, span.end) ?: continue
        when (span.kind) {
            ParagraphMarkup.InlineKind.EMPHASIS ->
                builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), range.start, range.end)
            ParagraphMarkup.InlineKind.STRONG ->
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.start, range.end)
            ParagraphMarkup.InlineKind.NOTE_REF -> {
                builder.addStyle(noteRefStyle(colors.noteRef), range.start, range.end)
                val noteId = span.noteId
                if (onNoteClick != null && noteId != null) {
                    builder.addLink(
                        LinkAnnotation.Clickable(
                            tag = NOTE_LINK_TAG_PREFIX + noteId,
                            styles = null,
                            linkInteractionListener = LinkInteractionListener { onNoteClick(noteId) }
                        ),
                        range.start,
                        range.end
                    )
                }
            }
        }
    }

    if (typography.bionic && parsed.style != BlockStyle.HEADING && parsed.style != BlockStyle.SUBTITLE) {
        // The helper keeps every character, so its bold prefixes line up with
        // the plain text and merge with the markup spans above.
        for (bold in BionicReadingHelper.transform(text).spanStyles) {
            val range = clamp(bold.start, bold.end) ?: continue
            builder.addStyle(bold.item, range.start, range.end)
        }
    }

    return RenderedParagraph(
        text = builder.toAnnotatedString(),
        style = paragraphTextStyle(parsed.style, typography, colors.text),
        blockStyle = parsed.style,
        startIndentEm = startIndentEm(parsed.style),
        endIndentEm = endIndentEm(parsed.style)
    )
}

/** Superscript footnote label. Part of the measured text, so it is a plain span. */
internal fun noteRefStyle(color: Color): SpanStyle = SpanStyle(
    color = color,
    fontSize = 0.7.em,
    fontWeight = FontWeight.SemiBold,
    baselineShift = BaselineShift.Superscript
)

internal fun startIndentEm(style: BlockStyle): Float = when (style) {
    BlockStyle.VERSE -> 1f
    BlockStyle.EPIGRAPH -> 2f
    BlockStyle.NORMAL, BlockStyle.SUBTITLE, BlockStyle.TEXT_AUTHOR, BlockStyle.HEADING -> 0f
}

internal fun endIndentEm(style: BlockStyle): Float = when (style) {
    BlockStyle.EPIGRAPH -> 1f
    BlockStyle.NORMAL, BlockStyle.SUBTITLE, BlockStyle.VERSE,
    BlockStyle.TEXT_AUTHOR, BlockStyle.HEADING -> 0f
}

/** Text and paragraph style of one block style. */
internal fun paragraphTextStyle(
    blockStyle: BlockStyle,
    typography: ReaderTypography,
    textColor: Color
): TextStyle {
    val baseSize = typography.fontSizeSp.toFloat()
    val fontSize = when (blockStyle) {
        BlockStyle.EPIGRAPH -> baseSize * 0.9f
        BlockStyle.HEADING -> baseSize + 2f
        BlockStyle.NORMAL, BlockStyle.SUBTITLE, BlockStyle.VERSE, BlockStyle.TEXT_AUTHOR -> baseSize
    }
    val textAlign = when (blockStyle) {
        BlockStyle.NORMAL -> if (typography.justify) TextAlign.Justify else TextAlign.Start
        BlockStyle.SUBTITLE -> TextAlign.Center
        BlockStyle.VERSE, BlockStyle.EPIGRAPH, BlockStyle.HEADING -> TextAlign.Start
        BlockStyle.TEXT_AUTHOR -> TextAlign.End
    }
    val fontWeight: FontWeight? = when (blockStyle) {
        BlockStyle.SUBTITLE -> FontWeight.SemiBold
        BlockStyle.HEADING -> FontWeight.Bold
        BlockStyle.NORMAL, BlockStyle.VERSE, BlockStyle.EPIGRAPH, BlockStyle.TEXT_AUTHOR -> null
    }
    val fontStyle: FontStyle? = when (blockStyle) {
        BlockStyle.EPIGRAPH, BlockStyle.TEXT_AUTHOR -> FontStyle.Italic
        BlockStyle.NORMAL, BlockStyle.SUBTITLE, BlockStyle.VERSE, BlockStyle.HEADING -> null
    }
    val textIndent: TextIndent? =
        if (blockStyle == BlockStyle.NORMAL && typography.firstLineIndentEm > 0f) {
            TextIndent(firstLine = typography.firstLineIndentEm.em)
        } else {
            null
        }
    val hyphenate = typography.hyphenation &&
        (blockStyle == BlockStyle.NORMAL || blockStyle == BlockStyle.EPIGRAPH)
    return TextStyle(
        color = textColor,
        fontSize = fontSize.sp,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = readerFontFamily(typography.fontFamilyName),
        localeList = typography.localeTag?.let { LocaleList(it) },
        textAlign = textAlign,
        lineHeight = typography.lineSpacing.em,
        textIndent = textIndent,
        lineBreak = LineBreak.Paragraph,
        hyphens = if (hyphenate) Hyphens.Auto else Hyphens.None
    )
}

/** Style of the chapter title at the top of a chapter's first page. */
internal fun chapterTitleStyle(typography: ReaderTypography, textColor: Color): TextStyle = TextStyle(
    color = textColor,
    fontSize = (typography.fontSizeSp + 3).sp,
    fontWeight = FontWeight.Bold,
    fontFamily = readerFontFamily(typography.fontFamilyName),
    localeList = typography.localeTag?.let { LocaleList(it) },
    textAlign = TextAlign.Start,
    lineHeight = typography.lineSpacing.em,
    lineBreak = LineBreak.Paragraph,
    hyphens = Hyphens.None
)

/** Parses "#RRGGBB" or "#AARRGGBB"; anything else becomes [fallback]. */
internal fun parseHighlightColor(hex: String, fallback: Color = DEFAULT_HIGHLIGHT_COLOR): Color {
    val digits = hex.trim().removePrefix("#")
    val value = digits.toLongOrNull(16) ?: return fallback
    return when (digits.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> fallback
    }
}

/**
 * Language tag for hyphenation: "ru" when the sample is mostly Cyrillic,
 * otherwise null (the system language is used).
 */
internal fun detectTextLocaleTag(sample: String): String? {
    var cyrillic = 0
    var latin = 0
    for (c in sample) {
        when {
            c in 'Ѐ'..'ӿ' -> cyrillic++
            c in 'a'..'z' || c in 'A'..'Z' -> latin++
        }
    }
    return if (cyrillic > 0 && cyrillic >= latin) "ru" else null
}
