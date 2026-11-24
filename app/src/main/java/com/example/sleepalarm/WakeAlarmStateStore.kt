package com.example.sleepalarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object WakeAlarmStateStore {
    private const val PREFS_NAME = "wake_alarm_state"
    private const val KEY_WAKE_AT = "wake_at_millis"
    private const val KEY_CANCELLED_WAKE_AT = "cancelled_wake_at_millis"

    private val mutableState = MutableStateFlow(WakeAlarmState())
    val state = mutableState.asStateFlow()

    fun load(context: Context): WakeAlarmState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WakeAlarmState(
            wakeAtMillis = prefs.getLong(KEY_WAKE_AT, 0L),
            cancelledWakeAtMillis = prefs.getLong(KEY_CANCELLED_WAKE_AT, 0L)
        )
    }

    fun refresh(context: Context) {
        mutableState.value = load(context)
    }

    fun save(context: Context, state: WakeAlarmState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_WAKE_AT, state.wakeAtMillis)
            .putLong(KEY_CANCELLED_WAKE_AT, state.cancelledWakeAtMillis)
            .remove("cancel_at_elapsed_millis")
            .apply()
        mutableState.value = state
    }
}
