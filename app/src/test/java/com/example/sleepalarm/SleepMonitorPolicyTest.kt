package com.example.sleepalarm

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepMonitorPolicyTest {
    private val now = 1_800_000_000_000L
    private val duration = 420

    @Test fun alreadySleepingAtStartupSchedulesFromNow() {
        val scheduled = SleepMonitorPolicy.screenOff(WakeAlarmState(), now, duration, true)
        assertEquals(now + 420L * 60_000L, scheduled.wakeAtMillis)
    }

    @Test fun duplicateScreenOffAndServiceRecreationKeepOriginalWakeTime() {
        val scheduled = SleepMonitorPolicy.screenOff(WakeAlarmState(), now, duration, true)
        assertEquals(scheduled, SleepMonitorPolicy.screenOff(scheduled, now + 60_000, 480, true))
    }

    @Test fun evenBriefLockedScreenOnCancelsAndNextScreenOffStartsNewCountdown() {
        val original = SleepMonitorPolicy.screenOff(WakeAlarmState(), now, duration, true)
        val cancelled = SleepMonitorPolicy.screenOn(original)
        assertEquals(0L, cancelled.wakeAtMillis)
        assertEquals(original.wakeAtMillis, cancelled.cancelledWakeAtMillis)
        assertEquals(cancelled, SleepMonitorPolicy.screenOn(cancelled))
        assertFalse(SleepMonitorPolicy.acceptsAlarm(cancelled, original.wakeAtMillis, original.wakeAtMillis))

        val rescheduled = SleepMonitorPolicy.screenOff(cancelled, now + 1_000L, duration, true)
        assertEquals(original.wakeAtMillis + 1_000L, rescheduled.wakeAtMillis)
        assertEquals(0L, rescheduled.cancelledWakeAtMillis)
    }

    @Test fun twoAmScreenOffStillRingsAtNineAfterTheWindowEndsAtEight() {
        val bedtime = ZonedDateTime.parse("2026-11-24T02:00:00+08:00[Asia/Shanghai]")
        val bedtimeMillis = bedtime.toInstant().toEpochMilli()
        val settings = AppSettings(nightEndHour = 8)
        val night = NightWindow.calculate(settings, bedtimeMillis, bedtime.zone)
        val scheduled = SleepMonitorPolicy.screenOff(WakeAlarmState(), bedtimeMillis, duration, night.isWithinWindow)

        val nineAm = bedtime.plusHours(7).toInstant().toEpochMilli()
        assertEquals(nineAm, scheduled.wakeAtMillis)
        assertTrue(night.nextStopMillis < nineAm)
        val atEnd = NightWindow.calculate(settings, night.nextStopMillis, bedtime.zone)
        assertFalse(atEnd.isWithinWindow)
        assertTrue(SleepMonitorPolicy.shouldMonitor(atEnd.isWithinWindow, scheduled))
        assertEquals(scheduled, SleepMonitorPolicy.screenOff(scheduled, night.nextStopMillis, duration, false))
        assertTrue(SleepMonitorPolicy.acceptsAlarm(scheduled, nineAm, nineAm))
    }

    @Test fun afterWindowScreenOnCancelsAndScreenOffDoesNotCreateAnotherAlarm() {
        val scheduled = WakeAlarmState(now + 60_000)
        val cancelled = SleepMonitorPolicy.screenOn(scheduled)

        assertFalse(SleepMonitorPolicy.shouldMonitor(false, cancelled))
        assertEquals(cancelled, SleepMonitorPolicy.screenOff(cancelled, now, duration, false))
        assertEquals(WakeAlarmState(), SleepMonitorPolicy.screenOff(WakeAlarmState(), now, duration, false))
        assertFalse(SleepMonitorPolicy.acceptsAlarm(cancelled, scheduled.wakeAtMillis, scheduled.wakeAtMillis))
    }

    @Test fun dueAlarmIsNotPostponedByScreenOffWhileBroadcastIsQueued() {
        val due = WakeAlarmState(now - 1)
        assertEquals(due, SleepMonitorPolicy.screenOff(due, now, duration, true))
    }

    @Test fun largeLegacyDurationDoesNotOverflowIntegerMultiplication() {
        val scheduled = SleepMonitorPolicy.screenOff(WakeAlarmState(), now, Int.MAX_VALUE, true)
        assertEquals(now + Int.MAX_VALUE.toLong() * 60_000L, scheduled.wakeAtMillis)
    }

    @Test fun rebootRestoresAStillValidAlarmEvenIfTheWindowHasEnded() {
        val pending = WakeAlarmState(now + 60_000)
        assertEquals(pending, SleepMonitorPolicy.afterBoot(pending, now, true))
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
