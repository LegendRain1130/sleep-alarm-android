package com.example.sleepalarm

import org.junit.Assert.*
import org.junit.Test

class SleepMonitorPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun alreadySleepingAtStartupSchedulesFromNow() {
        assertEquals(now + 420L * 60_000L,
            SleepMonitorPolicy.screenOff(WakeAlarmState(), now, 420).wakeAtMillis)
    }

    @Test fun duplicateScreenOffAndServiceRecreationKeepOriginalWakeTime() {
        val scheduled = SleepMonitorPolicy.screenOff(WakeAlarmState(), now, 420)
        assertEquals(scheduled, SleepMonitorPolicy.screenOff(scheduled.copy(), now + 60_000, 480))
    }

    @Test fun briefScreenOnKeepsOriginalAlarmAndClearsCancellation() {
        val original = WakeAlarmState(now + 60_000)
        val screenOn = SleepMonitorPolicy.screenOn(original, 1_000)
        assertEquals(21_000L, screenOn.cancelAtElapsedMillis)
        assertEquals(original, SleepMonitorPolicy.screenOff(screenOn, now + 10_000, 420))
    }

    @Test fun repeatedScreenOnOrRestartCannotExtendGracePeriod() {
        val original = SleepMonitorPolicy.screenOn(WakeAlarmState(now + 60_000), 1_000)
        assertEquals(original, SleepMonitorPolicy.screenOn(original.copy(), 20_999))
        assertEquals(WakeAlarmState(), SleepMonitorPolicy.screenOn(original.copy(), 21_000))
    }

    @Test fun longScreenOnThenScreenOffStartsANewCountdown() {
        val pending = SleepMonitorPolicy.screenOn(WakeAlarmState(now + 60_000), 1_000)
        val cancelled = SleepMonitorPolicy.graceExpired(pending, 21_000)
        assertEquals(0L, cancelled.wakeAtMillis)
        assertEquals(now + 30_000 + 420L * 60_000,
            SleepMonitorPolicy.screenOff(cancelled, now + 30_000, 420).wakeAtMillis)
    }

    @Test fun dueAlarmIsNotPostponedByScreenOffWhileBroadcastIsQueued() {
        val due = WakeAlarmState(now - 1)
        assertEquals(due, SleepMonitorPolicy.screenOff(due, now, 420))
    }

    @Test fun largeLegacyDurationDoesNotOverflowIntegerMultiplication() {
        assertEquals(now + Int.MAX_VALUE.toLong() * 60_000,
            SleepMonitorPolicy.screenOff(WakeAlarmState(), now, Int.MAX_VALUE).wakeAtMillis)
    }

    @Test fun rebootRestoresFutureAlarmAndDiscardsPreviousBootGraceDeadline() {
        val pending = WakeAlarmState(now + 60_000, 123_000)
        assertEquals(WakeAlarmState(now + 60_000), SleepMonitorPolicy.afterBoot(pending, now, true))
        assertEquals(WakeAlarmState(), SleepMonitorPolicy.afterBoot(pending, now, false))
        assertEquals(WakeAlarmState(), SleepMonitorPolicy.afterBoot(pending, now + 60_000, true))
    }

    @Test fun cancelledReplacedMissingOrEarlyBroadcastCannotRing() {
        val alarm = WakeAlarmState(now)
        assertTrue(SleepMonitorPolicy.acceptsAlarm(alarm, now, now))
        assertFalse(SleepMonitorPolicy.acceptsAlarm(WakeAlarmState(), now, now))
        assertFalse(SleepMonitorPolicy.acceptsAlarm(alarm.copy(wakeAtMillis = now + 1), now, now))
        assertFalse(SleepMonitorPolicy.acceptsAlarm(alarm, 0, now))
        assertFalse(SleepMonitorPolicy.acceptsAlarm(alarm, now, now - 1))
    }
}
