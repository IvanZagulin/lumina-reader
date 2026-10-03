package com.lumina.reader.core.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lumina.reader.MainActivity
import com.lumina.reader.R
import com.lumina.reader.core.database.AppDatabase
import com.lumina.reader.core.preferences.ReminderPreferences
import com.lumina.reader.core.preferences.ReminderSettings
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Public entry point for the daily reading reminder.
 *
 * - [reschedule] — call on app start and after anything that may affect the
 *   alarm; it reads [ReminderSettings] and schedules or cancels the alarm.
 * - [setEnabled] / [setTime] — for the settings UI: persist and reschedule.
 * - [settings] — observe the current settings.
 */
object ReadingReminder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun settings(context: Context): Flow<ReminderSettings> =
        ReminderPreferences(context.applicationContext).settingsFlow

    /** Fire-and-forget: reads the saved settings and (re)schedules or cancels the alarm. */
    fun reschedule(context: Context) {
        val appContext = context.applicationContext
        scope.launch { rescheduleNow(appContext) }
    }

    /** Suspending variant of [reschedule]; returns once the alarm has been updated. */
    suspend fun rescheduleNow(context: Context) {
        val appContext = context.applicationContext
        ReadingReminderScheduler.applySettings(appContext, readSettings(appContext))
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        ReminderPreferences(appContext).setEnabled(enabled)
        rescheduleNow(appContext)
    }

    /** [hour] 0..23, [minute] 0..59 (clamped). */
    suspend fun setTime(context: Context, hour: Int, minute: Int) {
        val appContext = context.applicationContext
        ReminderPreferences(appContext).setTime(hour, minute)
        rescheduleNow(appContext)
    }

    internal suspend fun readSettings(context: Context): ReminderSettings =
        try {
            ReminderPreferences(context).current()
        } catch (error: IOException) {
            ReminderSettings()
        }
}

object ReadingReminderScheduler {
    const val ACTION_REMIND = "com.lumina.reader.action.READING_REMINDER"
    private const val CHANNEL_ID = "reading_reminders"
    private const val REMINDER_ID = 701
    private const val REQUEST_CODE = 702

    /** Do not schedule closer than this to "now", so an alarm firing a moment early cannot repeat itself. */
    private const val MIN_LEAD_MILLIS = 30_000L

    /** Kept for existing callers; same as [ReadingReminder.reschedule]. */
    fun schedule(context: Context) {
        ReadingReminder.reschedule(context)
    }

    /** Schedules the next reminder for [settings], or cancels it when reminders are disabled. */
    fun applySettings(context: Context, settings: ReminderSettings) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        if (!settings.enabled) {
            alarmManager.cancel(reminderPendingIntent(context))
            return
        }
        createChannel(context)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMillis(
                nowMillis = System.currentTimeMillis(),
                hour = settings.hour,
                minute = settings.minute,
                timeZone = TimeZone.getDefault()
            ),
            reminderPendingIntent(context)
        )
    }

    /** The next moment (strictly after [nowMillis] plus a small lead) that is [hour]:[minute] in [timeZone]. */
    fun nextTriggerMillis(nowMillis: Long, hour: Int, minute: Int, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (calendar.timeInMillis <= nowMillis + MIN_LEAD_MILLIS) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    fun showReminder(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            REMINDER_ID,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompatHolder.from(context).notify(
            REMINDER_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Сегодня ещё не читали")
                .setContentText("Откройте книгу — следующий шаг к достижению уже ждёт.")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        )
    }

    private fun reminderPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReadingReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Напоминания о чтении",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Одно ежедневное напоминание продолжить чтение"
                }
            )
        }
    }
}

class ReadingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val isReminder = intent.action == ReadingReminderScheduler.ACTION_REMIND
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (isReminder && ReadingReminder.readSettings(appContext).enabled) {
                    val zone = ZoneId.systemDefault()
                    val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                    if (AppDatabase.getDatabase(appContext).readingStatsDao().countSessionsSince(startOfDay) == 0) {
                        ReadingReminderScheduler.showReminder(appContext)
                    }
                }
            } catch (error: Exception) {
                // A missed reminder is harmless; never crash a background broadcast over it.
            } finally {
                try {
                    // Also handles BOOT_COMPLETED: alarms do not survive a reboot.
                    ReadingReminder.rescheduleNow(appContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

/** Keeps notification API imports out of the scheduling code. */
private object NotificationManagerCompatHolder {
    fun from(context: Context) = androidx.core.app.NotificationManagerCompat.from(context)
}
