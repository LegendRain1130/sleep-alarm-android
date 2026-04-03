package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NightStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WakeAlarmManager.cancelInternalWake(context)

        context.stopService(Intent(context, SleepMonitorService::class.java))
        context.stopService(Intent(context, AlarmPlayerService::class.java))

        // 重新安排下一轮夜间计划
        NightScheduler.scheduleDailyWindow(context)
    }
}