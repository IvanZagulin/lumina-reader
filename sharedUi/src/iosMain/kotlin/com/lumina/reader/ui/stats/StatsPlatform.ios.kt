package com.lumina.reader.ui.stats

import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.getDatabase
import platform.Foundation.NSUserDefaults

/** The iPhone builds the statistics' services from the app's own database on first use. */
internal actual fun defaultStatsServices(): StatsServices {
    val db = AppDatabase.getDatabase()
    return StatsServices(
        bookDao = db.bookDao(),
        readingStatsDao = db.readingStatsDao(),
        goalStore = NSUserDefaultsStatsGoalStore()
    )
}

/**
 * [StatsGoalStore] over the standard NSUserDefaults. Keys carry the name of
 * Android's goals file as a prefix, so they cannot collide with the app's other
 * settings, which share the same defaults.
 */
class NSUserDefaultsStatsGoalStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : StatsGoalStore {

    override fun getString(key: String): String? = defaults.stringForKey(scoped(key))

    // integerForKey answers 0 for a missing key, which would replace the goal
    // defaults (30 minutes, 24 books) with 0; a missing key must give [default].
    override fun getInt(key: String, default: Int): Int =
        if (defaults.objectForKey(scoped(key)) == null) default else defaults.integerForKey(scoped(key)).toInt()

    override fun putAll(strings: Map<String, String>, ints: Map<String, Int>) {
        strings.forEach { (key, value) -> defaults.setObject(value, forKey = scoped(key)) }
        ints.forEach { (key, value) -> defaults.setInteger(value.toLong(), forKey = scoped(key)) }
    }

    private fun scoped(key: String): String = "$KEY_PREFIX$key"

    private companion object {
        const val KEY_PREFIX = "reading_stats_goals."
    }
}
