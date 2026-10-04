package com.lumina.reader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.ReaderSettings

// The document itself (PdfDocumentRenderer, openPdfDocument, pdfRenderSize)
// lives in :sharedUi PdfPlatform: PdfRenderer on Android, CoreGraphics on iOS.

private sealed interface PdfPageState {
    data object Loading : PdfPageState
    data object Failed : PdfPageState
    data class Ready(val image: ImageBitmap) : PdfPageState
}

private const val PDF_MAX_ZOOM = 5f
private const val PDF_DOUBLE_TAP_ZOOM = 2.5f

/** Turns a white page dark for dark reading themes. Created on first use. */
private val InvertColorsFilter: ColorFilter by lazy {
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

private fun clampPan(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

/**
 * A PDF page with pinch-zoom and pan. Double tap zooms in or back out; tap
 * zones turn pages only while the page is not zoomed.
 */
@Composable
internal fun PdfPageViewer(
    document: PdfDocumentRenderer?,
    pageIndex: Int,
    settings: ReaderSettings,
    onToggleControls: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val latestToggle by rememberUpdatedState(onToggleControls)
    val latestNext by rememberUpdatedState(onNextPage)
    val latestPrevious by rememberUpdatedState(onPreviousPage)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(settings.theme.bgComposeColor)
    ) {
        // produceState and the zoom state belong to one page: a new page starts
        // from its cached bitmap (or a spinner) and an unzoomed view.
        val viewportWidthPx = constraints.maxWidth.coerceAtLeast(1)
        key(pageIndex, viewportWidthPx) {
            PdfPageContent(
                document = document,
                pageIndex = pageIndex,
                viewportWidthPx = viewportWidthPx,
                settings = settings,
                onToggleControls = { latestToggle() },
                onNextPage = { latestNext() },
                onPreviousPage = { latestPrevious() }
            )
        }
    }
}

@Composable
private fun PdfPageContent(
    document: PdfDocumentRenderer?,
    pageIndex: Int,
    viewportWidthPx: Int,
    settings: ReaderSettings,
    onToggleControls: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit
) {
    val latestToggle by rememberUpdatedState(onToggleControls)
    val latestNext by rememberUpdatedState(onNextPage)
    val latestPrevious by rememberUpdatedState(onPreviousPage)
    val invertColors = settings.theme.isDark
    val tapZonesInverted = settings.tapZonesInverted

    val cachedImage = document?.cached(pageIndex, viewportWidthPx)
    val initialState: PdfPageState =
        if (cachedImage != null) PdfPageState.Ready(cachedImage) else PdfPageState.Loading
    val state by produceState(initialState, document, pageIndex, viewportWidthPx) {
        val pdf = document
        if (pdf == null) {
            value = PdfPageState.Failed
            return@produceState
        }
        // render is main-safe: each platform draws on its own background threads.
        if (value !is PdfPageState.Ready) {
            val image = pdf.render(pageIndex, viewportWidthPx)
            value = if (image != null) PdfPageState.Ready(image) else PdfPageState.Failed
        }
        // Prefetch the neighbours so the next page turn is instant.
        pdf.render(pageIndex + 1, viewportWidthPx)
        pdf.render(pageIndex - 1, viewportWidthPx)
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 50.dp)
            .pointerInput(pageIndex) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    val newScale = (scale * zoomChange).coerceIn(1f, PDF_MAX_ZOOM)
                    pan = if (newScale <= 1f) {
                        Offset.Zero
                    } else {
                        clampPan(pan + panChange, newScale, size)
                    }
                    scale = newScale
                }
            }
            .pointerInput(pageIndex, tapZonesInverted) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            pan = Offset.Zero
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val target = (tap - center) * (1f - PDF_DOUBLE_TAP_ZOOM)
                            scale = PDF_DOUBLE_TAP_ZOOM
                            pan = clampPan(target, PDF_DOUBLE_TAP_ZOOM, size)
                        }
                    },
                    onTap = { tap ->
                        if (scale > 1.01f) {
                            latestToggle()
                        } else {
                            val backZone = tap.x < size.width * 0.30f
                            val forwardZone = tap.x > size.width * 0.70f
                            when {
                                backZone -> if (tapZonesInverted) latestNext() else latestPrevious()
                                forwardZone -> if (tapZonesInverted) latestPrevious() else latestNext()
                                else -> latestToggle()
                            }
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when (val current = state) {
            PdfPageState.Loading -> CircularProgressIndicator(color = settings.theme.textComposeColor)
            PdfPageState.Failed -> Text(
                text = "Не удалось отобразить страницу PDF",
                color = settings.theme.textComposeColor
            )
            is PdfPageState.Ready -> {
                Image(
                    bitmap = current.image,
                    contentDescription = "Страница PDF ${pageIndex + 1}",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        }
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                    colorFilter = if (invertColors) InvertColorsFilter else null
                )
            }
        }
    }
}
