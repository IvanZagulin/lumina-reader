package com.lumina.reader.ui.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

/**
 * Nested-scroll connection that collapses the dock; top-level screens attach it
 * to their lists.
 *
 * The default does nothing, so a shared screen works outside the Android shell
 * too — on iPhone, where there is no dock yet. The shell (:app) provides the
 * real connection; the rest of it moves here in stage 8d.
 */
val LocalDockScroll = staticCompositionLocalOf<NestedScrollConnection> { object : NestedScrollConnection {} }
