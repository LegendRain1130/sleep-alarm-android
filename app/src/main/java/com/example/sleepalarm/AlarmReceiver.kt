package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DebugNotifier.show(
            context = context,
            title = "SleepAlarm 调试",
            text = "AlarmReceiver 已收到闹铃",
            id = 9001
        )

        WakeAlarmManager.cancelInternalWake(context)
        context.stopService(Intent(context, SleepMonitorService::class.java))

        val alarmIntent = Intent(context, AlarmPlayerService::class.java)
        ContextCompat.startForegroundService(context, alarmIntent)
    }
}