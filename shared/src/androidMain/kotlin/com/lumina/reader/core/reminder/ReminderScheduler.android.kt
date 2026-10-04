package com.lumina.reader.core.reminder

/**
 * Android's reminder is an alarm plus a receiver and a notification that open
 * `MainActivity`, all in :app, which this module cannot see. :app's
 * `ReadingReminder` installs it on first use, so reaching this is a wiring bug.
 */
internal actual fun defaultReminderEnvironment(): ReminderEnvironment =
    error(
        "DailyReadingReminder.install { ReadingReminder.environment(this) } must run in LuminaApp.onCreate(), " +
            "or ReadingReminder must be called first"
    )
