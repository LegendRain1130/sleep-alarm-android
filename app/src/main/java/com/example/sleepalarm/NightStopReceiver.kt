package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NightStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NightScheduler.scheduleDailyWindow(context)
        WakeAlarmManager.cancelInternalWake(context)

        context.stopService(Intent(context, SleepMonitorService::class.java))
        context.stopService(Intent(context, AlarmPlayerService::class.java))
    }
}