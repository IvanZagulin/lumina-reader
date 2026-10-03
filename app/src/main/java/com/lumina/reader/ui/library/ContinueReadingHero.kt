package com.lumina.reader.ui.library

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.reader.core.model.Book
import com.lumina.reader.core.model.BookFormat
import com.lumina.reader.ui.components.BookOnShelf
import com.lumina.reader.ui.components.ClothPalette
import com.lumina.reader.ui.components.paperGrainBrush
import com.lumina.reader.ui.components.rememberCoverColors
import com.lumina.reader.ui.components.rememberPaperGrain
import com.lumina.reader.ui.components.toShelfBookUi
import com.lumina.reader.ui.theme.LoraFamily
import com.lumina.reader.ui.theme.LuminaMotion
import com.lumina.reader.ui.theme.LuminaType
import com.lumina.reader.ui.transition.TransitionGeometry
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Meta line «42% · глава 7 из 18» (or «42% · глава 7» without a chapter count). */
fun heroMeta(book: Book): String {
    val percent = book.currentProgressPercent.toInt().coerceIn(0, 100)
    val position = book.currentChapterIndex + 1
    val unit = if (book.format == BookFormat.PDF) "стр." else "глава"
    return when {
        book.totalChapters > 1 -> "$percent% · $unit $position из ${book.totalChapters}"
        else -> "$percent%"
    }
}

/**
 * «Продолжить чтение» (spec §4.1): a 188 dp card in the cover's colours with a
 * blurred low-resolution cover glow, grain and a luminance-guarded scrim; the
 * 3D book overhangs the card top and is the transition slot `"hero"`.
 */
@Composable
fun ContinueReadingHero(
    pick: HeroPick,
    onOpen: (slotKey: String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val book = pick.book
    val shelfBook = remember(book) { book.toShelfBookUi() }
    val coverColors = rememberCoverColors(book.coverPath)
    val cloth = remember(book.title, book.author) { ClothPalette.forBook(book.title, book.author).color }
    val base = coverColors?.darkMuted ?: lerp(cloth, Color.Black, 0.35f)
    val vibrant = coverColors?.vibrant ?: cloth
    val scrimAlpha = ((base.luminance() - 0.12f) / 0.5f).coerceIn(0.25f, 0.6f)
    val grain = rememberPaperGrain()
    val context = LocalContext.current
    val glowRequest = remember(book.coverPath) {
        book.coverPath?.takeIf { it.isNotBlank() }?.let { path ->
            ImageRequest.Builder(context)
                .data(File(path))
                .size(24, 36)
                .allowHardware(Build.VERSION.SDK_INT >= 28)
                .build()
        }
    }
    val eyebrow = if (pick.resume) "ПРОДОЛЖИТЬ ЧТЕНИЕ" else "НАЧНИТЕ НОВУЮ КНИГУ"
    val description = "${if (pick.resume) "Продолжить чтение" else "Начать читать"}: «${book.title}», ${book.author}"
    val cardShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp)
            .height(188.dp)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(cardShape)
                .drawWithCache {
                    // 135° gradient from the muted cover colour to the vibrant one.
                    val angle = 135.0 * PI / 180.0
                    val half = (size.width + size.height) / 2f
                    val dx = cos(angle).toFloat() * half
                    val dy = sin(angle).toFloat() * half
                    val gradient = Brush.linearGradient(
                        listOf(base, vibrant.copy(alpha = 0.35f)),
                        start = Offset(size.width / 2f + dx, size.height / 2f - dy),
                        end = Offset(size.width / 2f - dx, size.height / 2f + dy)
                    )
                    onDrawBehind {
                        drawRect(base)
                        drawRect(gradient)
                    }
                }
                .clickable(role = Role.Button, onClickLabel = "Читать") { onOpen(TransitionGeometry.HERO_SLOT_KEY) }
                .semantics(mergeDescendants = true) { contentDescription = description }
        ) {
            if (glowRequest != null) {
                AsyncImage(
                    model = glowRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.45f,
                    modifier = Modifier
                        .matchParentSize()
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier)
                )
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val grainBrush = grain?.let { paperGrainBrush(it) }
                        val scrim = Brush.horizontalGradient(
                            0.3f to Color.Transparent,
                            1f to Color.Black.copy(alpha = scrimAlpha)
                        )
                        onDrawBehind {
                            if (grainBrush != null) drawRect(grainBrush)
                            drawRect(scrim)
                        }
                    }
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 144.dp, end = 20.dp, top = 18.dp, bottom = 16.dp)
                    .clearAndSetSemantics { }
            ) {
                Text(
                    text = eyebrow,
                    style = LuminaType.eyebrow,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = book.title,
                    fontFamily = LoraFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                HeroProgress(progress = shelfBook.progress)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = heroMeta(book),
                    style = LuminaType.tabular,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = { onOpen(TransitionGeometry.HERO_SLOT_KEY) },
                    shape = RoundedCornerShape(50),
                    color = Color.White,
                    contentColor = Color(0xFF2A211B),
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Читать", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        BookOnShelf(
            book = shelfBook,
            width = 104.dp,
            height = 156.dp,
            modifier = Modifier.offset(x = 20.dp, y = (-20).dp),
            slotKey = TransitionGeometry.HERO_SLOT_KEY,
            baseRotationY = -LuminaMotion.HingeSign * 12f,
            onClick = { onOpen(TransitionGeometry.HERO_SLOT_KEY) },
            onLongClick = onLongPress
        )
    }
}

/** A 4 dp rounded bar: white 25% track, white fill. */
@Composable
private fun HeroProgress(progress: Float) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .drawWithCache {
                val radius = CornerRadius(size.height / 2f)
                onDrawBehind {
                    drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = radius)
                    if (progress > 0f) {
                        drawRoundRect(
                            Color.White,
                            size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
                            cornerRadius = radius
                        )
                    }
                }
            }
    )
}
