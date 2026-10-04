package com.lumina.reader.ui.chat

import com.lumina.reader.core.model.ReadingStats
import kotlinx.datetime.toKotlinTimeZone
import java.time.ZoneId

/**
 * Test-only bridge for the JVM tests that still pass a `java.time` zone
 * (`AiLibraryActionsTest` uses `ZoneOffset.UTC`): [buildStatsContext] now
 * takes a kotlinx-datetime zone so it can run on iOS. Goes away when those
 * tests move to sharedUi commonTest with `TimeZone.UTC`.
 */
internal fun buildStatsContext(stats: List<ReadingStats>, zoneId: ZoneId): String =
    buildStatsContext(stats, zoneId.toKotlinTimeZone())
