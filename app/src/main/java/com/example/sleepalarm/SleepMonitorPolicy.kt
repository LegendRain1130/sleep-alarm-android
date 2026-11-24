package com.example.sleepalarm

/** State is shared by the monitor and alarm receiver, and survives service recreation. */
internal data class WakeAlarmState(
    val wakeAtMillis: Long = 0L,
    val cancelledWakeAtMillis: Long = 0L
)

internal object SleepMonitorPolicy {
    fun shouldMonitor(isWithinWindow: Boolean, state: WakeAlarmState): Boolean =
        isWithinWindow || state.wakeAtMillis > 0L

    fun screenOff(
        state: WakeAlarmState,
        nowMillis: Long,
        sleepDurationMinutes: Int,
        isWithinWindow: Boolean
    ): WakeAlarmState {
        if (!isWithinWindow || state.wakeAtMillis > 0L) return state
        require(sleepDurationMinutes > 0)
        return WakeAlarmState(wakeAtMillis = nowMillis + sleepDurationMinutes.toLong() * 60_000L)
    }

    fun screenOn(state: WakeAlarmState): WakeAlarmState {
        if (state.wakeAtMillis == 0L) return state
        return WakeAlarmState(cancelledWakeAtMillis = state.wakeAtMillis)
    }

    fun afterBoot(state: WakeAlarmState, nowMillis: Long, planEnabled: Boolean): WakeAlarmState {
        return if (planEnabled && state.wakeAtMillis > nowMillis) {
            state
        } else {
            WakeAlarmState()
        }
    }

    fun acceptsAlarm(state: WakeAlarmState, triggerAtMillis: Long, nowMillis: Long): Boolean {
        return triggerAtMillis > 0L && triggerAtMillis == state.wakeAtMillis &&
            nowMillis >= triggerAtMillis
    }
}
