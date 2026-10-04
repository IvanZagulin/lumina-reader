package com.lumina.reader.core.reminder

import com.lumina.reader.core.preferences.ReminderSettings
import com.lumina.reader.platform.PlatformLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okio.IOException

/**
 * The daily reading reminder for common code: what Android's `ReadingReminder`
 * has always done, without a `Context`.
 *
 * - [reschedule] — call on app start and after anything that may affect the
 *   reminder; it reads [ReminderSettings] and schedules or cancels it.
 * - [setEnabled] / [setTime] — for the settings UI: persist and reschedule.
 * - [settings] — observe the current settings.
 *
 * The [ReminderEnvironment] is built on first use: iOS builds its own; on
 * Android, :app's `ReadingReminder` installs the alarm-based one before it
 * calls in here (and `LuminaApp` may install it up front).
 */
object DailyReadingReminder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = PlatformLock()
    private var factory: (() -> ReminderEnvironment)? = null

    // Read without the lock on every call (the receiver runs on a background
    // thread, the settings sheet on the main one); built under it once.
    @Volatile
    private var installed: ReminderEnvironment? = null

    /** Registers how to build the environment; [factory] runs on first use, not here. */
    fun install(factory: () -> ReminderEnvironment) {
        lock.withLock { this.factory = factory }
    }

    /** As [install], unless an environment is already registered or built. */
    fun installIfAbsent(factory: () -> ReminderEnvironment) {
        lock.withLock {
            if (this.factory == null && installed == null) this.factory = factory
        }
    }

    private val environment: ReminderEnvironment
        get() = installed ?: lock.withLock {
            installed ?: (factory?.invoke() ?: defaultReminderEnvironment()).also { installed = it }
        }

    fun settings(): Flow<ReminderSettings> = environment.preferences.settingsFlow

    /** Fire-and-forget: reads the saved settings and (re)schedules or cancels the reminder. */
    fun reschedule() {
        scope.launch {
            try {
                rescheduleNow()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // A reminder that could not be scheduled must never crash the app.
            }
        }
    }

    /** Suspending variant of [reschedule]; returns once the reminder has been updated. */
    suspend fun rescheduleNow() {
        environment.scheduler.apply(readSettings())
    }

    suspend fun setEnabled(enabled: Boolean) {
        environment.preferences.setEnabled(enabled)
        rescheduleNow()
    }

    /** [hour] 0..23, [minute] 0..59 (clamped). */
    suspend fun setTime(hour: Int, minute: Int) {
        environment.preferences.setTime(hour, minute)
        rescheduleNow()
    }

    /** The saved settings, or the defaults when the file cannot be read. */
    suspend fun readSettings(): ReminderSettings =
        try {
            environment.preferences.current()
        } catch (error: IOException) {
            ReminderSettings()
        }
}
