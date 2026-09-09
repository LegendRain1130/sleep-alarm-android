package com.example.sleepalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class SleepMonitorService : Service() {

    private var screenReceiver: BroadcastReceiver? = null
    private var currentStatus: String = "夜间监测中：等待熄屏"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCancelRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (!canMonitor()) return START_NOT_STICKY

        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (!powerManager.isInteractive) {
            handleScreenOff()
        } else if (!keyguardManager.isKeyguardLocked) {
            handleUserPresent()
        } else {
            handleScreenOn()
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
        if (!canMonitor()) return
        cancelPendingCancelRunnable()
        val previous = WakeAlarmStateStore.load(this)
        val next = SleepMonitorPolicy.screenOff(
            previous, System.currentTimeMillis(), SettingsStore.load(this).sleepDurationMinutes
        )
        if (previous.wakeAtMillis == 0L) {
            if (!WakeAlarmManager.scheduleInternalWake(this, next.wakeAtMillis)) {
                stopSelf()
                return
            }
        } else {
            WakeAlarmStateStore.save(this, next)
        }
        updateMonitorNotification("已预定闹铃：${formatClock(next.wakeAtMillis)}，短暂亮屏不会重置")
    }

    private fun handleScreenOn() {
        if (!canMonitor()) return
        cancelPendingCancelRunnable()
        val previous = WakeAlarmStateStore.load(this)
        val now = SystemClock.elapsedRealtime()
        val next = SleepMonitorPolicy.screenOn(previous, now)
        if (next.wakeAtMillis == 0L) {
            if (previous.wakeAtMillis != 0L) WakeAlarmManager.cancelInternalWake(this)
            updateMonitorNotification("夜间监测中：等待熄屏")
            return
        }
        WakeAlarmStateStore.save(this, next)
        updateMonitorNotification("检测到亮屏：持续 20 秒或解锁将取消闹铃")
        pendingCancelRunnable = Runnable {
            pendingCancelRunnable = null
            if (canMonitor()) {
                if (getSystemService(PowerManager::class.java).isInteractive) {
                    handleScreenOn()
                } else {
                    handleScreenOff()
                }
            }
        }.also { handler.postDelayed(it, (next.cancelAtElapsedMillis - now).coerceAtLeast(1L)) }
    }

    private fun handleUserPresent() {
        if (!canMonitor()) return
        cancelPendingCancelRunnable()
        WakeAlarmManager.cancelInternalWake(this)
        updateMonitorNotification("已解锁：等待下一次熄屏")
    }

    private fun canMonitor(): Boolean {
        val settings = SettingsStore.load(this)
        if (settings.planEnabled && settings.sleepDurationMinutes > 0 &&
            NightScheduler.isNowWithinWindow(settings, System.currentTimeMillis()) &&
            WakeAlarmManager.canScheduleExactAlarms(this)
        ) return true
        cancelPendingCancelRunnable()
        WakeAlarmManager.cancelInternalWake(this)
        stopSelf()
        return false
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

    }
}
