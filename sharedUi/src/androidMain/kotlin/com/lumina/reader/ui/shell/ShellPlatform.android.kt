package com.lumina.reader.ui.shell

/**
 * Android's reminder is `ReadingReminder` in :app (alarm, receiver,
 * notification channel), which this module cannot see, so `LuminaApp` has
 * to install it; a missing install is a wiring bug, not a reason to hide the
 * setting silently.
 */
internal actual fun defaultReminderControl(): ReminderControl =
    throw IllegalStateException(
        "ShellServices.installReminders { ReadingReminderControl(this) } must be called from LuminaApp.onCreate"
    )
