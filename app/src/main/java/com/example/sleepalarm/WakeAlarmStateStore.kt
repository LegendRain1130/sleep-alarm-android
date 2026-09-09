package com.example.sleepalarm

import android.content.Context

internal object WakeAlarmStateStore {
    private const val PREFS_NAME = "wake_alarm_state"
    private const val KEY_WAKE_AT = "wake_at_millis"
    private const val KEY_CANCEL_AT = "cancel_at_elapsed_millis"

    fun load(context: Context): WakeAlarmState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WakeAlarmState(
            wakeAtMillis = prefs.getLong(KEY_WAKE_AT, 0L),
            cancelAtElapsedMillis = prefs.getLong(KEY_CANCEL_AT, 0L)
        )
    }

    fun save(context: Context, state: WakeAlarmState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_WAKE_AT, state.wakeAtMillis)
            .putLong(KEY_CANCEL_AT, state.cancelAtElapsedMillis)
            .apply()
    }
}
