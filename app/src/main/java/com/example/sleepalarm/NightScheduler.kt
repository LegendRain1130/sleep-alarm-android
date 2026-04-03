package com.example.sleepalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.Calendar

object NightScheduler {

    private const val REQUEST_CODE_NIGHT_START = 5001
    private const val REQUEST_CODE_NIGHT_STOP = 5002

    fun scheduleDailyWindow(context: Context) {
        val settings = SettingsStore.load(context)
        SettingsStore.save(context, settings.copy(planEnabled = true))

        val now = System.currentTimeMillis()

        val nextStartAt = nextStartMillis(settings, now)
        val nextStopAt = nextStopMillis(settings, now)

        scheduleExact(context, nextStartAt, startPendingIntent(context))
        scheduleExact(context, nextStopAt, stopPendingIntent(context))

        // 关键修复：如果当前已经处于夜间窗口内，立刻启动监测
        if (isNowWithinWindow(settings, now)) {
            val intent = Intent(context, SleepMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
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

    private fun isNowWithinWindow(settings: AppSettings, nowMillis: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val startMinutes = settings.nightStartHour * 60 + settings.nightStartMinute
        val endMinutes = settings.nightEndHour * 60 + settings.nightEndMinute

        return if (startMinutes < endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            // 跨午夜窗口，例如 23:30 -> 12:00
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    private fun nextStartMillis(settings: AppSettings, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val startMinutes = settings.nightStartHour * 60 + settings.nightStartMinute
        val endMinutes = settings.nightEndHour * 60 + settings.nightEndMinute
        val overnight = startMinutes >= endMinutes

        return if (!overnight) {
            if (nowMinutes < startMinutes) {
                atDayOffset(cal, settings.nightStartHour, settings.nightStartMinute, 0)
            } else {
                atDayOffset(cal, settings.nightStartHour, settings.nightStartMinute, 1)
            }
        } else {
            if (nowMinutes in endMinutes until startMinutes) {
                atDayOffset(cal, settings.nightStartHour, settings.nightStartMinute, 0)
            } else {
                atDayOffset(cal, settings.nightStartHour, settings.nightStartMinute, 1)
            }
        }
    }

    private fun nextStopMillis(settings: AppSettings, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val startMinutes = settings.nightStartHour * 60 + settings.nightStartMinute
        val endMinutes = settings.nightEndHour * 60 + settings.nightEndMinute
        val overnight = startMinutes >= endMinutes

        return if (!overnight) {
            when {
                nowMinutes < startMinutes -> atDayOffset(cal, settings.nightEndHour, settings.nightEndMinute, 0)
                nowMinutes < endMinutes -> atDayOffset(cal, settings.nightEndHour, settings.nightEndMinute, 0)
                else -> atDayOffset(cal, settings.nightEndHour, settings.nightEndMinute, 1)
            }
        } else {
            when {
                nowMinutes >= startMinutes -> atDayOffset(cal, settings.nightEndHour, settings.nightEndMinute, 1)
                nowMinutes < endMinutes -> atDayOffset(cal, settings.nightEndHour, settings.nightEndMinute, 0)
                else -> atDayOffset(cal, settings.nightEndHour, settings.nightEndMinute, 1)
            }
        }
    }

    private fun atDayOffset(base: Calendar, hour: Int, minute: Int, dayOffset: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = base.timeInMillis
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}