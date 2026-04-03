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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class SleepMonitorService : Service() {

    private var screenReceiver: BroadcastReceiver? = null
    private var currentStatus: String = "夜间监测中：等待熄屏"
    private var currentWakeAtMillis: Long = 0L
    private var sleepDurationMinutes: Int = 420

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCancelRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SettingsStore.load(this)
        sleepDurationMinutes = settings.sleepDurationMinutes

        startAsForeground()

        if (currentWakeAtMillis == 0L) {
            updateMonitorNotification("夜间监测中：等待熄屏")
        } else {
            updateMonitorNotification("夜间监测中：已预定 ${formatClock(currentWakeAtMillis)}")
        }

        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON -> handleScreenOn()
                    Intent.ACTION_USER_PRESENT -> handleUserPresent()
                }
            }
        }

        screenReceiver = receiver

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun handleScreenOff() {
        val settings = SettingsStore.load(this)
        sleepDurationMinutes = settings.sleepDurationMinutes

        // 如果刚刚只是短暂亮屏，现在又熄屏了，则保留原闹铃，不重置
        if (pendingCancelRunnable != null && currentWakeAtMillis != 0L) {
            cancelPendingCancelRunnable()
            updateMonitorNotification("短暂亮屏已忽略：保留 ${formatClock(currentWakeAtMillis)} 的闹铃")
            return
        }

        val now = System.currentTimeMillis()
        val wakeAt = now + sleepDurationMinutes * 60 * 1000L
        currentWakeAtMillis = wakeAt

        WakeAlarmManager.scheduleInternalWake(this, wakeAt)
        updateMonitorNotification(
            "已预定本体闹铃：${formatClock(wakeAt)}，亮屏后将等待确认"
        )
    }

    private fun handleScreenOn() {
        if (currentWakeAtMillis == 0L) {
            updateMonitorNotification("夜间监测中：等待熄屏")
            return
        }

        // 已经有一个“亮屏确认取消”的等待任务，就不要重复添加
        if (pendingCancelRunnable != null) {
            return
        }

        updateMonitorNotification("检测到亮屏：若 $SCREEN_ON_GRACE_SECONDS 秒内未重新熄屏，将取消闹铃")

        val runnable = Runnable {
            if (currentWakeAtMillis != 0L) {
                val cancelledAt = currentWakeAtMillis
                WakeAlarmManager.cancelInternalWake(this)
                currentWakeAtMillis = 0L
                updateMonitorNotification("亮屏持续过久：已取消 ${formatClock(cancelledAt)} 的闹铃")
            }
            pendingCancelRunnable = null
        }

        pendingCancelRunnable = runnable
        handler.postDelayed(runnable, SCREEN_ON_GRACE_MILLIS)
    }

    private fun handleUserPresent() {
        // 用户已经解锁，视为主动使用手机，立即取消
        cancelPendingCancelRunnable()

        if (currentWakeAtMillis != 0L) {
            val cancelledAt = currentWakeAtMillis
            WakeAlarmManager.cancelInternalWake(this)
            currentWakeAtMillis = 0L
            updateMonitorNotification("已解锁：取消 ${formatClock(cancelledAt)} 的闹铃，等待下一次熄屏")
        } else {
            updateMonitorNotification("夜间监测中：等待熄屏")
        }
    }

    private fun cancelPendingCancelRunnable() {
        pendingCancelRunnable?.let { handler.removeCallbacks(it) }
        pendingCancelRunnable = null
    }

    private fun startAsForeground() {
        val notification = buildMonitorNotification(currentStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
        cancelPendingCancelRunnable()

        screenReceiver?.let {
            unregisterReceiver(it)
            screenReceiver = null
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val MONITOR_CHANNEL_ID = "night_monitor_channel"
        private const val MONITOR_NOTIFICATION_ID = 1001

        private const val SCREEN_ON_GRACE_SECONDS = 20
        private const val SCREEN_ON_GRACE_MILLIS = SCREEN_ON_GRACE_SECONDS * 1000L
    }
}