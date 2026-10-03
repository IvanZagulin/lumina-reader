package com.lumina.reader.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.ui.theme.LuminaShape
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What every cover surface needs to draw a book (spec §4.1). [image] is a
 * `File`, a remote URL or null (then a [GeneratedCover] is drawn).
 */
@Immutable
data class BookCoverModel(
    /** Null for catalogue (OPDS) entries. */
    val bookId: Long?,
    /** `"book:<id>"` or `"opds:<entryId>"`; also the Coil memory-cache key. */
    val key: String,
    val title: String,
    val author: String,
    val image: Any?,
    /** Page-block thickness of the shelf book, see [bookThicknessDp]. */
    val thicknessDp: Float = 3f
)

/** `(fileSizeBytes / 400 000)` clamped to 2..6 dp; PDFs are always 4 dp. */
fun bookThicknessDp(fileSizeBytes: Long, format: BookFormat): Float =
    if (format == BookFormat.PDF) 4f else (fileSizeBytes / 400_000f).coerceIn(2f, 6f)

fun Book.toCoverModel(): BookCoverModel = BookCoverModel(
    bookId = id,
    key = "book:$id",
    title = title,
    author = author,
    image = coverPath?.takeIf { it.isNotBlank() }?.let { File(it) },
    thicknessDp = bookThicknessDp(fileSizeBytes, format)
)

/**
 * The one Coil request shape per surface (spec §4.1). Shelf requests decode at
 * 300×450 under the shared memory key; `large` decodes 720×1080 and shows the
 * shelf bitmap as its placeholder, so the transition starts sharp at once.
 * Hardware bitmaps only from API 28: below it `GraphicsLayer.toImageBitmap()`
 * renders in software and cannot draw them.
 */
fun coverImageRequest(context: Context, model: BookCoverModel, large: Boolean = false): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(model.image)
        .allowHardware(Build.VERSION.SDK_INT >= 28)
    return if (large) {
        builder
            .size(720, 1080)
            .placeholderMemoryCacheKey(shelfCacheKey(model))
            .crossfade(false)
            .build()
    } else {
        builder
            .size(300, 450)
            .memoryCacheKey(shelfCacheKey(model))
            .crossfade(false)
            .build()
    }
}

private fun shelfCacheKey(model: BookCoverModel): String = "cover:${model.key}"

/**
 * A flat cover clipped to [LuminaShape.Book]: the image, or a [GeneratedCover]
 * when there is none or it fails to load, with the printed-book overlay (hinge
 * groove, gloss, hairline edge) on top. Decorative: the caller describes the
 * book for accessibility.
 */
@Composable
fun BookCover(
    model: BookCoverModel,
    width: Dp,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val context = LocalContext.current
    val request = remember(model.key, model.image, large) {
        model.image?.let { coverImageRequest(context, model, large) }
    }
    var failed by remember(model.key, model.image) { mutableStateOf(false) }
    val cloth = remember(model.title, model.author) { ClothPalette.forBook(model.title, model.author).color }

    Box(
        modifier = modifier
            .clip(LuminaShape.Book)
            .background(cloth)
            .coverOverlay()
    ) {
        if (request == null || failed) {
            GeneratedCover(
                title = model.title,
                author = model.author,
                widthDp = width,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true }
            )
        }
    }
}

/**
 * Hinge groove near the spine, a 115° gloss band and a 1px dark edge, drawn
 * after the content (spec §4.1).
 */
fun Modifier.coverOverlay(): Modifier = drawWithCache {
    val groove = 10.dp.toPx()
    val grooveBrush = Brush.horizontalGradient(
        0f to Color.Black.copy(alpha = 0.28f),
        0.4f to Color.Black.copy(alpha = 0.10f),
        0.6f to Color.White.copy(alpha = 0.14f),
        1f to Color.Transparent,
        startX = 0f,
        endX = groove
    )
    // Gloss band at 115°: direction (cos, sin) of the angle, measured in screen space.
    val angle = 115.0 * PI / 180.0
    val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
    val half = (size.width + size.height) / 2f
    val start = Offset(size.width / 2f - dir.x * half, size.height / 2f - dir.y * half)
    val end = Offset(size.width / 2f + dir.x * half, size.height / 2f + dir.y * half)
    val gloss = Brush.linearGradient(
        0f to Color.Transparent,
        0.45f to Color.White.copy(alpha = 0.10f),
        0.6f to Color.Transparent,
        1f to Color.Transparent,
        start = start,
        end = end
    )
    val edge = Stroke(width = 1f)
    onDrawWithContent {
        drawContent()
        drawRect(grooveBrush, size = Size(groove, size.height))
        drawRect(gloss)
        drawRect(Color.Black.copy(alpha = 0.12f), style = edge)
    }
}
