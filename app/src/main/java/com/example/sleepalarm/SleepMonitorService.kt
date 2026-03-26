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
import androidx.core.app.NotificationCompat
import java.util.Calendar

class SleepMonitorService : Service() {

    private var screenReceiver: BroadcastReceiver? = null
    private var currentStatus: String = "夜间监测中：等待熄屏"
    private var currentWakeAtMillis: Long = 0L
    private var sleepDurationMinutes: Int = 420

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore.load(this)
        sleepDurationMinutes = settings.sleepDurationMinutes

        startAsForeground()
        updateMonitorNotification("夜间监测中：等待熄屏")

        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> handleScreenOn()
                }
            }
        }

        screenReceiver = receiver

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        registerReceiver(receiver, filter)
    }

    private fun handleScreenOff() {
        val settings = SettingsStore.load(this)
        sleepDurationMinutes = settings.sleepDurationMinutes

        val now = System.currentTimeMillis()
        val wakeAt = now + sleepDurationMinutes * 60 * 1000L
        currentWakeAtMillis = wakeAt

        WakeAlarmManager.scheduleInternalWake(this, wakeAt)
        updateMonitorNotification(
            "已预定本体闹铃：${formatClock(wakeAt)}，亮屏将取消"
        )
    }

    private fun handleScreenOn() {
        if (currentWakeAtMillis != 0L) {
            WakeAlarmManager.cancelInternalWake(this)
            currentWakeAtMillis = 0L
            updateMonitorNotification("屏幕亮起：已取消本体闹铃，等待下一次熄屏")
        } else {
            updateMonitorNotification("夜间监测中：等待熄屏")
        }
    }

    private fun startAsForeground() {
        val notification = buildMonitorNotification(currentStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MONITOR_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(MONITOR_NOTIFICATION_ID, notification)
        }
    }

    private fun updateMonitorNotification(status: String) {
        currentStatus = status
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(MONITOR_NOTIFICATION_ID, buildMonitorNotification(status))
    }

    private fun buildMonitorNotification(status: String): Notification {
        return NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
            .setContentTitle("SleepAlarm")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val monitorChannel = NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Night Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(monitorChannel)
        }
    }

    private fun formatClock(timeMillis: Long): String {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = timeMillis
        }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return "%02d:%02d".format(hour, minute)
    }

    override fun onDestroy() {
        screenReceiver?.let {
            unregisterReceiver(it)
            screenReceiver = null
        }

        WakeAlarmManager.cancelInternalWake(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val MONITOR_CHANNEL_ID = "night_monitor_channel"
        private const val MONITOR_NOTIFICATION_ID = 1001
    }
}