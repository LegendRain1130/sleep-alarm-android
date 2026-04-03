package com.example.sleepalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NightStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // 让 scheduler 自己决定是否立即启动服务，以及安排下一次 start/stop
        NightScheduler.scheduleDailyWindow(context)
    }
}