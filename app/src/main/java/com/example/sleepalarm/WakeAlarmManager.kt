package com.example.sleepalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object WakeAlarmManager {

    private const val REQUEST_CODE_WAKE_ALARM = 1001
    private const val REQUEST_CODE_SHOW_APP = 1002
    private const val EXTRA_TRIGGER_AT = "com.example.sleepalarm.TRIGGER_AT_MILLIS"

    fun canScheduleExactAlarms(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    fun scheduleInternalWake(context: Context, triggerAtMillis: Long): Boolean {
        if (triggerAtMillis <= 0L || !canScheduleExactAlarms(context)) {
            cancelInternalWake(context)
            return false
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_SHOW_APP,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val wakeIntent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_TRIGGER_AT, triggerAtMillis)
        val wakePendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_WAKE_ALARM,
            wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent),
                wakePendingIntent
            )
            WakeAlarmStateStore.save(context, WakeAlarmState(wakeAtMillis = triggerAtMillis))
            true
        } catch (_: SecurityException) {
            // Permission can be revoked between checking it and registering the alarm.
            cancelInternalWake(context)
            false
        }
    }

    internal fun cancelInternalWake(context: Context, nextState: WakeAlarmState = WakeAlarmState()) {
        // Clear shared state first so an already queued broadcast cannot start playback.
        WakeAlarmStateStore.save(context, nextState)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_WAKE_ALARM,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun restoreAfterBoot(context: Context) {
        val settings = SettingsStore.load(context)
        val now = System.currentTimeMillis()
        val restored = SleepMonitorPolicy.afterBoot(
            WakeAlarmStateStore.load(context),
            now,
            settings.planEnabled
        )
        if (restored.wakeAtMillis == 0L) {
            cancelInternalWake(context)
        } else {
            scheduleInternalWake(context, restored.wakeAtMillis)
        }
    }

    internal fun consumeInternalWake(context: Context, intent: Intent?): Long? {
        if (!SettingsStore.load(context).planEnabled) {
            cancelInternalWake(context)
            return null
        }
        val triggerAt = intent?.getLongExtra(EXTRA_TRIGGER_AT, 0L) ?: 0L
        if (!SleepMonitorPolicy.acceptsAlarm(
                WakeAlarmStateStore.load(context), triggerAt, System.currentTimeMillis()
            )
        ) {
            return null
        }
        cancelInternalWake(context)
        return triggerAt
    }
}
