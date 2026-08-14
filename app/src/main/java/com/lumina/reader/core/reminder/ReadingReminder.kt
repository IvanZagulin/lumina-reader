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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object ReadingReminderScheduler {
    const val ACTION_REMIND = "com.lumina.reader.action.READING_REMINDER"
    private const val CHANNEL_ID = "reading_reminders"
    private const val REMINDER_ID = 701
    private const val REQUEST_CODE = 702

    fun schedule(context: Context) {
        createChannel(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            reminderPendingIntent(context)
        )
    }

    fun showReminder(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

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
                    description = "Одно вечернее напоминание продолжить чтение"
                }
            )
        }
    }
}

class ReadingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReadingReminderScheduler.schedule(context)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                if (AppDatabase.getDatabase(context).readingStatsDao().countSessionsSince(startOfDay) == 0) {
                    ReadingReminderScheduler.showReminder(context)
                }
            } finally {
                ReadingReminderScheduler.schedule(context)
                pendingResult.finish()
            }
        }
    }
}

/** Keeps notification API imports out of the scheduling code. */
private object NotificationManagerCompatHolder {
    fun from(context: Context) = androidx.core.app.NotificationManagerCompat.from(context)
}
