package com.lumina.reader.ui.reader.pageturn

import android.app.ActivityManager
import android.content.Context
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lumina.reader.core.model.PageTurnAnimation
import com.lumina.reader.ui.theme.LuminaMotion
import kotlin.math.abs

/**
 * Offset of pager page [page] from the viewport (§9): `o > 0` lies left of
 * the viewport (outgoing or previous), `o < 0` right of it (incoming or next).
 * Read it only inside graphicsLayer or draw lambdas.
 */
internal fun PagerState.pageOffsetOf(page: Int): Float =
    (currentPage - page) + currentPageOffsetFraction

/**
 * The animation actually used: the curl needs a capable device and is not
 * used while read-aloud turns the pages.
 */
internal fun effectivePageTurn(
    selected: PageTurnAnimation,
    curlSupported: Boolean,
    readAloudDriving: Boolean
): PageTurnAnimation = when {
    selected == PageTurnAnimation.CURL && (!curlSupported || readAloudDriving) -> PageTurnAnimation.SLIDE
    else -> selected
}

/** The curl is off on low-RAM devices (§4.2 PageTurnStyleCard). */
internal fun isCurlSupported(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return manager?.isLowRamDevice != true
}

/**
 * Animation of a tap or volume-key turn; null means an instant jump
 * (NONE, the curl's own path, or reduced motion).
 */
internal fun tapTurnSpec(style: PageTurnAnimation, reducedMotion: Boolean): AnimationSpec<Float>? = when {
    reducedMotion -> null
    style == PageTurnAnimation.SLIDE -> tween(
        durationMillis = LuminaMotion.PageTurnTapMs,
        easing = LuminaMotion.EmphasizedDecelerate
    )
    style == PageTurnAnimation.FLIP -> tween(durationMillis = 320, easing = FastOutSlowInEasing)
    else -> null
}

/** Alpha of a page in curl mode: the curled page and the page under it, otherwise only the current page. */
internal fun curlPageAlpha(page: Int, offset: Float, activePage: Int): Float =
    if (activePage >= 0) {
        if (page == activePage || page == activePage + 1) 1f else 0f
    } else {
        if (abs(offset) < 0.5f) 1f else 0f
    }

/**
 * §9.1 «Сдвиг»: the page on top slides away fully while the page beneath
 * moves 30 % (parallax) and brightens; the moving edge casts a 16dp shadow.
 * Lower indices draw on top, which gives a correct stack in both directions.
 */
internal fun Modifier.slidePage(
    pagerState: PagerState,
    page: Int,
    pageColor: Color,
    isDarkTheme: Boolean
): Modifier = this
    .zIndex(-page.toFloat())
    .graphicsLayer {
        val o = pagerState.pageOffsetOf(page)
        if (o <= -0.999f || o >= 0.999f) {
            // Off screen: left where the pager put it, so a hidden page never
            // lies over the visible one (it would catch touches and selection).
            translationX = 0f
            alpha = 0f
        } else if (o < 0f) {
            translationX = 0.7f * o * size.width
            alpha = 1f
        } else {
            translationX = 0f
            alpha = 1f
        }
    }
    .drawWithCache {
        val edge = 16.dp.toPx()
        val shadow = Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = if (isDarkTheme) 0.45f else 0.20f),
            1f to Color.Transparent,
            startX = size.width,
            endX = size.width + edge
        )
        val dim = if (isDarkTheme) 0.30f else 0.10f
        onDrawWithContent {
            drawRect(pageColor)
            drawContent()
            val o = pagerState.pageOffsetOf(page)
            if (o < 0f) {
                drawRect(Color.Black, alpha = (dim * -o).coerceIn(0f, 1f))
            } else if (o > 0.001f) {
                drawRect(shadow, topLeft = Offset(size.width, 0f), size = Size(edge, size.height))
            }
        }
    }

/**
 * §9.2 «3D-переворот»: every page is pinned in place; the outgoing page
 * swings around the spine (left edge) towards the reader and darkens, the
 * page beneath lightens as it is uncovered.
 */
internal fun Modifier.flipPage(
    pagerState: PagerState,
    page: Int,
    pageColor: Color
): Modifier = this
    .zIndex(-page.toFloat())
    .graphicsLayer {
        val o = pagerState.pageOffsetOf(page)
        if (o <= -0.999f || o >= 0.999f) {
            // Off screen and hidden; not pinned over the visible page.
            translationX = 0f
            rotationY = 0f
            alpha = 0f
        } else if (o > 0f) {
            translationX = o * size.width
            transformOrigin = TransformOrigin(0f, 0.5f)
            cameraDistance = 12f * density
            rotationY = LuminaMotion.HingeSign * 90f * o
            alpha = 1f
        } else {
            translationX = o * size.width
            rotationY = 0f
            alpha = 1f
        }
    }
    .flipShading(pagerState, page, pageColor)

private fun Modifier.flipShading(pagerState: PagerState, page: Int, pageColor: Color): Modifier =
    drawWithCache {
        onDrawWithContent {
            drawRect(pageColor)
            drawContent()
            val o = pagerState.pageOffsetOf(page)
            if (o > 0f) {
                drawRect(Color.Black, alpha = (0.4f * o).coerceIn(0f, 1f))
            } else if (o < 0f) {
                drawRect(Color.Black, alpha = (0.25f * -o).coerceIn(0f, 1f))
            }
        }
    }

/** §9.4 «Без анимации» (and the default drag of the pager): opaque pages in a plain row. */
internal fun Modifier.plainPage(page: Int, pageColor: Color): Modifier = this
    .zIndex(-page.toFloat())
    .drawWithCache {
        onDrawWithContent {
            drawRect(pageColor)
            drawContent()
        }
    }
