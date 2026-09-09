package com.example.sleepalarm

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightWindowTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")
    private val overnight = AppSettings()

    @Test
    fun afterMidnightSchedulesTonightInsteadOfSkippingADay() {
        val window = calculate(overnight, "2026-09-08T01:00:00+08:00")

        assertTrue(window.isWithinWindow)
        assertEquals(at("2026-09-08T23:30:00+08:00"), window.nextStartMillis)
        assertEquals(at("2026-09-08T12:00:00+08:00"), window.nextStopMillis)
    }

    @Test
    fun betweenOvernightWindowsSchedulesTheFollowingMorningStop() {
        val window = calculate(overnight, "2026-09-08T14:00:00+08:00")

        assertFalse(window.isWithinWindow)
        assertEquals(at("2026-09-08T23:30:00+08:00"), window.nextStartMillis)
        assertEquals(at("2026-09-09T12:00:00+08:00"), window.nextStopMillis)
    }

    @Test
    fun startIsInclusiveAndEndIsExclusive() {
        assertFalse(calculate(overnight, "2026-09-08T23:29:59.999+08:00").isWithinWindow)
        val atStart = calculate(overnight, "2026-09-08T23:30:00+08:00")
        assertTrue(atStart.isWithinWindow)
        assertEquals(at("2026-09-09T23:30:00+08:00"), atStart.nextStartMillis)
        assertTrue(calculate(overnight, "2026-09-09T11:59:59.999+08:00").isWithinWindow)
        val atEnd = calculate(overnight, "2026-09-09T12:00:00+08:00")
        assertFalse(atEnd.isWithinWindow)
        assertEquals(at("2026-09-10T12:00:00+08:00"), atEnd.nextStopMillis)
    }

    @Test
    fun daytimeWindowUsesTheSameDayAndAdvancesAfterItsEnd() {
        val daytime = overnight.copy(
            nightStartHour = 10, nightStartMinute = 0,
            nightEndHour = 18, nightEndMinute = 0
        )
        val before = calculate(daytime, "2026-09-08T09:00:00+08:00")
        assertFalse(before.isWithinWindow)
        assertEquals(at("2026-09-08T10:00:00+08:00"), before.nextStartMillis)
        assertEquals(at("2026-09-08T18:00:00+08:00"), before.nextStopMillis)

        val during = calculate(daytime, "2026-09-08T10:00:00+08:00")
        assertTrue(during.isWithinWindow)
        assertEquals(at("2026-09-09T10:00:00+08:00"), during.nextStartMillis)
        assertEquals(at("2026-09-08T18:00:00+08:00"), during.nextStopMillis)

        val after = calculate(daytime, "2026-09-08T18:00:00+08:00")
        assertFalse(after.isWithinWindow)
        assertEquals(at("2026-09-09T10:00:00+08:00"), after.nextStartMillis)
        assertEquals(at("2026-09-09T18:00:00+08:00"), after.nextStopMillis)
    }

    @Test
    fun changingSettingsCanMoveAnActiveMonitorOutsideTheWindow() {
        val now = "2026-09-08T01:00:00+08:00"
        assertTrue(calculate(overnight, now).isWithinWindow)
        val replacement = overnight.copy(nightStartHour = 20, nightEndHour = 22)
        assertFalse(calculate(replacement, now).isWithinWindow)
    }

    @Test
    fun identicalStartAndEndFormAContinuousWindowIncludingTheBoundary() {
        val allDay = overnight.copy(
            nightStartHour = 9, nightStartMinute = 0,
            nightEndHour = 9, nightEndMinute = 0
        )
        for (time in listOf("00:00:00", "08:59:59", "09:00:00", "23:59:59")) {
            assertTrue(calculate(allDay, "2026-09-08T$time+08:00").isWithinWindow)
        }
        val boundary = calculate(allDay, "2026-09-08T09:00:00+08:00")
        assertEquals(at("2026-09-09T09:00:00+08:00"), boundary.nextStartMillis)
        assertEquals(boundary.nextStartMillis, boundary.nextStopMillis)
    }

    @Test
    fun duplicateBroadcastsDoNotShiftFutureBoundaries() {
        val first = calculate(overnight, "2026-09-08T23:30:00+08:00")
        val duplicate = calculate(overnight, "2026-09-08T23:30:15+08:00")
        assertEquals(first, duplicate)
    }

    @Test
    fun springClockChangeKeepsLocalEndTimeInsteadOfAddingTwentyFourHours() {
        val springNight = overnight.copy(nightStartHour = 23, nightStartMinute = 0,
            nightEndHour = 8)
        val window = calculate(springNight, "2026-03-07T23:00:00-05:00", newYork)

        assertTrue(window.isWithinWindow)
        assertEquals(at("2026-03-08T08:00:00-04:00"), window.nextStopMillis)
        assertEquals(at("2026-03-08T23:00:00-04:00"), window.nextStartMillis)
    }

    @Test
    fun nonexistentStartTimeUsesTheSameAdjustedInstantForMembershipAndAlarm() {
        val gapStart = overnight.copy(nightStartHour = 2, nightStartMinute = 30,
            nightEndHour = 4)
        val before = calculate(gapStart, "2026-03-08T03:15:00-04:00", newYork)
        assertFalse(before.isWithinWindow)
        assertEquals(at("2026-03-08T03:30:00-04:00"), before.nextStartMillis)
        assertTrue(calculate(gapStart, "2026-03-08T03:30:00-04:00", newYork).isWithinWindow)
    }

    @Test
    fun windowLostToSpringClockChangeIsSkipped() {
        val lostWindow = overnight.copy(nightStartHour = 2, nightStartMinute = 30,
            nightEndHour = 3)
        val window = calculate(lostWindow, "2026-03-08T03:15:00-04:00", newYork)
        assertFalse(window.isWithinWindow)
        assertEquals(at("2026-03-09T02:30:00-04:00"), window.nextStartMillis)
        assertEquals(at("2026-03-09T03:00:00-04:00"), window.nextStopMillis)
    }

    @Test
    fun repeatedAutumnHourDoesNotCloseAWindowThatAlreadyStarted() {
        val overlap = overnight.copy(nightStartHour = 1, nightStartMinute = 30,
            nightEndHour = 3)
        // The clock already passed 01:30 EDT, then rolled back to 01:15 EST.
        val window = calculate(overlap, "2026-11-01T01:15:00-05:00", newYork)
        assertTrue(window.isWithinWindow)
        assertEquals(at("2026-11-02T01:30:00-05:00"), window.nextStartMillis)
        assertEquals(at("2026-11-01T03:00:00-05:00"), window.nextStopMillis)
    }

    @Test
    fun timeZoneChangeRecomputesWindowAndFutureLocalBoundaries() {
        val now = Instant.parse("2026-09-08T15:00:00Z").toEpochMilli()
        val before = NightWindow.calculate(overnight, now, shanghai)
        val after = NightWindow.calculate(overnight, now, ZoneId.of("Asia/Tokyo"))
        assertFalse(before.isWithinWindow)
        assertEquals(at("2026-09-08T23:30:00+08:00"), before.nextStartMillis)
        assertTrue(after.isWithinWindow)
        assertEquals(at("2026-09-09T23:30:00+09:00"), after.nextStartMillis)
        assertEquals(at("2026-09-09T12:00:00+09:00"), after.nextStopMillis)
    }

    private fun calculate(settings: AppSettings, now: String, zone: ZoneId = shanghai) =
        NightWindow.calculate(settings, at(now), zone)

    private fun at(time: String): Long = ZonedDateTime.parse(time).toInstant().toEpochMilli()
}
