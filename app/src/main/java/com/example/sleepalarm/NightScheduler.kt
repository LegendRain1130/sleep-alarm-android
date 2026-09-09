package com.example.sleepalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object NightScheduler {

    private const val REQUEST_CODE_NIGHT_START = 5001
    private const val REQUEST_CODE_NIGHT_STOP = 5002

    // Only the user's save action enables the plan. Late broadcasts must not enable it.
    fun scheduleDailyWindow(context: Context): Boolean {
        val settings = SettingsStore.load(context)
        if (!settings.planEnabled || !WakeAlarmManager.canScheduleExactAlarms(context)) {
            cancelScheduledBoundaries(context)
            stopCurrentWindow(context)
            return false
        }

        val window = NightWindow.calculate(settings, System.currentTimeMillis())
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                window.nextStartMillis,
                startPendingIntent(context)
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                window.nextStopMillis,
                stopPendingIntent(context)
            )
        } catch (_: SecurityException) {
            // Permission can change between the permission check and either alarm call.
            cancelScheduledBoundaries(context)
            stopCurrentWindow(context)
            return false
        }

        if (window.isWithinWindow) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SleepMonitorService::class.java)
                )
            } catch (_: IllegalStateException) {
                // Some system/OEM background restrictions can still deny a service start.
                return false
            } catch (_: SecurityException) {
                return false
            }
        } else {
            // Saving a different window must also stop a monitor from the previous window.
            stopCurrentWindow(context)
        }
        return true
    }

    fun cancelDailyWindow(context: Context) {
        val settings = SettingsStore.load(context)
        SettingsStore.save(context, settings.copy(planEnabled = false))
        cancelScheduledBoundaries(context)
        stopCurrentWindow(context)
    }

    fun rescheduleIfNeeded(context: Context): Boolean = scheduleDailyWindow(context)

    fun isNowWithinWindow(settings: AppSettings, nowMillis: Long): Boolean =
        NightWindow.calculate(settings, nowMillis).isWithinWindow

    private fun cancelScheduledBoundaries(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(startPendingIntent(context))
        alarmManager.cancel(stopPendingIntent(context))
    }

    private fun stopCurrentWindow(context: Context) {
        WakeAlarmManager.cancelInternalWake(context)
        context.stopService(Intent(context, SleepMonitorService::class.java))
        context.stopService(Intent(context, AlarmPlayerService::class.java))
    }

    private fun startPendingIntent(context: Context): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_NIGHT_START,
            Intent(context, NightStartReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPendingIntent(context: Context): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_NIGHT_STOP,
            Intent(context, NightStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
