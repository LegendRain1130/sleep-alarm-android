package com.example.sleepalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SleepMonitorService : Service() {
    private var currentStatus: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> reconcileScreen(interactive = false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> reconcileScreen(interactive = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            MONITOR_CHANNEL_ID,
            getString(R.string.monitor_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(currentStatus ?: getString(R.string.monitor_waiting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(MONITOR_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(MONITOR_NOTIFICATION_ID, notification)
        }

        val interactive = getSystemService(PowerManager::class.java).isInteractive
        return if (reconcileScreen(interactive)) START_STICKY else START_NOT_STICKY
    }

    private fun reconcileScreen(interactive: Boolean): Boolean {
        val settings = SettingsStore.load(this)
        if (!settings.planEnabled || settings.sleepDurationMinutes <= 0 ||
            !WakeAlarmManager.canScheduleExactAlarms(this)
        ) {
            WakeAlarmManager.cancelInternalWake(this)
            return stopMonitoring()
        }

        val nowMillis = System.currentTimeMillis()
        val withinWindow = NightWindow.calculate(settings, nowMillis).isWithinWindow
        val current = WakeAlarmStateStore.load(this)
        val next = if (interactive) {
            SleepMonitorPolicy.screenOn(current)
        } else {
            SleepMonitorPolicy.screenOff(current, nowMillis, settings.sleepDurationMinutes, withinWindow)
        }

        if (next != current) {
            if (next.wakeAtMillis == 0L) {
                WakeAlarmManager.cancelInternalWake(this, next)
            } else if (!WakeAlarmManager.scheduleInternalWake(this, next.wakeAtMillis)) {
                return stopMonitoring()
            }
        }

        if (!SleepMonitorPolicy.shouldMonitor(withinWindow, next)) return stopMonitoring()

        val status = when {
            next.wakeAtMillis != 0L ->
                getString(R.string.monitor_scheduled, formatAlarmTime(next.wakeAtMillis))
            next.cancelledWakeAtMillis != 0L -> getString(R.string.monitor_cancelled)
            else -> getString(R.string.monitor_waiting)
        }
        if (status != currentStatus) {
            currentStatus = status
            getSystemService(NotificationManager::class.java)
                .notify(MONITOR_NOTIFICATION_ID, buildNotification(status))
        }
        mutableMonitoring.value = true
        return true
    }

    private fun stopMonitoring(): Boolean {
        mutableMonitoring.value = false
        stopSelf()
        return false
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        mutableMonitoring.value = false
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val MONITOR_CHANNEL_ID = "night_monitor_channel"
        private const val MONITOR_NOTIFICATION_ID = 1001

        private val mutableMonitoring = MutableStateFlow(false)
        internal val monitoring = mutableMonitoring.asStateFlow()
    }
}
