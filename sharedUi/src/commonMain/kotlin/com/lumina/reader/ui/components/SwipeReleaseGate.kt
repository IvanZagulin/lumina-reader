package com.lumina.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Keeps swipe-to-dismiss actions on finger release, as they were with
 * Material3 1.3.
 *
 * Since Material3 1.4 the `confirmValueChange` callback of a
 * `SwipeToDismissBoxState` is also asked while the finger is still dragging,
 * each time the row passes the middle between two anchors (foundation's
 * AnchoredDraggableState). The rows that use it run their action from that
 * callback (delete, move to a shelf), so a mid-drag call would act before
 * release, or act on a swipe that the reader then drags back.
 *
 * The gate records whether a pointer is down on the row. While it is, the
 * callback vetoes every proposal without side effects, which is exactly the
 * 1.3 behaviour, where the state did not change during a drag. On release the
 * state settles and asks again, with the finger up.
 */
@Stable
class SwipeReleaseGate {
    /** True while at least one pointer is pressed on the gated element. */
    var isPointerDown: Boolean = false
        private set

    internal fun update(pressed: Boolean) {
        isPointerDown = pressed
    }
}

@Composable
fun rememberSwipeReleaseGate(): SwipeReleaseGate = remember { SwipeReleaseGate() }

/**
 * Feeds [gate] with the press state of this element. It observes the initial
 * pass and consumes nothing, so the swipe and the clicks below work as before;
 * a cancelled gesture arrives as an "all pointers up" event and resets it.
 */
fun Modifier.swipeReleaseGate(gate: SwipeReleaseGate): Modifier =
    pointerInput(gate) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                gate.update(event.changes.any { it.pressed })
            }
        }
    }
