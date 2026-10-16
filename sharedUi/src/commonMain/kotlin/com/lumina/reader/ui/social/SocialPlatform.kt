package com.lumina.reader.ui.social

import com.lumina.reader.core.database.BookDao
import com.lumina.reader.core.library.ImportPipeline
import com.lumina.reader.core.preferences.SocialPreferences

/**
 * The address of the circle this app joins by default. It is only the value the
 * join form starts with: the server lives at whatever address the user keeps in
 * [com.lumina.reader.core.preferences.SocialSettings.serverUrl], so a different
 * circle is a matter of typing another one.
 */
const val DEFAULT_CIRCLE_SERVER: String = "https://social.quasar-x.ru"

/**
 * What the reading circle's screen needs, built once per process: the stored
 * membership and the library it shares. It exists for the same reason
 * [com.lumina.reader.ui.stats.StatsServices] does - the platforms build these
 * differently, Android needs a `Context` for both, iOS needs nothing.
 */
class SocialServices(
    val preferences: SocialPreferences,
    val bookDao: BookDao,
    /**
     * The library's own import pipeline, not a second one: a received book goes
     * through exactly the path a downloaded or shared book does, and the
     * pipeline serialises imports with a lock that only works if there is one.
     */
    val importPipeline: ImportPipeline
)

/**
 * The circle's services of this process. iOS builds them lazily on first use;
 * Android's `LuminaApp.onCreate` installs a Context-backed set, as it does for
 * `AppServices`, since only the app has a `Context` to open the database and
 * the preference file with.
 */
object SocialServicesHolder {
    private var factory: (() -> SocialServices)? = null
    private var installed: SocialServices? = null

    /**
     * Registers how to build the services; [factory] runs when the circle first
     * opens, not here, so start-up never touches the circle's preference file.
     */
    fun install(factory: () -> SocialServices) {
        this.factory = factory
    }

    val services: SocialServices
        get() = installed ?: (factory?.invoke() ?: defaultSocialServices()).also { installed = it }
}

/** Android: an error (nothing to build them from without `install`); iOS: the app's own database. */
internal expect fun defaultSocialServices(): SocialServices
