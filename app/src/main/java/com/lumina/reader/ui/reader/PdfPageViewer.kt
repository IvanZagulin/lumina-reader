package com.lumina.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumina.reader.core.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Longest side of a rendered PDF page bitmap. */
internal const val MAX_PDF_RENDER_SIDE = 4096

internal data class PdfRenderSize(
    val width: Int,
    val height: Int
)

/**
 * Bitmap size for a PDF page: twice the viewport width so pinch-zoom stays
 * sharp, at most [MAX_PDF_RENDER_SIDE] on the long side and at most
 * [maxBitmapBytes] of ARGB pixels.
 */
internal fun pdfRenderSize(
    pageWidth: Int,
    pageHeight: Int,
    viewportWidthPx: Int,
    maxBitmapBytes: Long
): PdfRenderSize {
    val safePageWidth = pageWidth.coerceAtLeast(1).toDouble()
    val safePageHeight = pageHeight.coerceAtLeast(1).toDouble()
    var width = viewportWidthPx.coerceAtLeast(1) * 2.0
    var height = width * safePageHeight / safePageWidth
    val longSide = max(width, height)
    if (longSide > MAX_PDF_RENDER_SIDE) {
        val scale = MAX_PDF_RENDER_SIDE / longSide
        width *= scale
        height *= scale
    }
    var widthPx = width.roundToInt().coerceIn(1, MAX_PDF_RENDER_SIDE)
    var heightPx = height.roundToInt().coerceIn(1, MAX_PDF_RENDER_SIDE)
    val bytes = widthPx.toLong() * heightPx * 4L
    if (maxBitmapBytes > 0 && bytes > maxBitmapBytes) {
        // Rounding down keeps the bitmap inside the budget.
        val scale = sqrt(maxBitmapBytes.toDouble() / bytes)
        widthPx = (widthPx * scale).toInt().coerceAtLeast(1)
        heightPx = (heightPx * scale).toInt().coerceAtLeast(1)
    }
    return PdfRenderSize(widthPx, heightPx)
}

/**
 * One open PDF for the whole reading session. [PdfRenderer] allows a single
 * open page at a time, so rendering and closing are serialised by a mutex.
 * Rendered pages are cached by size in bytes; neighbours are prefetched.
 */
internal class PdfDocumentRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    private val mutex = Mutex()
    private var closed = false
    private val maxBitmapBytes: Long = (Runtime.getRuntime().maxMemory() / 10).coerceAtLeast(8L * 1024 * 1024)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(16L * 1024 * 1024, 160L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    val pageCount: Int = renderer.pageCount

    private fun key(pageIndex: Int, viewportWidthPx: Int) = "$pageIndex@$viewportWidthPx"

    fun cached(pageIndex: Int, viewportWidthPx: Int): Bitmap? = cache.get(key(pageIndex, viewportWidthPx))

    /** Renders a page (blocking, call from a background dispatcher). Null if it cannot. */
    suspend fun render(pageIndex: Int, viewportWidthPx: Int): Bitmap? {
        if (pageIndex !in 0 until pageCount) return null
        cache.get(key(pageIndex, viewportWidthPx))?.let { return it }
        return mutex.withLock {
            if (closed) {
                null
            } else {
                cache.get(key(pageIndex, viewportWidthPx)) ?: renderLocked(pageIndex, viewportWidthPx)
            }
        }
    }

    private fun renderLocked(pageIndex: Int, viewportWidthPx: Int): Bitmap? {
        var page: PdfRenderer.Page? = null
        return try {
            val openPage = renderer.openPage(pageIndex)
            page = openPage
            val size = pdfRenderSize(openPage.width, openPage.height, viewportWidthPx, maxBitmapBytes)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            openPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(key(pageIndex, viewportWidthPx), bitmap)
            bitmap
        } catch (error: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (error: Exception) {
            null
        } finally {
            try {
                page?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            if (!closed) {
                closed = true
                cache.evictAll()
                try {
                    renderer.close()
                } catch (ignored: Exception) {
                }
                try {
                    descriptor.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    companion object {
        /** Opens [file]; null if it is not a readable PDF. Blocking. */
        fun open(file: File): PdfDocumentRenderer? {
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (error: Exception) {
                return null
            }
            return try {
                PdfDocumentRenderer(descriptor, PdfRenderer(descriptor))
            } catch (error: Exception) {
                try {
                    descriptor.close()
                } catch (ignored: Exception) {
                }
                null
            }
        }
    }
}

private sealed interface PdfPageState {
    data object Loading : PdfPageState
    data object Failed : PdfPageState
    data class Ready(val bitmap: Bitmap) : PdfPageState
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

    val cachedBitmap = document?.cached(pageIndex, viewportWidthPx)
    val initialState: PdfPageState =
        if (cachedBitmap != null) PdfPageState.Ready(cachedBitmap) else PdfPageState.Loading
    val state by produceState(initialState, document, pageIndex, viewportWidthPx) {
        val pdf = document
        if (pdf == null) {
            value = PdfPageState.Failed
            return@produceState
        }
        if (value !is PdfPageState.Ready) {
            val bitmap = withContext(Dispatchers.IO) { pdf.render(pageIndex, viewportWidthPx) }
            value = if (bitmap != null) PdfPageState.Ready(bitmap) else PdfPageState.Failed
        }
        // Prefetch the neighbours so the next page turn is instant.
        withContext(Dispatchers.IO) {
            pdf.render(pageIndex + 1, viewportWidthPx)
            pdf.render(pageIndex - 1, viewportWidthPx)
        }
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
                val image = remember(current.bitmap) { current.bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
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
