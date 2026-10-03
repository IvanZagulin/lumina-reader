package com.lumina.reader.platform

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** Wall-clock and monotonic time for common code (no java.lang.System). */
object AppClock {

    /** Milliseconds since the Unix epoch, like `System.currentTimeMillis()`. */
    @OptIn(ExperimentalTime::class)
    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /** A mark for measuring elapsed time that wall-clock changes do not affect. */
    fun monotonic(): TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
}
