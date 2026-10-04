package com.lumina.reader.core.network

import com.lumina.reader.core.preferences.CatalogPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

enum class ProxyType { HTTP, SOCKS5 }

/**
 * A proxy for the catalogues and book downloads (not for the app's other traffic).
 * Typed in once on each device, in the catalogue settings: it is deliberately not
 * built into the app, whose source and releases are public.
 *
 * On the iPhone a SOCKS5 proxy cannot ask for a login (the system's networking does
 * not support it); an HTTP proxy can.
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = ""
) {
    /** On and complete enough to use. */
    val usable: Boolean
        get() = enabled && host.isNotBlank() && port in 1..65535

    val hasCredentials: Boolean
        get() = username.isNotEmpty()
}

/**
 * The proxy the catalogue clients currently use. [CatalogPreferences] is the stored
 * truth; [bind] mirrors it here so the HTTP clients, which are built synchronously,
 * can read it. [ready] lets a request wait for the first read, so the very first
 * feed of a session already goes through the proxy.
 */
object NetworkProxy {
    @Volatile
    var current: ProxySettings? = null
        private set

    private val loaded = CompletableDeferred<Unit>()
    private var bound = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts following [preferences]; later calls do nothing. */
    fun bind(preferences: CatalogPreferences) {
        if (bound) return
        bound = true
        scope.launch {
            preferences.proxy.distinctUntilChanged().collect { settings ->
                current = settings.takeIf { it.usable }
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
    }

    /** Returns once the stored proxy has been read (at once if nothing was bound). */
    suspend fun ready() {
        if (bound) loaded.await()
    }
}
