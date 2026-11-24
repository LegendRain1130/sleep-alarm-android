package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val wakeAtMillis = WakeAlarmManager.consumeInternalWake(context, intent) ?: return

        context.stopService(Intent(context, SleepMonitorService::class.java))

        try {
            ContextCompat.startForegroundService(context, AlarmPlayerService.playIntent(context, wakeAtMillis))
        } catch (error: IllegalStateException) {
            Log.e("AlarmReceiver", "Unable to start the alarm player", error)
        } catch (error: SecurityException) {
            Log.e("AlarmReceiver", "Unable to start the alarm player", error)
        }
    }
}
