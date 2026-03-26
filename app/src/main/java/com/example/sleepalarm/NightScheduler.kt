package com.example.sleepalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object NightScheduler {

    private const val REQUEST_CODE_NIGHT_START = 5001
    private const val REQUEST_CODE_NIGHT_STOP = 5002

    fun scheduleDailyWindow(context: Context) {
        val settings = SettingsStore.load(context)
        SettingsStore.save(context, settings.copy(planEnabled = true))

        val startAt = nextOccurrenceMillis(settings.nightStartHour, settings.nightStartMinute)
        val stopAt = nextOccurrenceMillis(settings.nightEndHour, settings.nightEndMinute)

        scheduleExact(context, startAt, startPendingIntent(context))
        scheduleExact(context, stopAt, stopPendingIntent(context))
    }

    fun cancelDailyWindow(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(startPendingIntent(context))
        alarmManager.cancel(stopPendingIntent(context))

        val settings = SettingsStore.load(context)
        SettingsStore.save(context, settings.copy(planEnabled = false))
    }

    fun rescheduleIfNeeded(context: Context) {
        val settings = SettingsStore.load(context)
        if (settings.planEnabled) {
            scheduleDailyWindow(context)
        }
    }

    private fun scheduleExact(
        context: Context,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun startPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NightStartReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_NIGHT_START,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NightStopReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_NIGHT_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextOccurrenceMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis
    }
}