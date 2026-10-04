package com.lumina.reader.ui.stats

import android.content.Context
import android.content.SharedPreferences

/** Android needs a `Context` for the database and the goals file, so `LuminaApp.onCreate` installs them. */
internal actual fun defaultStatsServices(): StatsServices =
    error("StatsServicesHolder.install(...) must run in LuminaApp.onCreate()")

/**
 * [StatsGoalStore] over the SharedPreferences file the goals have always been
 * kept in ([FILE_NAME]); writes are applied asynchronously, as before.
 */
class SharedPreferencesStatsGoalStore(context: Context) : StatsGoalStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putAll(strings: Map<String, String>, ints: Map<String, Int>) {
        val editor = prefs.edit()
        strings.forEach { (key, value) -> editor.putString(key, value) }
        ints.forEach { (key, value) -> editor.putInt(key, value) }
        editor.apply()
    }

    companion object {
        const val FILE_NAME = "reading_stats_goals"
    }
}
