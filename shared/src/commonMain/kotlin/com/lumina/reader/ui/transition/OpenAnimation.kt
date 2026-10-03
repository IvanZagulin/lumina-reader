package com.lumina.reader.ui.transition

// Split from app's BookTransitionState.kt: the preferences store this enum,
// so it lives with the platform-neutral code. The package is unchanged.

/** «Анимация открытия книги»: the full hinge, a quick one, or a plain fade. */
enum class OpenAnimation(val title: String) {
    FULL("Полная"),
    FAST("Быстрая"),
    OFF("Выкл")
}
