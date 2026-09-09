package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NightStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // A delayed stop broadcast may belong to an older window. Reconcile saved settings
        // and the current time before deciding whether the monitor should actually stop.
        NightScheduler.rescheduleIfNeeded(context)
    }
}
