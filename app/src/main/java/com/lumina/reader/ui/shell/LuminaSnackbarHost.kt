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

/** Nested-scroll connection that collapses the dock; top-level screens attach it to their lists. */
val LocalDockScroll = staticCompositionLocalOf<NestedScrollConnection> { object : NestedScrollConnection {} }

/**
 * Drives the dock collapse (spec §3.3): scrolling content down shrinks the
 * capsule to 52 dp and fades the labels, scrolling up restores it. [collapse]
 * (0..1) is read only in layout and draw lambdas.
 */
@Stable
class DockScrollState(private val scope: CoroutineScope) {
    val collapse = Animatable(0f)
    private var target = 0f
    private var job: Job? = null

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            when {
                available.y < -THRESHOLD -> animateTo(1f)
                available.y > THRESHOLD -> animateTo(0f)
            }
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

    private companion object {
        const val THRESHOLD = 1f
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
