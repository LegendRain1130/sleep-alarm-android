package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class NightStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NightScheduler.scheduleDailyWindow(context)

        val serviceIntent = Intent(context, SleepMonitorService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}