package com.lumina.reader.ui.transition

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.lumina.reader.ui.components.BookCoverModel
import com.lumina.reader.ui.components.ClothPalette
import com.lumina.reader.ui.components.CoverPalette
import com.lumina.reader.ui.theme.LuminaMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path

/** Where the page starts when the reader closes (predictive back may have shrunk it). */
internal data class CloseStart(val scale: Float = 1f, val translationX: Float = 0f, val cornerPx: Float = 0f)

/**
 * State of the root-level book-open overlay (spec §6). The reader is navigated
 * to at once and composes underneath; the overlay draws the flying book, the
 * hinge and the page, then fades away when the reader reports it is ready.
 * Closing replays it backwards to the book's slot on the shelf.
 *
 * Every animated value is an [Animatable] read only in draw / layer lambdas.
 */
@Stable
class BookTransitionState internal constructor(
    private val scope: CoroutineScope,
    private val graphicsContext: GraphicsContext,
    private val navLayer: GraphicsLayer
) : BookSlotHost {
    enum class Phase { Idle, Opening, WaitingReader, Closing }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** The book whose shelf slot is drawn transparent while the clone flies. */
    override var hiddenBookId by mutableStateOf<Long?>(null)
        private set

    /** The shared shelf books (:sharedUi) register their slots through this. */
    override fun slotModifier(key: String, bookId: Long): Modifier =
        Modifier.bookTransitionSlot(this, key, bookId)

    /**
     * The library grows from [LIBRARY_SCALE_DEPTH] below 1 back to 1 while the
     * reader closes onto it; at rest it draws at its own size.
     */
    override val libraryScale: Float
        get() = if (phase == Phase.Closing) librarySourceScale() else 1f

    /** NavHost transitions are skipped while the overlay animates. */
    val suppressNavAnimation: Boolean get() = phase != Phase.Idle

    /** The book being opened or closed. */
    var cover by mutableStateOf<BookCoverModel?>(null)
        private set

    /** Endpaper tint: the cover's colour or its generated cloth. */
    var cloth by mutableStateOf(Color(0xFF6E1F2A))
        private set

    /** Reader page colour and ink (the reading theme), kept current by the root. */
    var paper by mutableStateOf(Color.White)
    var paperInk by mutableStateOf(Color(0xFF191C1E))

    /** Reduced motion turns every open/close into a plain NavHost fade. */
    var reducedMotion by mutableStateOf(false)

    val flight = Animatable(0f)
    val hinge = Animatable(0f)
    val expand = Animatable(0f)
    val overlayAlpha = Animatable(0f)
    val libraryBackdrop = Animatable(0f)

    /** Close without a target slot: the book shrinks to 0.6 and fades. */
    internal val vanish = Animatable(0f)

    /** Closing: 1 shows the reader snapshot, 0 the cover it fades into. */
    internal val readerFade = Animatable(0f)

    /** True while closing (the live library is under the overlay). */
    internal var closing by mutableStateOf(false)
        private set

    /** The library snapshot is ready to be drawn behind the opening book. */
    internal var backdropReady by mutableStateOf(false)
        private set

    /** The reader is slow: show «Открываю книгу…». */
    internal var waitingVisible by mutableStateOf(false)
        private set

    /** Slot bounds (open) or close target; unscaled while [sourceFollowsLibraryScale]. */
    var sourceRect: Rect = Rect.Zero
        internal set

    /**
     * While closing, the live library grows from [LIBRARY_SCALE_DEPTH] below 1 back
     * to 1; the target slot is kept unscaled and re-scaled every frame so the book
     * lands exactly on it.
     */
    internal var sourceFollowsLibraryScale = false
        private set
    var stageRect: Rect = Rect.Zero
        internal set

    /** Root bounds; the page expands to it. */
    internal var fullRect: Rect = Rect.Zero
        private set

    /** Page rect and corner at expand = 1 (full window, or the predictive-back preview). */
    internal var endRect: Rect = Rect.Zero
        private set
    internal var endCornerPx: Float = 0f
        private set

    /** Full-resolution library while opening; then only [libraryShotSmall] is kept. */
    var libraryShot: ImageBitmap? = null
        private set

    /** Quarter-resolution library for the predictive-back preview. */
    var libraryShotSmall: ImageBitmap? = null
        private set

    var readerShot: ImageBitmap? = null
        private set

    /**
     * Size of the shell's root box in px (its `onSizeChanged`); the stage and
     * the page are laid out in it. Public because the Android root,
     * `LuminaNavGraph`, stays in :app and writes it from there.
     */
    var rootSize: IntSize = IntSize.Zero

    /** The root's density, kept current by the shell like [rootSize]; it records the downscaled snapshot. */
    var density: Density = Density(1f)

    internal val slots = HashMap<String, SlotEntry>()
    private val readerReady = MutableStateFlow<Long?>(null)
    private var originSlotKey: String? = null
    private var job: Job? = null

    /** Set synchronously by [open] / [close] so a second tap or back press is ignored. */
    private var busy = false

    internal class SlotEntry(val bookId: Long, val coordinates: LayoutCoordinates, val owner: Any)

    fun onReaderReady(bookId: Long) {
        readerReady.value = bookId
    }

    /** Back pressed while the page waits for a slow reader: fade onto it now. */
    internal fun skipWaiting() {
        if (phase == Phase.WaitingReader) hiddenBookId?.let { readerReady.value = it }
    }

    internal fun registerSlot(key: String, bookId: Long, coordinates: LayoutCoordinates, owner: Any) {
        slots[key] = SlotEntry(bookId, coordinates, owner)
    }

    internal fun unregisterSlot(key: String, owner: Any) {
        if (slots[key]?.owner === owner) slots.remove(key)
    }

    /** Root-space bounds of a registered slot (position + size, not clipped by its LazyRow). */
    internal fun slotRect(key: String): Rect? {
        val entry = slots[key] ?: return null
        return entry.rectOrNull()
    }

    /** Any visible slot that shows [bookId] (opening from a sheet or a deep link). */
    override fun findSlotKey(bookId: Long): String? {
        val size = rootSize
        return slots.entries.firstOrNull { (_, entry) ->
            entry.bookId == bookId &&
                entry.rectOrNull()?.let { TransitionGeometry.isOnScreen(it, size.width.toFloat(), size.height.toFloat()) } == true
        }?.key
    }

    private fun SlotEntry.rectOrNull(): Rect? {
        val coords = coordinates
        if (!coords.isAttached) return null
        val position = coords.positionInRoot()
        val size = coords.size
        return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
    }

    private fun computeStage(): Rect =
        TransitionGeometry.showcaseRect(rootSize.width.toFloat(), rootSize.height.toFloat())

    private fun clothFor(model: BookCoverModel): Color {
        val path = (model.image as? Path)?.toString()
        return CoverPalette.peek(path)?.base ?: ClothPalette.forBook(model.title, model.author).color
    }

    /**
     * Opens [cover] from the slot [slotKey]: snapshot the library, call
     * [navigate], then play the flight → hinge → expand sequence and fade onto
     * the reader once it reports ready. With [OpenAnimation.OFF], reduced
     * motion or while another transition runs, it only navigates.
     */
    override fun open(cover: BookCoverModel, slotKey: String?, mode: OpenAnimation, navigate: () -> Unit) {
        val bookId = cover.bookId
        if (busy || phase != Phase.Idle) return
        if (bookId == null || mode == OpenAnimation.OFF || reducedMotion || rootSize == IntSize.Zero) {
            navigate()
            return
        }
        busy = true
        job = scope.launch {
            var navigated = false
            try {
                val width = rootSize.width.toFloat()
                val height = rootSize.height.toFloat()
                fullRect = Rect(0f, 0f, width, height)
                endRect = fullRect
                endCornerPx = 0f
                stageRect = computeStage()
                sourceRect = slotKey?.let { slotRect(it) }
                    ?.takeIf { TransitionGeometry.isOnScreen(it, width, height) }
                    ?: TransitionGeometry.centeredSource(stageRect)
                this@BookTransitionState.cover = cover
                cloth = clothFor(cover)
                originSlotKey = slotKey
                sourceFollowsLibraryScale = false
                readerReady.value = null
                libraryShot = null
                libraryShotSmall = null
                readerShot = null
                flight.snapTo(0f)
                hinge.snapTo(0f)
                expand.snapTo(0f)
                libraryBackdrop.snapTo(0f)
                vanish.snapTo(0f)
                readerFade.snapTo(0f)
                overlayAlpha.snapTo(1f)
                closing = false
                backdropReady = false
                waitingVisible = false
                hiddenBookId = bookId
                phase = Phase.Opening

                // Let the slot turn transparent before the library is captured.
                withFrameNanos { }
                withFrameNanos { }
                val shot = runCatching { navLayer.toImageBitmap() }.getOrNull()
                libraryShot = shot
                libraryShotSmall = shot?.let { downscale(it) }
                backdropReady = true
                navigate()
                navigated = true

                val fast = mode == OpenAnimation.FAST
                // The cover grows straight to half the screen while the library dims,
                // holds a moment with «Приятного чтения», then fades onto the text.
                coroutineScope {
                    launch { flight.animateTo(1f, tween(if (fast) 320 else 650, easing = LuminaMotion.Emphasized)) }
                    launch {
                        libraryBackdrop.animateTo(1f, tween(if (fast) 360 else 700, easing = LinearOutSlowInEasing))
                    }
                }
                delay(if (fast) 120L else 450L)

                phase = Phase.WaitingReader
                val ready = withTimeoutOrNull(WAIT_BEFORE_INDICATOR_MS) { readerReady.first { it == bookId } }
                if (ready == null) {
                    waitingVisible = true
                    withTimeoutOrNull(READER_TIMEOUT_MS - WAIT_BEFORE_INDICATOR_MS) {
                        readerReady.first { it == bookId }
                    }
                }
                overlayAlpha.animateTo(0f, tween(if (fast) 220 else 420, easing = LinearOutSlowInEasing))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash over an animation: the reader still opens.
            } finally {
                if (!navigated && isActive) navigate()
                finishOpen()
            }
        }
    }

    private fun finishOpen() {
        busy = false
        waitingVisible = false
        backdropReady = false
        libraryShot = null
        hiddenBookId = null
        phase = Phase.Idle
    }

    /**
     * Closes the reader of [bookId]: capture [readerLayer], cover the screen
     * with it, [popBack] under the overlay, then shrink, close the hinge and
     * fly the book back to its slot (or vanish at the stage without one).
     * Falls back to a plain [popBack] when animations are off or nothing is
     * known about the book.
     */
    internal fun close(
        readerLayer: GraphicsLayer,
        bookId: Long,
        cover: BookCoverModel?,
        mode: OpenAnimation,
        popBack: () -> Unit,
        start: CloseStart = CloseStart()
    ) {
        if (busy || phase != Phase.Idle) return
        if (cover == null || mode == OpenAnimation.OFF || reducedMotion || rootSize == IntSize.Zero) {
            libraryShotSmall = null
            popBack()
            return
        }
        busy = true
        job = scope.launch {
            var popped = false
            try {
                val width = rootSize.width.toFloat()
                val height = rootSize.height.toFloat()
                fullRect = Rect(0f, 0f, width, height)
                endRect = TransitionGeometry.scaleAbout(fullRect, fullRect.center, start.scale)
                    .translate(Offset(start.translationX, 0f))
                endCornerPx = start.cornerPx
                stageRect = computeStage()
                sourceRect = TransitionGeometry.centeredSource(stageRect)
                sourceFollowsLibraryScale = false
                this@BookTransitionState.cover = cover
                cloth = clothFor(cover)
                readerShot = runCatching { readerLayer.toImageBitmap() }.getOrNull()

                flight.snapTo(1f)
                hinge.snapTo(0f)
                expand.snapTo(1f)
                readerFade.snapTo(1f)
                libraryBackdrop.snapTo(1f)
                vanish.snapTo(0f)
                overlayAlpha.snapTo(1f)
                closing = true
                backdropReady = false
                waitingVisible = false
                hiddenBookId = bookId
                phase = Phase.Closing

                // The overlay covers the reader before it leaves.
                withFrameNanos { }
                popBack()
                popped = true

                var target: SlotCandidate? = null
                for (attempt in 0 until 3) {
                    withFrameNanos { }
                    target = findCloseTarget(bookId)
                    if (target != null) break
                }

                val found = target
                if (found != null) {
                    // Measured inside the scaled library: store it unscaled (see frame()).
                    val measuredScale = librarySourceScale().coerceAtLeast(0.5f)
                    sourceRect = TransitionGeometry.scaleAbout(found.rect, fullRect.center, 1f / measuredScale)
                    sourceFollowsLibraryScale = true
                    readerFade.animateTo(0f, tween(320, easing = LinearOutSlowInEasing))
                    coroutineScope {
                        launch { flight.animateTo(0f, tween(480, easing = LuminaMotion.Emphasized)) }
                        launch { libraryBackdrop.animateTo(0f, tween(520, easing = LinearEasing)) }
                    }
                } else {
                    readerFade.animateTo(0f, tween(320, easing = LinearOutSlowInEasing))
                    coroutineScope {
                        launch { libraryBackdrop.animateTo(0f, tween(450, easing = LinearEasing)) }
                        launch { vanish.animateTo(1f, tween(300, easing = LuminaMotion.EmphasizedAccelerate)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash over an animation: the reader still closes.
            } finally {
                if (!popped && isActive) popBack()
                finishClose()
            }
        }
    }

    /** Scale of the live library while closing (1 − depth·backdrop). */
    internal fun librarySourceScale(): Float = 1f - LIBRARY_SCALE_DEPTH * libraryBackdrop.value

    private fun findCloseTarget(bookId: Long): SlotCandidate? {
        val candidates = slots.mapNotNull { (key, entry) ->
            entry.rectOrNull()?.let { SlotCandidate(key, entry.bookId, it) }
        }
        return TransitionGeometry.pickCloseTarget(
            candidates = candidates,
            originKey = originSlotKey,
            bookId = bookId,
            rootWidth = rootSize.width.toFloat(),
            rootHeight = rootSize.height.toFloat()
        )
    }

    private fun finishClose() {
        busy = false
        closing = false
        readerShot = null
        libraryShotSmall = null
        hiddenBookId = null
        phase = Phase.Idle
    }

    /** A quarter-resolution copy, drawn through a GraphicsLayer (works with hardware bitmaps). */
    private suspend fun downscale(source: ImageBitmap): ImageBitmap? {
        val width = (source.width / 4).coerceAtLeast(1)
        val height = (source.height / 4).coerceAtLeast(1)
        val layer = runCatching { graphicsContext.createGraphicsLayer() }.getOrNull() ?: return null
        return try {
            layer.record(density, LayoutDirection.Ltr, IntSize(width, height)) {
                drawImage(
                    image = source,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(source.width, source.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(width, height)
                )
            }
            layer.toImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            graphicsContext.releaseGraphicsLayer(layer)
        }
    }

    internal companion object {
        /** The library behind the overlay is scaled down by this much (spec §5.1, §6.5). */
        const val LIBRARY_SCALE_DEPTH = 0.06f

        /** «Быстрая» opens the cover to about −110° (h = 0.61). */
        const val WAIT_BEFORE_INDICATOR_MS = 400L
        const val READER_TIMEOUT_MS = 10_000L
    }
}

/** Provided by the app shell's root (`LuminaNavGraph` on Android); null outside it (previews, tests). */
val LocalBookTransition = staticCompositionLocalOf<BookTransitionState?> { null }

/** The transition state of the root; [navLayer] must record the NavHost (and dock). */
@Composable
fun rememberBookTransitionState(navLayer: GraphicsLayer): BookTransitionState {
    val scope = rememberCoroutineScope()
    val graphicsContext = LocalGraphicsContext.current
    return remember(navLayer, scope, graphicsContext) {
        BookTransitionState(scope, graphicsContext, navLayer)
    }
}
