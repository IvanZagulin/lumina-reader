package com.lumina.reader.ui.reader.pageturn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.zIndex
import com.lumina.reader.ui.theme.LuminaMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * State of the «Загиб» page turn (§9.3). One instance per chapter pager. The
 * pager itself stays in charge of pages: a committed curl ends with
 * `scrollToPage`, so settled-page reporting, chapter boundaries and progress
 * work exactly as for the other styles.
 *
 * Everything the drawing reads is snapshot state read in the draw phase only.
 */
@Stable
internal class CurlState {
    /** Pager page drawn curled, or -1. */
    var activePage by mutableIntStateOf(-1)
        private set

    /** True for a forward curl (the current page lifts), false when the previous page uncurls. */
    var forward by mutableStateOf(true)
        private set

    var corner: CurlCorner = CurlCorner.BOTTOM
        private set

    /** Page size, kept up to date by the pager. */
    var pageWidth = 0f
    var pageHeight = 0f

    private var dragTouch by mutableStateOf(Offset.Zero)
    private var animating by mutableStateOf(false)
    private val release = Animatable(Offset.Zero, Offset.VectorConverter)
    private val scripted = Animatable(0f)
    private val pathPoint = FloatArray(2)

    /** Geometry and drawing caches; touched only while drawing on the main thread. */
    val geometry = CurlGeometry()
    val painter = CurlPainter()

    val isActive: Boolean get() = activePage >= 0

    /** The touch point P for drawing. */
    fun touch(): Offset = if (animating) release.value else dragTouch

    fun startDrag(page: Int, forward: Boolean, corner: CurlCorner, touch: Offset) {
        this.corner = corner
        this.forward = forward
        animating = false
        dragTouch = touch
        activePage = page
    }

    fun dragTo(touch: Offset) {
        dragTouch = touch
    }

    /**
     * Finishes a drag: commits with [LuminaMotion.pageRelease] towards the
     * turned-over position (or back flat for a backward curl), otherwise
     * springs back. [commit] receives the page to show.
     */
    suspend fun release(velocityX: Float, commit: suspend (page: Int) -> Unit) {
        val width = pageWidth
        val start = dragTouch
        val cornerY = curlCornerY(corner, pageHeight, start.y)
        val commits = shouldCommitCurl(forward, start.x, width, velocityX)
        val turnedOver = Offset(-width, cornerY)
        val flat = Offset(width, cornerY)
        val target = when {
            forward && commits -> turnedOver
            forward -> flat
            commits -> flat
            else -> turnedOver
        }
        try {
            release.snapTo(start)
            animating = true
            release.animateTo(
                targetValue = target,
                animationSpec = if (commits) LuminaMotion.pageRelease() else spring(dampingRatio = 1f, stiffness = 400f)
            )
            if (commits) commit(if (forward) activePage + 1 else activePage)
        } finally {
            reset()
        }
    }

    /**
     * A tap or volume-key turn: the bottom corner travels along a quadratic
     * Bézier for [LuminaMotion.CurlTapMs] (§9.3), then [commit] shows the page.
     */
    suspend fun scriptedTurn(currentPage: Int, forward: Boolean, commit: suspend (page: Int) -> Unit) {
        val width = pageWidth
        val height = pageHeight
        if (width <= 0f || height <= 0f) {
            commit(if (forward) currentPage + 1 else currentPage - 1)
            return
        }
        val page = if (forward) currentPage else currentPage - 1
        curlTapPath(if (forward) 0f else 1f, width, height, height, pathPoint)
        startDrag(page, forward, CurlCorner.BOTTOM, Offset(pathPoint[0], pathPoint[1]))
        try {
            scripted.snapTo(if (forward) 0f else 1f)
            scripted.animateTo(
                targetValue = if (forward) 1f else 0f,
                animationSpec = tween(durationMillis = LuminaMotion.CurlTapMs, easing = LuminaMotion.Emphasized)
            ) {
                curlTapPath(value, width, height, height, pathPoint)
                dragTouch = Offset(pathPoint[0], pathPoint[1])
            }
            commit(if (forward) currentPage + 1 else currentPage - 1)
        } finally {
            reset()
        }
    }

    fun reset() {
        animating = false
        activePage = -1
    }
}

/** Paths, matrix and brushes of the curl, reused every frame. */
internal class CurlPainter {
    val frontPath = Path()
    val flapPath = Path()
    val reflectedPath = Path()
    val matrixValues = FloatArray(16)
    val matrix = Matrix(matrixValues)

    private var density = -1f
    var castWidth = 0f
        private set
    var shadeWidth = 0f
        private set
    var bendWidth = 0f
        private set
    var dropOffset = 0f
        private set
    lateinit var castBrush: Brush
        private set
    lateinit var flapShadeBrush: Brush
        private set
    lateinit var bendBrush: Brush
        private set

    fun ensure(scope: DrawScope) {
        if (scope.density == density) return
        density = scope.density
        castWidth = 24f * density
        shadeWidth = 30f * density
        bendWidth = 16f * density
        dropOffset = 3f * density
        // In the fold's frame: +x points into the flap (n), -x into the front.
        castBrush = Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.25f),
            1f to Color.Transparent,
            startX = 0f,
            endX = castWidth
        )
        flapShadeBrush = Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0.12f),
            0.5f to Color.Black.copy(alpha = 0.10f),
            1f to Color.Transparent,
            startX = 0f,
            endX = -shadeWidth
        )
        bendBrush = Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.08f),
            startX = -bendWidth,
            endX = 0f
        )
    }

    fun fill(path: Path, polygon: CurlPolygon) {
        path.rewind()
        if (polygon.size < 3) return
        path.moveTo(polygon.xs[0], polygon.ys[0])
        for (i in 1 until polygon.size) path.lineTo(polygon.xs[i], polygon.ys[i])
        path.close()
    }
}

/**
 * Draws the curled page from its recorded [layer] (§9.3 draw order): the
 * flat part, shadows on the page underneath, the mirrored back of the flap
 * with a paper tint, and the shading along the fold.
 */
internal fun DrawScope.drawCurl(layer: GraphicsLayer, curl: CurlState, pageColor: Color) {
    val g = curl.geometry
    val painter = curl.painter
    painter.ensure(this)
    painter.fill(painter.frontPath, g.front)
    painter.fill(painter.flapPath, g.flap)
    painter.fill(painter.reflectedPath, g.reflectedFlap)
    val strength = g.shadowStrength()
    val angle = (atan2(g.normalY, g.normalX) * 180.0 / PI).toFloat()
    val reach = hypot(size.width, size.height)

    // 1. The part of the page still lying flat.
    clipPath(painter.frontPath) {
        drawLayer(layer)
        // 6. Bend shading on the front, close to the fold.
        withTransform({
            translate(g.midX, g.midY)
            rotate(angle, Offset.Zero)
        }) {
            drawRect(
                brush = painter.bendBrush,
                topLeft = Offset(-painter.bendWidth, -reach),
                size = Size(painter.bendWidth, 2f * reach),
                alpha = strength
            )
        }
    }

    // 2. Shadow cast on the next page, where the flap has lifted off.
    clipPath(painter.flapPath) {
        withTransform({
            translate(g.midX, g.midY)
            rotate(angle, Offset.Zero)
        }) {
            drawRect(
                brush = painter.castBrush,
                topLeft = Offset(0f, -reach),
                size = Size(painter.castWidth, 2f * reach),
                alpha = strength
            )
        }
    }

    // 3. A soft drop shadow under the flap.
    translate(-g.normalX * painter.dropOffset, -g.normalY * painter.dropOffset) {
        drawPath(painter.reflectedPath, Color.Black, alpha = 0.12f * strength)
    }

    // 4. The back of the flap: the page mirrored across the fold, seen through thin paper.
    g.writeReflectionMatrix(painter.matrixValues)
    withTransform({ transform(painter.matrix) }) {
        clipPath(painter.flapPath) {
            drawLayer(layer)
            drawRect(pageColor, alpha = 0.88f)
        }
    }

    // 5. Shading of the flap: light at the fold, a little darker just behind it.
    clipPath(painter.reflectedPath) {
        withTransform({
            translate(g.midX, g.midY)
            rotate(angle, Offset.Zero)
        }) {
            drawRect(
                brush = painter.flapShadeBrush,
                topLeft = Offset(-painter.shadeWidth, -reach),
                size = Size(painter.shadeWidth, 2f * reach)
            )
        }
    }

    // 7. Hardware path clipping is aliased on older devices: smooth the fold.
    if (g.hasFold) {
        drawLine(
            color = pageColor,
            start = Offset(g.foldX0, g.foldY0),
            end = Offset(g.foldX1, g.foldY1),
            strokeWidth = 1f,
            alpha = 0.6f
        )
    }
}

/**
 * One page of the pager in curl mode: pinned in place, shown only when it
 * is the current page or part of an active curl, drawn through [layer] while
 * it is curled.
 */
internal fun Modifier.curlPage(
    pagerState: PagerState,
    page: Int,
    curl: CurlState,
    layer: GraphicsLayer,
    pageColor: Color
): Modifier = this
    .zIndex(-page.toFloat())
    .graphicsLayer {
        val o = pagerState.pageOffsetOf(page)
        val shown = curlPageAlpha(page, o, curl.activePage)
        // Only shown pages are pinned in place; hidden ones stay off screen
        // where they cannot catch touches meant for the visible page.
        translationX = if (shown > 0f) o * size.width else 0f
        alpha = shown
    }
    .drawWithContent {
        if (curl.activePage != page) {
            drawRect(pageColor)
            drawContent()
        } else {
            val touch = curl.touch()
            val cornerY = curlCornerY(curl.corner, size.height, touch.y)
            curl.geometry.update(size.width, size.height, size.width, cornerY, touch.x, touch.y)
            if (curl.geometry.isFlat) {
                drawRect(pageColor)
                drawContent()
            } else {
                layer.record {
                    drawRect(pageColor)
                    this@drawWithContent.drawContent()
                }
                drawCurl(layer, curl, pageColor)
            }
        }
    }

/**
 * Drags from the outer quarter of the page curl it (§9.3): from the right
 * edge the current page lifts, from the left edge the previous one comes
 * back. Long presses (text selection) and taps are left to the other
 * detectors: a gesture becomes a curl only after the touch slop.
 */
internal fun Modifier.curlDragGestures(
    curl: CurlState,
    pagerState: PagerState,
    pageCount: Int,
    isSelectionActive: () -> Boolean,
    scope: CoroutineScope,
    onCommit: suspend (page: Int) -> Unit
): Modifier = pointerInput(curl, pagerState, pageCount) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (curl.isActive || isSelectionActive() || pagerState.isScrollInProgress) return@awaitEachGesture
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val fromRight = down.position.x > width * 0.76f
        val fromLeft = down.position.x < width * 0.24f
        if (!fromRight && !fromLeft) return@awaitEachGesture
        val current = pagerState.settledPage
        if (fromRight && current >= pageCount - 1) return@awaitEachGesture
        if (fromLeft && current <= 0) return@awaitEachGesture
        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            ?: return@awaitEachGesture
        curl.pageWidth = width
        curl.pageHeight = height
        val corner = CurlCorner.forStart(down.position.y, height)
        fun touchFor(position: Offset): Offset {
            val y = if (corner == CurlCorner.MIDDLE) {
                position.y
            } else {
                curlCornerY(corner, height, down.position.y) + (position.y - down.position.y)
            }
            val x = if (fromRight) {
                width + (position.x - down.position.x)
            } else {
                // The previous page lies turned over at -W and unfolds twice as fast as the finger.
                (2f * position.x - width).coerceAtLeast(-width)
            }
            return Offset(x, y)
        }
        curl.startDrag(
            page = if (fromRight) current else current - 1,
            forward = fromRight,
            corner = corner,
            touch = touchFor(slop.position)
        )
        val tracker = VelocityTracker()
        tracker.addPosition(slop.uptimeMillis, slop.position)
        drag(slop.id) { change ->
            tracker.addPosition(change.uptimeMillis, change.position)
            curl.dragTo(touchFor(change.position))
            change.consume()
        }
        val velocity = tracker.calculateVelocity()
        scope.launch { curl.release(velocity.x, onCommit) }
    }
}
