package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NightStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NightScheduler.rescheduleIfNeeded(context)
    }
}
