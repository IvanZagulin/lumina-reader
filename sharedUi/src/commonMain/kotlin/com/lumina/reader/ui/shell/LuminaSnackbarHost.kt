package com.lumina.reader.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.dp
import com.lumina.reader.ui.theme.Lumina
import com.lumina.reader.ui.theme.LuminaMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The app-wide snackbar state (spec §3.1); screens may show their own messages on it. */
val LocalAppSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Drives the dock collapse (spec §3.3): scrolling content down shrinks the
 * capsule to 52 dp and fades the labels, scrolling up restores it. [collapse]
 * (0..1) is read only in layout and draw lambdas.
 *
 * It follows the content, not the finger. The dock used to be driven from
 * `onPreScroll`, which reports the whole gesture before the list has had it: at
 * the very end of a list the finger keeps moving while the content does not, and
 * the stretch springing back reports the opposite direction a frame later, so the
 * dock collapsed and expanded over and over - the jumping the user sees when a
 * list is scrolled to its end. What a list actually scrolled is `consumed` in
 * [NestedScrollConnection.onPostScroll], and that is zero once there is nowhere
 * left to go.
 */
@Stable
class DockScrollState(private val scope: CoroutineScope) {
    val collapse = Animatable(0f)
    private var target = 0f
    private var job: Job? = null

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            dockTargetFor(consumed.y)?.let(::animateTo)
            return Offset.Zero
        }
    }

    /** Expands the dock at once (e.g. after switching tabs). */
    fun reset() {
        animateTo(0f)
    }

    private fun animateTo(value: Float) {
        if (target == value) return
        target = value
        job?.cancel()
        job = scope.launch {
            collapse.animateTo(value, tween(200, easing = LuminaMotion.EmphasizedDecelerate))
        }
    }

    internal companion object {
        /** Below this a scroll is jitter, not a direction. */
        const val THRESHOLD = 1f

        /**
         * Where the dock should go for a scroll that moved the content by
         * [consumedY] pixels, or null to leave it where it is. Nothing consumed
         * means the list had nowhere to go, which must not move the dock.
         */
        fun dockTargetFor(consumedY: Float): Float? = when {
            consumedY < -THRESHOLD -> 1f
            consumedY > THRESHOLD -> 0f
            else -> null
        }
    }
}

@Composable
fun rememberDockScrollState(): DockScrollState {
    val scope = rememberCoroutineScope()
    return remember(scope) { DockScrollState(scope) }
}

/**
 * The root snackbar (spec §7.10): an inverse capsule with radius 16 for
 * imports, shelf changes and errors; the action uses `inversePrimary`.
 */
@Composable
fun LuminaSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = Lumina.colors
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 12.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = colors.inverseCapsule,
            contentColor = colors.onInverseCapsule,
            actionColor = MaterialTheme.colorScheme.inversePrimary,
            actionContentColor = MaterialTheme.colorScheme.inversePrimary,
            dismissActionContentColor = colors.onInverseCapsule
        )
    }
}
