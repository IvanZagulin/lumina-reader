package com.lumina.reader.core.reminder

import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.database.ReadingStatsDao
import com.lumina.reader.core.database.getDatabase
import com.lumina.reader.core.preferences.ReminderPreferences
import com.lumina.reader.core.preferences.ReminderSettings
import com.lumina.reader.platform.LuminaLog
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
// Star import: timeIntervalSince1970 and friends come from an NSDate category,
// which Kotlin/Native exposes as top-level extensions, not members.
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObjectProtocol

/** The iPhone's reminder: the same settings file as Android, delivered by UNUserNotificationCenter. */
internal actual fun defaultReminderEnvironment(): ReminderEnvironment = ReminderEnvironment(
    preferences = ReminderPreferences(),
    scheduler = IosReminderScheduler(AppDatabase.getDatabase().readingStatsDao())
)

/**
 * [ReminderScheduler] over UNUserNotificationCenter, with Android's text.
 *
 * Android's alarm wakes a receiver that posts the notification only if nobody
 * has read today. A local notification fires without running app code, so the
 * check moves to planning time: one weekly repeating calendar trigger per
 * weekday at the set time, which together fire every day for as long as the
 * app is installed, as Android's alarm does. When a session was already
 * recorded today and today's time is still ahead, today's weekday gets a
 * one-off trigger a week from now instead, so today stays quiet; the next plan
 * (at the next start, or when the app goes to the background) restores it.
 *
 * Authorization is requested first while the reminder is on; iOS shows its
 * prompt once and afterwards answers silently. Denied: nothing is planned.
 */
@OptIn(ExperimentalForeignApi::class)
class IosReminderScheduler(private val readingStats: ReadingStatsDao) : ReminderScheduler {

    /** One plan at a time: remove-then-add from two plans must not interleave. */
    private val planning = Mutex()

    override suspend fun apply(settings: ReminderSettings) {
        planning.withLock { plan(settings) }
    }

    private suspend fun plan(settings: ReminderSettings) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removePendingNotificationRequestsWithIdentifiers(REQUEST_IDS)
        if (!settings.enabled) return
        if (!center.requestAuthorization()) {
            LuminaLog.w(TAG, "Notifications are not allowed; the reading reminder is not planned")
            return
        }

        val hour = settings.hour.coerceIn(0, 23).toLong()
        val minute = settings.minute.coerceIn(0, 59).toLong()
        val calendar = NSCalendar.currentCalendar
        val now = NSDate()
        val todayWeekday = calendar.component(NSCalendarUnitWeekday, fromDate = now)
        val quietToday = readToday(calendar, now) && todayTimeIsAhead(calendar, now, hour, minute)

        // NSCalendar weekdays: 1 = Sunday ... 7 = Saturday.
        for (weekday in 1L..7L) {
            val trigger: UNNotificationTrigger = if (weekday == todayWeekday && quietToday) {
                nextWeekTrigger(calendar, now, hour, minute) ?: continue
            } else {
                val components = NSDateComponents()
                components.weekday = weekday
                components.hour = hour
                components.minute = minute
                UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, true)
            }
            val request = UNNotificationRequest.requestWithIdentifier(
                REQUEST_IDS[(weekday - 1).toInt()],
                reminderContent(),
                trigger
            )
            center.add(request)
        }
    }

    /** True once a reading session was recorded since local midnight (Android's receiver check). */
    private suspend fun readToday(calendar: NSCalendar, now: NSDate): Boolean {
        val startOfDayMillis = (calendar.startOfDayForDate(now).timeIntervalSince1970 * 1000.0).toLong()
        return try {
            readingStats.countSessionsSince(startOfDayMillis) > 0
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Unknown: keep today's reminder rather than lose it.
            LuminaLog.w(TAG, "Could not count today's reading sessions", error)
            false
        }
    }

    /** Whether today's reminder time has not come yet (only then is there a reminder to silence). */
    private fun todayTimeIsAhead(calendar: NSCalendar, now: NSDate, hour: Long, minute: Long): Boolean {
        val components = calendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, fromDate = now)
        components.hour = hour
        components.minute = minute
        val fireDate = calendar.dateFromComponents(components) ?: return false
        return fireDate.timeIntervalSince1970 > now.timeIntervalSince1970
    }

    /**
     * A one-off trigger at the set time a week from today. Calendar arithmetic,
     * not 7 × 24 hours, so a daylight-saving change cannot move it to another day.
     */
    private fun nextWeekTrigger(calendar: NSCalendar, now: NSDate, hour: Long, minute: Long): UNNotificationTrigger? {
        val inAWeek = calendar.dateByAddingUnit(NSCalendarUnitDay, value = 7L, toDate = now, options = 0uL) ?: return null
        val components = calendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, fromDate = inAWeek)
        components.hour = hour
        components.minute = minute
        return UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, false)
    }

    /** Android's notification text («Сегодня ещё не читали») with the default sound. */
    private fun reminderContent(): UNMutableNotificationContent = UNMutableNotificationContent().apply {
        setTitle("Сегодня ещё не читали")
        setBody("Откройте книгу — следующий шаг к достижению уже ждёт.")
        setSound(UNNotificationSound.defaultSound())
    }

    private suspend fun UNUserNotificationCenter.requestAuthorization(): Boolean =
        suspendCancellableCoroutine { continuation ->
            // The handler runs on a background queue; it only resumes the coroutine.
            requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, error ->
                if (error != null) LuminaLog.w(TAG, "Notification authorization failed: " + error.localizedDescription)
                continuation.resume(granted)
            }
        }

    private suspend fun UNUserNotificationCenter.add(request: UNNotificationRequest) {
        suspendCancellableCoroutine<Unit> { continuation ->
            addNotificationRequest(request) { error ->
                if (error != null) LuminaLog.w(TAG, "Could not plan a reading reminder: " + error.localizedDescription)
                continuation.resume(Unit)
            }
        }
    }

    private companion object {
        const val TAG = "ReadingReminder"

        /**
         * One fixed identifier per weekday. Every plan removes all seven
         * first, so a new plan replaces the old one instead of adding to it
         * and the reminder never shows twice on one day.
         */
        val REQUEST_IDS: List<String> = List(7) { "com.lumina.reader.reading-reminder.$it" }
    }
}

/**
 * Keeps the iPhone's reminder in step with the app. Call [start] once at
 * launch (MainViewController), the counterpart of MainActivity's
 * `ReadingReminder.reschedule`: it plans the reminder (asking for permission
 * the first time) and plans it again whenever the app goes to the background,
 * which is right after reading, so a day with a session loses its reminder.
 * Main thread only.
 *
 * The background plan waits [SESSION_SAVE_GRACE_MILLIS] first: the reader
 * saves its session on the same transition (its ON_STOP), asynchronously, and
 * a plan that counted today's sessions before that write would keep today's
 * reminder for someone who has just read. A background task keeps the app
 * awake until the plan is done; iOS would otherwise suspend it within seconds.
 */
object IosReadingReminders {
    private const val TAG = "ReadingReminder"
    private const val SESSION_SAVE_GRACE_MILLIS = 1_500L

    private val scope = MainScope()
    private var observer: NSObjectProtocol? = null

    fun start() {
        DailyReadingReminder.reschedule()
        if (observer != null) return
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidEnterBackgroundNotification,
            null,
            NSOperationQueue.mainQueue
        ) { _ ->
            replanInBackground()
        }
    }

    /** Runs on the main queue (the observer's), as does everything it starts. */
    private fun replanInBackground() {
        val application = UIApplication.sharedApplication
        var task: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        fun finish() {
            if (task != UIBackgroundTaskInvalid) {
                application.endBackgroundTask(task)
                task = UIBackgroundTaskInvalid
            }
        }
        // Not started before this function returns (MainScope dispatches), so
        // the task identifier below is set before the plan can finish.
        val plan = scope.launch {
            try {
                delay(SESSION_SAVE_GRACE_MILLIS)
                DailyReadingReminder.rescheduleNow()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // An uncaught exception would terminate the app; a missed plan is harmless.
                LuminaLog.w(TAG, "Could not plan the reading reminder", error)
            } finally {
                finish()
            }
        }
        task = application.beginBackgroundTaskWithName("ReadingReminder") {
            // Background time is up: iOS requires the task to end right here.
            plan.cancel()
            finish()
        }
    }
}
