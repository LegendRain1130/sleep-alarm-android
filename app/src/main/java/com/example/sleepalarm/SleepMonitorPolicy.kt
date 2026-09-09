package com.example.sleepalarm

/** State is shared by the monitor and alarm receiver, and survives service recreation. */
internal data class WakeAlarmState(
    val wakeAtMillis: Long = 0L,
    val cancelAtElapsedMillis: Long = 0L
)

internal object SleepMonitorPolicy {
    const val SCREEN_ON_GRACE_MILLIS = 20_000L

    fun screenOff(
        state: WakeAlarmState,
        nowMillis: Long,
        sleepDurationMinutes: Int
    ): WakeAlarmState {
        require(sleepDurationMinutes > 0)
        // Repeated broadcasts and service restarts must not postpone an existing alarm.
        // Even a due alarm is preserved until its receiver consumes it.
        val wakeAt = state.wakeAtMillis.takeIf { it > 0L }
            ?: (nowMillis + sleepDurationMinutes.toLong() * 60_000L)
        return WakeAlarmState(wakeAtMillis = wakeAt)
    }

    fun screenOn(state: WakeAlarmState, nowElapsedMillis: Long): WakeAlarmState {
        if (state.wakeAtMillis == 0L) return WakeAlarmState()
        if (state.cancelAtElapsedMillis != 0L) {
            return graceExpired(state, nowElapsedMillis)
        }
        return state.copy(cancelAtElapsedMillis = nowElapsedMillis + SCREEN_ON_GRACE_MILLIS)
    }

    fun graceExpired(state: WakeAlarmState, nowElapsedMillis: Long): WakeAlarmState {
        return if (state.cancelAtElapsedMillis != 0L &&
            nowElapsedMillis >= state.cancelAtElapsedMillis
        ) {
            WakeAlarmState()
        } else {
            state
        }
    }

    fun afterBoot(state: WakeAlarmState, nowMillis: Long, monitoringAllowed: Boolean): WakeAlarmState {
        return if (monitoringAllowed && state.wakeAtMillis > nowMillis) {
            // elapsedRealtime starts over on reboot; the old grace deadline is invalid.
            state.copy(cancelAtElapsedMillis = 0L)
        } else {
            WakeAlarmState()
        }
    }

    fun acceptsAlarm(state: WakeAlarmState, triggerAtMillis: Long, nowMillis: Long): Boolean {
        return triggerAtMillis > 0L && triggerAtMillis == state.wakeAtMillis &&
            nowMillis >= triggerAtMillis
    }
}
