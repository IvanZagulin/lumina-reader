package com.lumina.reader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParagraphMarkup
import com.lumina.reader.core.model.ReaderSettings
import com.lumina.reader.platform.PlatformLock
import kotlin.math.roundToInt

/**
 * Everything pagination depends on. Two equal specs produce identical pages,
 * so the spec is also the key of [ChapterPageCache].
 */
internal data class PageLayoutSpec(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val paragraphSpacingPx: Int,
    val titleSpacingPx: Int,
    val density: Float,
    val fontScale: Float,
    val typography: ReaderTypography
)

/** Start and end margins of a block in pixels. */
internal data class BlockMargins(
    val start: Int,
    val end: Int
)

/** Block margins of [rendered]; measuring and drawing must both use this. */
internal fun blockMarginsPx(
    rendered: RenderedParagraph,
    typography: ReaderTypography,
    density: Density
): BlockMargins = with(density) {
    BlockMargins(
        start = if (rendered.startIndentEm > 0f) {
            (rendered.startIndentEm * typography.fontSizeSp).sp.toPx().roundToInt()
        } else {
            0
        },
        end = if (rendered.endIndentEm > 0f) {
            (rendered.endIndentEm * typography.fontSizeSp).sp.toPx().roundToInt()
        } else {
            0
        }
    )
}

/**
 * Space after a paragraph in pixels: [ReaderSettings.paragraphSpacingEm] of
 * the reading font size. Both viewers and the paginator use it.
 */
internal fun paragraphSpacingPx(settings: ReaderSettings, density: Density): Int = with(density) {
    (settings.paragraphSpacingEm.coerceAtLeast(0f) * settings.fontSizeSp).sp.toPx().roundToInt()
}

/**
 * Height of the blank space an empty paragraph (stanza break, blank line)
 * stands for: about half a line. Both viewers use it.
 */
internal fun blankParagraphGapPx(typography: ReaderTypography, density: Density): Int = with(density) {
    (typography.fontSizeSp * typography.lineSpacing.coerceAtLeast(1f) * 0.5f).sp.toPx().roundToInt()
}

internal fun TextLayoutResult.toParagraphLines(): ParagraphLines {
    val count = lineCount
    return ParagraphLines(
        lineTops = FloatArray(count) { getLineTop(it) },
        lineBottoms = FloatArray(count) { getLineBottom(it) },
        lineStarts = IntArray(count) { getLineStart(it) },
        textLength = layoutInput.text.length
    )
}

/** Colours do not affect layout; measuring uses fixed ones. */
private val MEASURE_COLORS = ReaderTextColors(
    text = Color.Black,
    noteRef = Color.Black,
    searchMatch = Color.Transparent
)

/**
 * Measures every paragraph of a chapter once and splits the chapter into
 * pages. The [measurer] must belong to the calling thread: TextMeasurer is
 * not thread-safe, so background work creates its own instance.
 */
internal class ChapterPaginator(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val spec: PageLayoutSpec
) {
    /** Pages of [chapter], which is chapter number [chapterIndex] of the book. */
    fun paginate(
        chapter: Chapter,
        chapterIndex: Int,
        checkCancelled: () -> Unit = {}
    ): ChapterPages {
        val typography = spec.typography
        val title = displayChapterTitle(chapter.title, chapterIndex)
        val titleHeight = measurer.measure(
            text = AnnotatedString(title),
            style = chapterTitleStyle(typography, MEASURE_COLORS.text),
            constraints = Constraints(maxWidth = spec.contentWidthPx.coerceAtLeast(1))
        ).size.height + spec.titleSpacingPx

        val gapPx = blankParagraphGapPx(typography, density)
        val measured = ArrayList<MeasuredParagraph>(chapter.paragraphs.size)
        chapter.paragraphs.forEachIndexed { index, raw ->
            checkCancelled()
            if (ParagraphMarkup.isImage(raw)) {
                ParagraphMarkup.imageId(raw)?.let { measured += MeasuredParagraph.Image(index, it) }
                return@forEachIndexed
            }
            if (raw.isBlank()) {
                measured += MeasuredParagraph.Gap(index, gapPx)
                return@forEachIndexed
            }
            val rendered = renderParagraph(raw, typography, MEASURE_COLORS)
            if (rendered.text.text.isBlank()) return@forEachIndexed
            val margins = blockMarginsPx(rendered, typography, density)
            val width = (spec.contentWidthPx - margins.start - margins.end).coerceAtLeast(1)
            val layout = measurer.measure(
                text = rendered.text,
                style = rendered.style,
                constraints = Constraints(maxWidth = width)
            )
            measured += MeasuredParagraph.Text(index, layout.toParagraphLines())
        }

        val pages = paginateParagraphs(
            paragraphs = measured,
            contentHeightPx = spec.contentHeightPx,
            titleHeightPx = titleHeight,
            paragraphSpacingPx = spec.paragraphSpacingPx,
            checkCancelled = checkCancelled
        )
        return ChapterPages(chapterIndex, pages)
    }
}

/**
 * Pages of the chapters measured with the active [PageLayoutSpec]. Results
 * for any other spec are dropped, so a late background job for old settings
 * cannot pollute the cache. Thread-safe.
 */
internal class ChapterPageCache {
    private val lock = PlatformLock()
    private var activeSpec: PageLayoutSpec? = null
    private val chapters = HashMap<Int, ChapterPages>()

    /** Makes [spec] current, forgetting pages of any other spec. */
    fun activate(spec: PageLayoutSpec) {
        lock.withLock {
            if (activeSpec != spec) {
                activeSpec = spec
                chapters.clear()
            }
        }
    }

    fun get(spec: PageLayoutSpec, chapterIndex: Int): ChapterPages? = lock.withLock {
        if (activeSpec == spec) chapters[chapterIndex] else null
    }

    fun put(spec: PageLayoutSpec, pages: ChapterPages) {
        lock.withLock {
            if (activeSpec == spec) chapters[pages.chapterIndex] = pages
        }
    }

    /** The whole-book page map once every chapter has been paginated. */
    fun bookPageMap(spec: PageLayoutSpec, chapterCount: Int): BookPageMap? {
        val all = ArrayList<ChapterPages>(chapterCount.coerceAtLeast(0))
        // withLock is not inline, so a missing chapter is reported as false
        // instead of returning from bookPageMap inside the lock.
        val complete = lock.withLock {
            if (activeSpec != spec || chapterCount <= 0) return@withLock false
            for (index in 0 until chapterCount) {
                val pages = chapters[index] ?: return@withLock false
                all += pages
            }
            true
        }
        if (!complete) return null
        return BookPageMap(
            pageStartParagraphs = all.map { chapter ->
                IntArray(chapter.pages.size) { chapter.pages[it].start.paragraphIndex }
            },
            pageStartOffsets = all.map { chapter ->
                IntArray(chapter.pages.size) { chapter.pages[it].start.charOffset }
            }
        )
    }
}
