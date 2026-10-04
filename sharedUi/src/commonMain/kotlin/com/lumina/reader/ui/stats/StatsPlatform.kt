package com.lumina.reader.ui.stats

import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.database.ReadingStatsDao

/**
 * Raw storage of the reading goals («Дневная цель», «Цель на год»). On Android
 * it is the SharedPreferences file "reading_stats_goals" the goals have always
 * lived in, so nobody loses them; on iOS the standard NSUserDefaults. The
 * statistics' own `StatsGoalPreferences` keeps the keys, defaults and parsing,
 * so both platforms read the goals the same way.
 */
interface StatsGoalStore {
    /** The stored string, or null when there is none. */
    fun getString(key: String): String?

    /** The stored number, or [default] when there is none. */
    fun getInt(key: String, default: Int): Int

    /**
     * Writes every entry of [strings] and [ints] in one go: one
     * `SharedPreferences.Editor.apply()` on Android, as before, so a goal is
     * never half saved.
     */
    fun putAll(strings: Map<String, String>, ints: Map<String, Int>)
}

/**
 * What the statistics screens need, built once per process. It exists because
 * the platforms build the same objects differently: Android opens the database
 * and the preferences through a `Context`, iOS needs nothing.
 */
class StatsServices(
    val bookDao: BookDao,
    val readingStatsDao: ReadingStatsDao,
    val goalStore: StatsGoalStore
)

/**
 * The statistics services of this process. iOS builds them lazily on first
 * use; Android's `LuminaApp.onCreate` installs a Context-backed set (as it does
 * for `AppServices`), since only the app has a `Context` to open them with.
 */
object StatsServicesHolder {
    private var factory: (() -> StatsServices)? = null
    private var installed: StatsServices? = null

    /**
     * Registers how to build the services; [factory] runs on first use, not
     * here, so opening the statistics' database and preference file never
     * slows down the start of the app.
     */
    fun install(factory: () -> StatsServices) {
        this.factory = factory
    }

    val services: StatsServices
        get() = installed ?: (factory?.invoke() ?: defaultStatsServices()).also { installed = it }
}

/** Android: an error (nothing to build them from without `install`); iOS: built from the app's database. */
internal expect fun defaultStatsServices(): StatsServices
