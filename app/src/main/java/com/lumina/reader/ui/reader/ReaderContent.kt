package com.lumina.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import com.lumina.reader.core.bionic.BionicReadingHelper
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.core.model.Chapter
import com.lumina.reader.core.model.ParsedBook
import com.lumina.reader.core.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderContent(
    book: Book,
    parsedBook: ParsedBook?,
    chapter: Chapter,
    initialParagraphIndex: Int,
    settings: ReaderSettings,
    onToggleControls: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onParagraphVisible: (Int) -> Unit,
    onParagraphFragmentVisible: (paragraphIndex: Int, fragmentIndex: Int, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val navigationOwner = remember { Any() }
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val latestPreviousChapter by rememberUpdatedState(onPreviousChapter)
    val visibleChapterTitle = remember(chapter.title, chapter.index) {
        displayChapterTitle(chapter.title, chapter.index)
    }

    val fontFamily = when (settings.fontFamily) {
        "Serif" -> FontFamily.Serif
        "SansSerif" -> FontFamily.SansSerif
        "Monospace" -> FontFamily.Monospace
        "Cursive" -> FontFamily.Cursive
        else -> FontFamily.Serif
    }

    val totalChapters = (parsedBook?.chapters?.size ?: 1).coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(settings.theme.bgComposeColor)
    ) {
        if (book.format == BookFormat.PDF) {
            DisposableEffect(settings.volumeKeyNavigation) {
                if (settings.volumeKeyNavigation) {
                    ReaderPageNavigation.register(navigationOwner) { direction ->
                        if (direction == PageTurnDirection.NEXT) latestNextChapter()
                        else latestPreviousChapter()
                        true
                    }
                }
                onDispose { ReaderPageNavigation.unregister(navigationOwner) }
            }
            // PDF Page Rendering
            PdfPageViewer(
                filePath = book.filePath,
                pageNumber = chapter.pdfPageNumber,
                settings = settings,
                onToggleControls = onToggleControls,
                onNextPage = onNextChapter,
                onPreviousPage = onPreviousChapter,
                modifier = Modifier.fillMaxSize()
            )
        } else if (settings.isContinuousScroll) {
            // 1. Continuous Vertical Scroll Mode
            val initialListIndex = when (initialParagraphIndex) {
                0 -> if (chapter.paragraphs.isEmpty()) 0 else 1
                Int.MAX_VALUE -> chapter.paragraphs.size
                else -> if (chapter.paragraphs.isEmpty()) {
                    0
                } else {
                    initialParagraphIndex.coerceIn(0, chapter.paragraphs.lastIndex) + 1
                }
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialListIndex)

            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .collect { index ->
                        // Item zero is the chapter title, paragraph zero starts at
                        // item one. Keep persisted progress in paragraph coordinates.
                        val paragraphIndex = if (chapter.paragraphs.isEmpty()) {
                            0
                        } else {
                            (index - 1).coerceIn(0, chapter.paragraphs.lastIndex)
                        }
                        onParagraphVisible(paragraphIndex)
                    }
            }

            DisposableEffect(settings.volumeKeyNavigation, listState) {
                if (settings.volumeKeyNavigation) {
                    ReaderPageNavigation.register(navigationOwner) { direction ->
                        coroutineScope.launch {
                            val viewport = listState.layoutInfo.viewportSize.height
                                .takeIf { it > 0 }
                                ?.times(0.88f)
                                ?: 900f
                            val delta = if (direction == PageTurnDirection.NEXT) viewport else -viewport
                            val consumed = listState.scrollBy(delta)
                            if (kotlin.math.abs(consumed) < 1f) {
                                if (direction == PageTurnDirection.NEXT) latestNextChapter()
                                else latestPreviousChapter()
                            }
                        }
                        true
                    }
                }
                onDispose { ReaderPageNavigation.unregister(navigationOwner) }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val screenWidth = size.width
                            val x = offset.x
                            if (x in (screenWidth * 0.25f)..(screenWidth * 0.75f)) {
                                onToggleControls()
                            }
                        }
                    }
            ) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = settings.horizontalPaddingDp.dp),
                        contentPadding = PaddingValues(
                            top = 56.dp,
                            bottom = 70.dp
                        )
                    ) {
                        item(key = "title_${chapter.index}") {
                            Text(
                                text = visibleChapterTitle,
                                fontSize = (settings.fontSizeSp + 4).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = fontFamily,
                                color = settings.theme.textComposeColor,
                                lineHeight = ((settings.fontSizeSp + 4) * settings.lineSpacingMultiplier).sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 20.dp, top = 12.dp)
                            )
                        }

                        itemsIndexed(
                            items = chapter.paragraphs,
                            key = { index, _ -> "p_${chapter.index}_$index" }
                        ) { index, paragraph ->
                            if (paragraph.isBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                            } else {
                                val annotatedText = remember(paragraph, settings.isBionicReadingEnabled) {
                                    if (settings.isBionicReadingEnabled) BionicReadingHelper.transform(paragraph) else null
                                }

                                if (annotatedText != null) {
                                    Text(
                                        text = annotatedText,
                                        fontSize = settings.fontSizeSp.sp,
                                        fontFamily = fontFamily,
                                        color = settings.theme.textComposeColor,
                                        lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                                        textAlign = TextAlign.Justify,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp)
                                    )
                                } else {
                                    Text(
                                        text = paragraph,
                                        fontSize = settings.fontSizeSp.sp,
                                        fontFamily = fontFamily,
                                        color = settings.theme.textComposeColor,
                                        lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                                        textAlign = TextAlign.Justify,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            PagedChapterViewer(
                chapter = chapter,
                parsedBook = parsedBook,
                initialParagraphIndex = initialParagraphIndex,
                settings = settings,
                fontFamily = fontFamily,
                totalChapters = totalChapters,
                navigationOwner = navigationOwner,
                onToggleControls = onToggleControls,
                onNextChapter = latestNextChapter,
                onPreviousChapter = latestPreviousChapter,
                onParagraphVisible = onParagraphVisible,
                onParagraphFragmentVisible = onParagraphFragmentVisible,
                modifier = Modifier.fillMaxSize()
            )

            /* Legacy character-count pagination retained temporarily for easy
               comparison while the measured paginator is exercised on-device.
            // 2. Horizontal Paged Book Mode (True Book-Like Page Flipping)
            val pages = remember(chapter.index, chapter.paragraphs, settings.fontSizeSp, settings.horizontalPaddingDp, settings.lineSpacingMultiplier, parsedBook) {
                val scale = 18f / settings.fontSizeSp.coerceAtLeast(1f)
                // charsPerPage = rough chars that fit on screen, accounting for font size and line spacing
                val charsPerPage = (1100 * scale * scale / settings.lineSpacingMultiplier).toInt().coerceIn(150, 3000)

                val newPages = mutableListOf<Pair<List<String>, Int>>()
                var currentPage = mutableListOf<String>()
                var currentChars = 0
                var startParagraphIdx = 0

                for ((idx, p) in chapter.paragraphs.withIndex()) {
                    // skip blank lines but allow them inside pages for spacing
                    if (p.isBlank()) {
                        if (currentPage.isNotEmpty()) {
                            currentChars += 30 // blank line costs some space
                        }
                        continue
                    }

                    // Image paragraph: flush current page, then add image as its own full page
                    if (p.startsWith("[IMG:") && p.endsWith("]")) {
                        if (currentPage.isNotEmpty()) {
                            newPages.add(currentPage to startParagraphIdx)
                            currentPage = mutableListOf()
                            currentChars = 0
                        }
                        newPages.add(mutableListOf(p) to idx)
                        startParagraphIdx = idx + 1
                        continue
                    }

                    if (currentPage.isEmpty()) startParagraphIdx = idx

                    // Split long paragraphs across pages
                    var remaining = p
                    while (remaining.isNotEmpty()) {
                        val spaceLeft = charsPerPage - currentChars
                        if (spaceLeft <= 60 && currentPage.isNotEmpty()) {
                            // Not enough room — flush page
                            newPages.add(currentPage to startParagraphIdx)
                            currentPage = mutableListOf()
                            currentChars = 0
                            startParagraphIdx = idx
                            continue
                        }

                        if (remaining.length <= spaceLeft) {
                            currentPage.add(remaining)
                            currentChars += remaining.length + 80 // paragraph padding cost
                            remaining = ""
                        } else {
                            // Find a word boundary to split
                            var splitIndex = remaining.lastIndexOf(' ', spaceLeft.coerceAtMost(remaining.length - 1))
                            if (splitIndex < spaceLeft * 0.5f || splitIndex == -1) {
                                splitIndex = spaceLeft.coerceAtMost(remaining.length)
                            }
                            currentPage.add(remaining.substring(0, splitIndex))
                            remaining = remaining.substring(splitIndex).trimStart()
                            newPages.add(currentPage to startParagraphIdx)
                            currentPage = mutableListOf()
                            currentChars = 0
                            startParagraphIdx = idx
                        }
                    }
                }
                if (currentPage.isNotEmpty()) {
                    newPages.add(currentPage to startParagraphIdx)
                }

                if (newPages.isEmpty()) listOf(listOf("Конец главы") to 0) else newPages
            }

            key(chapter.index) {
                val initialPage = if (initialParagraphIndex == Int.MAX_VALUE) {
                    pages.size - 1
                } else {
                    val found = pages.indexOfFirst { it.second >= initialParagraphIndex }
                    if (found == -1) 0 else found
                }

                val pagerState = rememberPagerState(
                    initialPage = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                    pageCount = { pages.size }
                )

                LaunchedEffect(pagerState.currentPage) {
                    val pIdx = pages.getOrNull(pagerState.currentPage)?.second ?: 0
                    onParagraphVisible(pIdx)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(pages.size, pagerState.currentPage) {
                            detectTapGestures { offset ->
                                val screenWidth = size.width
                                val x = offset.x
                                when {
                                    x < screenWidth * 0.30f -> {
                                        if (pagerState.currentPage > 0) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                            }
                                        } else {
                                            onPreviousChapter()
                                        }
                                    }
                                    x > screenWidth * 0.70f -> {
                                        if (pagerState.currentPage < pages.size - 1) {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                            }
                                        } else {
                                            onNextChapter()
                                        }
                                    }
                                    else -> {
                                        onToggleControls()
                                    }
                                }
                            }
                        }
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { pageIdx ->
                        val pageParagraphs = pages.getOrNull(pageIdx)?.first ?: emptyList()
                        val pageOffset = (pagerState.currentPage - pageIdx) + pagerState.currentPageOffsetFraction
                        val scale = 1f - 0.1f * kotlin.math.abs(pageOffset)
                        val alpha = 1f - 0.3f * kotlin.math.abs(pageOffset)

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                }
                                .padding(horizontal = settings.horizontalPaddingDp.dp)
                                .padding(top = 44.dp, bottom = 48.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                        // Top Subtle Book Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = chapter.title,
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 11.sp,
                                    color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.7f)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Page Body Text
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.Top
                            ) {
                                if (pageIdx == 0) {
                                    Text(
                                        text = chapter.title,
                                        fontSize = (settings.fontSizeSp + 3).sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = fontFamily,
                                        color = settings.theme.textComposeColor,
                                        lineHeight = ((settings.fontSizeSp + 3) * settings.lineSpacingMultiplier).sp,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                }

                                pageParagraphs.forEach { p ->
                                    if (p.startsWith("[IMG:") && p.endsWith("]")) {
                                        val imgId = p.substring(5, p.length - 1)
                                        val allKeys = parsedBook?.images?.keys?.joinToString(", ") ?: "null"
                                        val imgBytes = parsedBook?.images?.get(imgId)
                                        Log.d("ReaderImages", "Rendering page imgId='$imgId', keys=[$allKeys], found=${imgBytes != null}")
                                        if (imgBytes != null) {
                                            val bitmap = remember(imgId) {
                                                try {
                                                    android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)?.asImageBitmap()
                                                } catch(e: Exception) { null }
                                            }
                                            if (bitmap != null) {
                                                Image(
                                                    bitmap = bitmap,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .fillMaxHeight(0.85f)
                                                        .padding(vertical = 12.dp)
                                                        .clip(RoundedCornerShape(8.dp)),
                                                    contentScale = ContentScale.Fit
                                                )
                                            } else {
                                                Text("[Ошибка загрузки: $imgId]", color = settings.theme.secondaryTextComposeColor)
                                            }
                                        } else {
                                            Text("[Изображение не найдено: $imgId]", color = settings.theme.secondaryTextComposeColor, fontSize = 12.sp)
                                        }
                                    } else {
                                        val annotatedText = remember(p, settings.isBionicReadingEnabled) {
                                            if (settings.isBionicReadingEnabled) BionicReadingHelper.transform(p) else null
                                        }

                                        if (annotatedText != null) {
                                            Text(
                                                text = annotatedText,
                                                fontSize = settings.fontSizeSp.sp,
                                                fontFamily = fontFamily,
                                                color = settings.theme.textComposeColor,
                                                lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                                                textAlign = TextAlign.Justify,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 10.dp)
                                            )
                                        } else {
                                            Text(
                                                text = p,
                                                fontSize = settings.fontSizeSp.sp,
                                                fontFamily = fontFamily,
                                                color = settings.theme.textComposeColor,
                                                lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp,
                                                textAlign = TextAlign.Justify,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Page Number & Footer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Глава ${chapter.index + 1}",
                                fontSize = 11.sp,
                                color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.7f)
                            )
                            val progress = ((chapter.index.toFloat() + (pageIdx.toFloat() / pages.size.coerceAtLeast(1))) / totalChapters * 100f).coerceIn(0f, 100f)
                            Text(
                                text = String.format(java.util.Locale.US, "%.1f%%", progress),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.8f)
                            )
                            Text(
                                text = "${pageIdx + 1} / ${pages.size}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
            }
            */
        }
    }
}

private sealed interface MeasuredPageBlock {
    val paragraphIndex: Int

    data class TextBlock(
        val text: String,
        override val paragraphIndex: Int,
        val fragmentIndex: Int
    ) : MeasuredPageBlock

    data class ImageBlock(
        val imageId: String,
        override val paragraphIndex: Int
    ) : MeasuredPageBlock
}

private data class MeasuredReaderPage(
    val blocks: List<MeasuredPageBlock>
) {
    val paragraphIndices: List<Int> = blocks
        .map(MeasuredPageBlock::paragraphIndex)
        .distinct()
}

private fun displayChapterTitle(title: String, chapterIndex: Int): String =
    if (title.matches(Regex("Раздел\\s+\\d+", RegexOption.IGNORE_CASE))) {
        "Глава ${chapterIndex + 1}"
    } else {
        title
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedChapterViewer(
    chapter: Chapter,
    parsedBook: ParsedBook?,
    initialParagraphIndex: Int,
    settings: ReaderSettings,
    fontFamily: FontFamily,
    totalChapters: Int,
    navigationOwner: Any,
    onToggleControls: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onParagraphVisible: (Int) -> Unit,
    onParagraphFragmentVisible: (paragraphIndex: Int, fragmentIndex: Int, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val visibleChapterTitle = remember(chapter.title, chapter.index) {
        displayChapterTitle(chapter.title, chapter.index)
    }

    BoxWithConstraints(modifier = modifier) {
        val contentWidthPx = with(density) {
            (maxWidth - settings.horizontalPaddingDp.dp * 2).roundToPx().coerceAtLeast(1)
        }
        // The body is the viewport between the subtle header and footer. Using
        // the real device constraints is what prevents half-empty pages across
        // different screen sizes, font sizes and line spacings.
        val contentHeightPx = with(density) {
            (maxHeight - 44.dp - 48.dp - 26.dp - 30.dp)
                .roundToPx()
                .coerceAtLeast(1)
        }
        val paragraphSpacingPx = with(density) { 10.dp.roundToPx() }
        val textStyle = TextStyle(
            fontSize = settings.fontSizeSp.sp,
            fontFamily = fontFamily,
            lineHeight = (settings.fontSizeSp * settings.lineSpacingMultiplier).sp
        )
        val titleStyle = TextStyle(
            fontSize = (settings.fontSizeSp + 3).sp,
            fontWeight = FontWeight.Bold,
            fontFamily = fontFamily,
            lineHeight = ((settings.fontSizeSp + 3) * settings.lineSpacingMultiplier).sp
        )
        val titleHeightPx = remember(visibleChapterTitle, titleStyle, contentWidthPx) {
            textMeasurer.measure(
                text = visibleChapterTitle,
                style = titleStyle,
                constraints = Constraints(maxWidth = contentWidthPx)
            ).size.height + paragraphSpacingPx
        }

        val pages = remember(
            chapter.index,
            chapter.paragraphs,
            contentWidthPx,
            contentHeightPx,
            titleHeightPx,
            textStyle,
            settings.isBionicReadingEnabled
        ) {
            paginateMeasuredChapter(
                paragraphs = chapter.paragraphs,
                textMeasurer = textMeasurer,
                textStyle = textStyle,
                contentWidthPx = contentWidthPx,
                contentHeightPx = contentHeightPx,
                firstPageTitleHeightPx = titleHeightPx,
                paragraphSpacingPx = paragraphSpacingPx,
                bionic = settings.isBionicReadingEnabled
            )
        }

        key(
            chapter.index,
            contentWidthPx,
            contentHeightPx,
            settings.fontSizeSp,
            settings.lineSpacingMultiplier,
            settings.fontFamily,
            settings.isBionicReadingEnabled
        ) {
            val initialPage = when {
                initialParagraphIndex == Int.MAX_VALUE -> pages.lastIndex
                else -> pages.indexOfFirst { initialParagraphIndex in it.paragraphIndices }
                    .takeIf { it >= 0 }
                    ?: 0
            }.coerceIn(0, pages.lastIndex.coerceAtLeast(0))

            val pagerState = rememberPagerState(
                initialPage = initialPage,
                pageCount = { pages.size }
            )
            val turnRequests = remember(chapter.index) {
                MutableSharedFlow<PageTurnDirection>(
                    extraBufferCapacity = 64,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            }
            var showPageJumpDialog by remember(chapter.index) { mutableStateOf(false) }
            var pageNumberInput by remember(chapter.index) { mutableStateOf("1") }

            LaunchedEffect(pagerState, pages) {
                // A burst at a chapter edge may leave several requests queued in
                // the old pager. Commit the chapter transition only once; the new
                // chapter creates a fresh collector and accepts turns immediately.
                var boundaryTransitionCommitted = false
                var requestedPage = pagerState.currentPage
                var pageAnimationJob: kotlinx.coroutines.Job? = null
                turnRequests.collect { direction ->
                    if (pageAnimationJob?.isActive != true) requestedPage = pagerState.currentPage
                    val target = if (direction == PageTurnDirection.NEXT) {
                        requestedPage + 1
                    } else {
                        requestedPage - 1
                    }
                    when {
                        target in pages.indices -> {
                            boundaryTransitionCommitted = false
                            requestedPage = target
                            // Keep a tactile page-turn animation, but make it
                            // interruptible: a rapid second tap cancels the old
                            // motion and immediately heads for the new target.
                            pageAnimationJob?.cancel()
                            pageAnimationJob = launch {
                                pagerState.animateScrollToPage(
                                    page = target,
                                    animationSpec = tween(
                                        durationMillis = 155,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                            }
                        }
                        !boundaryTransitionCommitted -> {
                            boundaryTransitionCommitted = true
                            pageAnimationJob?.cancel()
                            if (direction == PageTurnDirection.NEXT) onNextChapter()
                            else onPreviousChapter()
                        }
                    }
                }
            }

            DisposableEffect(settings.volumeKeyNavigation, pagerState, pages.size) {
                if (settings.volumeKeyNavigation) {
                    ReaderPageNavigation.register(navigationOwner) { direction ->
                        turnRequests.tryEmit(direction)
                    }
                }
                onDispose { ReaderPageNavigation.unregister(navigationOwner) }
            }

            LaunchedEffect(pagerState.currentPage, pages) {
                pages.getOrNull(pagerState.currentPage)?.blocks.orEmpty().forEach { block ->
                    when (block) {
                        is MeasuredPageBlock.TextBlock -> onParagraphFragmentVisible(
                            block.paragraphIndex,
                            block.fragmentIndex,
                            block.text
                        )
                        is MeasuredPageBlock.ImageBlock -> onParagraphVisible(block.paragraphIndex)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(turnRequests, pages.size) {
                        detectTapGestures { offset ->
                            when {
                                offset.x < size.width * 0.30f ->
                                    turnRequests.tryEmit(PageTurnDirection.PREVIOUS)
                                offset.x > size.width * 0.70f ->
                                    turnRequests.tryEmit(PageTurnDirection.NEXT)
                                else -> onToggleControls()
                            }
                        }
                    }
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    val pageOffset = (pagerState.currentPage - pageIndex) +
                        pagerState.currentPageOffsetFraction
                    val distance = kotlin.math.abs(pageOffset).coerceIn(0f, 1f)

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1f - 0.025f * distance
                                scaleY = 1f - 0.025f * distance
                                alpha = 1f - 0.15f * distance
                            }
                            .padding(horizontal = settings.horizontalPaddingDp.dp)
                            .padding(top = 44.dp, bottom = 48.dp)
                    ) {
                        Text(
                            text = visibleChapterTitle,
                            fontSize = 11.sp,
                            color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )

                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds(),
                                verticalArrangement = Arrangement.Top
                            ) {
                                if (pageIndex == 0) {
                                    Text(
                                        text = visibleChapterTitle,
                                        style = titleStyle,
                                        color = settings.theme.textComposeColor,
                                        modifier = Modifier.padding(bottom = 10.dp)
                                    )
                                }

                                page.blocks.forEach { block ->
                                    when (block) {
                                        is MeasuredPageBlock.ImageBlock -> {
                                            val imageBytes = parsedBook?.images?.get(block.imageId)
                                            val bitmap = remember(block.imageId, imageBytes) {
                                                imageBytes?.let {
                                                    runCatching {
                                                        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                                                            ?.asImageBitmap()
                                                    }.getOrNull()
                                                }
                                            }
                                            if (bitmap != null) {
                                                Image(
                                                    bitmap = bitmap,
                                                    contentDescription = "Иллюстрация книги",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f, fill = true)
                                                        .padding(vertical = 6.dp)
                                                        .clip(RoundedCornerShape(12.dp)),
                                                    contentScale = ContentScale.Fit
                                                )
                                            }
                                        }

                                        is MeasuredPageBlock.TextBlock -> {
                                            val annotated = remember(
                                                block.text,
                                                settings.isBionicReadingEnabled
                                            ) {
                                                if (settings.isBionicReadingEnabled) {
                                                    BionicReadingHelper.transform(block.text)
                                                } else null
                                            }
                                            Text(
                                                text = annotated ?: AnnotatedString(block.text),
                                                style = textStyle,
                                                color = settings.theme.textComposeColor,
                                                // Start alignment avoids the enormous word gaps
                                                // produced by justification on short final lines.
                                                textAlign = TextAlign.Start,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Глава ${chapter.index + 1} из $totalChapters",
                                fontSize = 11.sp,
                                color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.72f)
                            )
                            val progress = ((chapter.index.toFloat() +
                                pageIndex.toFloat() / pages.size.coerceAtLeast(1)) /
                                totalChapters * 100f).coerceIn(0f, 100f)
                            Text(
                                text = String.format(java.util.Locale.US, "%.1f%%", progress),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = settings.theme.secondaryTextComposeColor.copy(alpha = 0.82f)
                            )
                            Text(
                                text = "Стр. ${pageIndex + 1} из ${pages.size}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = settings.theme.secondaryTextComposeColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                    pageNumberInput = (pageIndex + 1).toString()
                                    showPageJumpDialog = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (showPageJumpDialog) {
                val requestedPage = pageNumberInput.toIntOrNull()
                val isValidPage = requestedPage != null && requestedPage in 1..pages.size
                AlertDialog(
                    onDismissRequest = { showPageJumpDialog = false },
                    title = { Text("Перейти к странице") },
                    text = {
                        OutlinedTextField(
                            value = pageNumberInput,
                            onValueChange = { value ->
                                pageNumberInput = value.filter(Char::isDigit).take(6)
                            },
                            label = { Text("Номер от 1 до ${pages.size}") },
                            supportingText = {
                                Text("Страницы текущей главы: ${pages.size}")
                            },
                            isError = pageNumberInput.isNotEmpty() && !isValidPage,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = isValidPage,
                            onClick = {
                                showPageJumpDialog = false
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(
                                        page = requestedPage!! - 1,
                                        animationSpec = tween(
                                            durationMillis = 180,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                }
                            }
                        ) {
                            Text("Перейти")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPageJumpDialog = false }) {
                            Text("Отмена")
                        }
                    }
                )
            }
        }
    }
}

private fun paginateMeasuredChapter(
    paragraphs: List<String>,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    contentWidthPx: Int,
    contentHeightPx: Int,
    firstPageTitleHeightPx: Int,
    paragraphSpacingPx: Int,
    bionic: Boolean
): List<MeasuredReaderPage> {
    val pages = mutableListOf<MeasuredReaderPage>()
    var blocks = mutableListOf<MeasuredPageBlock>()
    var usedHeight = firstPageTitleHeightPx.coerceAtMost(contentHeightPx)

    fun measured(text: String) = textMeasurer.measure(
        text = if (bionic) BionicReadingHelper.transform(text) else AnnotatedString(text),
        style = textStyle,
        constraints = Constraints(maxWidth = contentWidthPx)
    )

    fun flushPage() {
        if (blocks.isNotEmpty()) {
            pages += MeasuredReaderPage(blocks.toList())
            blocks = mutableListOf()
            usedHeight = 0
        }
    }

    paragraphs.forEachIndexed { paragraphIndex, rawParagraph ->
        if (rawParagraph.isBlank()) {
            // FB2 often contains formatting-only <empty-line/> nodes. They have
            // no visual block, so reserving height for them creates mysteriously
            // half-empty pages in books with many such nodes.
            return@forEachIndexed
        }

        if (rawParagraph.startsWith("[IMG:") && rawParagraph.endsWith("]")) {
            val imageBlock = MeasuredPageBlock.ImageBlock(
                imageId = rawParagraph.substring(5, rawParagraph.length - 1),
                paragraphIndex = paragraphIndex
            )
            // If the preceding text occupies at most half a page, let the image
            // fill the remaining viewport instead of forcing a visibly sparse
            // text-only page. Dense text keeps a dedicated image page so the
            // illustration never becomes an unreadable thumbnail.
            if (blocks.isNotEmpty() && usedHeight <= contentHeightPx * 0.55f) {
                blocks += imageBlock
                flushPage()
            } else {
                flushPage()
                pages += MeasuredReaderPage(
                    listOf(imageBlock)
                )
                usedHeight = 0
            }
            return@forEachIndexed
        }

        var remaining = rawParagraph.trim()
        var fragmentIndex = 0
        while (remaining.isNotEmpty()) {
            val available = (contentHeightPx - usedHeight).coerceAtLeast(0)
            val layout = measured(remaining)
            val completeHeight = layout.size.height + paragraphSpacingPx

            if (completeHeight <= available) {
                blocks += MeasuredPageBlock.TextBlock(remaining, paragraphIndex, fragmentIndex)
                usedHeight += completeHeight
                remaining = ""
                continue
            }

            val heightForText = (available - paragraphSpacingPx).coerceAtLeast(0)
            var fittingLines = 0
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= heightForText.toFloat() + 0.5f) fittingLines++
                else break
            }

            // Do not strand a single line at the foot of an otherwise populated
            // page; it reads better and still keeps pages densely filled.
            if ((fittingLines == 0 || (fittingLines == 1 && blocks.isNotEmpty())) &&
                blocks.isNotEmpty()
            ) {
                flushPage()
                continue
            }
            if (fittingLines == 0) fittingLines = 1

            var splitOffset = layout
                .getLineEnd((fittingLines - 1).coerceAtMost(layout.lineCount - 1), visibleEnd = true)
                .coerceIn(1, remaining.length)

            if (splitOffset < remaining.length) {
                val searchEnd = (splitOffset - 1).coerceAtLeast(0)
                val boundary = maxOf(
                    remaining.lastIndexOf(' ', searchEnd),
                    remaining.lastIndexOf('\n', searchEnd),
                    remaining.lastIndexOf('\t', searchEnd)
                )
                if (boundary >= splitOffset / 2) splitOffset = boundary + 1
            }

            val chunk = remaining.substring(0, splitOffset).trimEnd()
            if (chunk.isNotEmpty()) {
                blocks += MeasuredPageBlock.TextBlock(chunk, paragraphIndex, fragmentIndex)
            }
            fragmentIndex++
            remaining = remaining.substring(splitOffset).trimStart()
            flushPage()
        }
    }

    flushPage()
    return pages.ifEmpty {
        listOf(
            MeasuredReaderPage(
                listOf(MeasuredPageBlock.TextBlock("Конец главы", 0, 0))
            )
        )
    }
}

@Composable
fun PdfPageViewer(
    filePath: String,
    pageNumber: Int,
    settings: ReaderSettings,
    onToggleControls: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pageBitmap by remember(filePath, pageNumber) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(filePath, pageNumber) { mutableStateOf(true) }

    LaunchedEffect(filePath, pageNumber) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    if (pageNumber in 0 until renderer.pageCount) {
                        val page = renderer.openPage(pageNumber)
                        val width = (page.width * 2.2).toInt().coerceAtLeast(600)
                        val height = (page.height * 2.2).toInt().coerceAtLeast(800)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        pageBitmap = bitmap
                    }
                    renderer.close()
                    pfd.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(settings.theme.bgComposeColor)
            .padding(horizontal = 8.dp, vertical = 50.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val screenWidth = size.width
                    val x = offset.x
                    when {
                        x < screenWidth * 0.30f -> onPreviousPage()
                        x > screenWidth * 0.70f -> onNextPage()
                        else -> onToggleControls()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = settings.theme.textComposeColor)
        } else if (pageBitmap != null) {
            Image(
                bitmap = pageBitmap!!.asImageBitmap(),
                contentDescription = "PDF Страница",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = "Не удалось отобразить страницу PDF",
                color = settings.theme.textComposeColor
            )
        }
    }
}
