package com.lumina.reader.ui.reader

/** Direction of a physical or on-screen page turn. */
enum class PageTurnDirection {
    PREVIOUS,
    NEXT
}

/**
 * Bridges Activity-level volume key events to the currently visible reader.
 *
 * Android routes volume keys before Compose receives focus events, therefore
 * the active reader registers a short-lived handler here. The owner token
 * prevents an old composition from unregistering a newer reader instance.
 */
object ReaderPageNavigation {
    private data class Registration(
        val owner: Any,
        val handler: (PageTurnDirection) -> Boolean
    )

    @Volatile
    private var registration: Registration? = null

    fun register(owner: Any, handler: (PageTurnDirection) -> Boolean) {
        registration = Registration(owner, handler)
    }

    fun unregister(owner: Any) {
        if (registration?.owner === owner) registration = null
    }

    fun hasActiveReader(): Boolean = registration != null

    fun dispatch(direction: PageTurnDirection): Boolean =
        registration?.handler?.invoke(direction) == true
}
