package com.lumina.reader.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.lumina.reader.ui.components.toNetworkHeaders
import com.lumina.reader.ui.theme.LegacyM3Defaults
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaShape
import com.lumina.reader.ui.theme.rememberReducedMotion

/** The 8 cloth colours of generated covers and monograms (spec §4.3). */
val CatalogCloth = listOf(
    Color(0xFF6E1F2A), // Бордо
    Color(0xFF4E2A4F), // Слива
    Color(0xFF1F4D3A), // Изумруд
    Color(0xFF1E5A63), // Бирюза
    Color(0xFF23305E), // Индиго
    Color(0xFF2E2F33), // Графит
    Color(0xFFC08A3A), // Охра
    Color(0xFF8C3F25)  // Терракота
)
private const val OCHRE_INDEX = 6
private val Foil = Color(0xFFF0D69A)
private val Deboss = Color(0xFF2A1A0C)

fun clothColor(seed: String): Color = CatalogCloth[CatalogUi.clothIndex(seed)]
fun onClothColor(seed: String): Color = if (CatalogUi.clothIndex(seed) == OCHRE_INDEX) Deboss else Foil

/** True when the Material scheme is dark (works before and after LuminaReaderTheme provides its locals). */
@Composable
@ReadOnlyComposable
fun catalogIsDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Search pill / chip fill: `surfaceSunken`, or the closest Material role if the extended palette is not provided. */
@Composable
@ReadOnlyComposable
fun sunkenColor(): Color {
    val extended = Lumina.colors
    return if (extended.isDark == catalogIsDark()) extended.surfaceSunken else MaterialTheme.colorScheme.surfaceContainerHighest
}

/** Spec «LuminaCard»: surface, radius 20, 1dp outlineVariant hairline, no elevation. */
@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = LuminaShape.Card,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = color,
            border = border,
            tonalElevation = 0.dp,
            content = content
        )
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = color,
            border = border,
            tonalElevation = 0.dp,
            content = content
        )
    }
}

/** Small caps section label («МОИ КАТАЛОГИ»). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** 48dp search field in a sunken pill. */
@Composable
fun CatalogSearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            focusManager.clearFocus()
            onSearch()
        }),
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(LuminaShape.Pill)
                    .background(sunkenColor())
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.Close, contentDescription = "Очистить", tint = colors.onSurfaceVariant)
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    )
}

/**
 * A catalogue cover: the remote image (with the catalogue's credentials)
 * over a generated cloth cover, clipped to the book shape with a hinge groove.
 */
@Composable
fun CatalogCover(
    url: String?,
    authHeaders: Map<String, String>,
    title: String,
    author: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    large: Boolean = false
) {
    val seed = "${title.lowercase()}|${author.lowercase()}"
    val cloth = clothColor(seed)
    val ink = onClothColor(seed)
    val density = LocalDensity.current
    // The printed cover is image-like: its text does not follow the system font scale.
    val titleSize = with(density) { (width.value * 0.15f).coerceIn(9f, 20f).dp.toSp() }
    val authorSize = with(density) { (width.value * 0.085f).coerceIn(6f, 11f).dp.toSp() }
    Box(modifier = modifier.size(width = width, height = height)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(LuminaShape.Book)
                .background(cloth)
                .drawWithCache {
                    // Hinge groove along the spine; the brush is built once per size.
                    val groove = 10.dp.toPx().coerceAtMost(size.width / 4f)
                    val brush = Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.28f),
                        0.4f to Color.Black.copy(alpha = 0.10f),
                        0.6f to Color.White.copy(alpha = 0.14f),
                        1f to Color.Transparent,
                        endX = groove
                    )
                    val grooveSize = size.copy(width = groove)
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = brush, size = grooveSize)
                    }
                }
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = width * 0.12f, vertical = height * 0.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    color = ink,
                    fontSize = titleSize,
                    lineHeight = titleSize * 1.15f,
                    fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                if (author.isNotBlank()) {
                    Text(
                        text = author.uppercase(),
                        color = ink.copy(alpha = 0.85f),
                        fontSize = authorSize,
                        lineHeight = authorSize * 1.2f,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (url != null) {
                // Coil's platform context: the Android Context, as before; a placeholder on iOS.
                val context = LocalPlatformContext.current
                val request = remember(url, authHeaders, large) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .httpHeaders(authHeaders.toNetworkHeaders())
                        .size(if (large) 360 else 216, if (large) 540 else 324)
                        .crossfade(large)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (checked) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Indeterminate progress line; a static partial line under reduced motion (no infinite animation, spec §11). */
@Composable
fun LoadingLine(modifier: Modifier = Modifier) {
    if (rememberReducedMotion()) {
        LinearProgressIndicator(progress = { 0.3f }, modifier = modifier, gapSize = 0.dp, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

/** Indeterminate spinner; a static arc under reduced motion (spec §11). */
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier, strokeWidth: Dp = 2.dp) {
    if (rememberReducedMotion()) {
        CircularProgressIndicator(progress = { 0.25f }, modifier = modifier, strokeWidth = strokeWidth)
    } else {
        CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
    }
}

/** Inline card for empty and error states («Каталог не ответил: …» + «Повторить»). */
@Composable
fun CatalogMessageCard(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    HairlineCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isError) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = title ?: if (isError) "Каталог не ответил" else "Здесь пока пусто",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onAction,
                    shape = LuminaShape.Pill,
                    colors = LegacyM3Defaults.outlinedButtonColors(),
                    border = LegacyM3Defaults.outlinedButtonBorder()
                ) { Text(actionLabel) }
            }
        }
    }
}

/** Static placeholder rows while the first page loads (no shimmer: nothing animates). */
@Composable
fun CatalogSkeleton(modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        repeat(4) {
            Row {
                Box(
                    Modifier
                        .size(72.dp, 108.dp)
                        .clip(LuminaShape.Book)
                        .background(fill)
                )
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(fill)
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.45f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(fill)
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.9f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(fill)
                    )
                }
            }
        }
    }
}
