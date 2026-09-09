package com.example.sleepalarm

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal data class NightWindowSchedule(
    val isWithinWindow: Boolean,
    val nextStartMillis: Long,
    val nextStopMillis: Long
)

internal object NightWindow {
    fun calculate(
        settings: AppSettings,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): NightWindowSchedule {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val startTime = LocalTime.of(settings.nightStartHour, settings.nightStartMinute)
        val endTime = LocalTime.of(settings.nightEndHour, settings.nightEndMinute)
        // Matching times retain the app's existing meaning: a continuous, all-day window.
        val crossesMidnight = !startTime.isBefore(endTime)

        // Resolve actual instants first, so membership and alarms agree across DST changes.
        // A missing local time moves forward by the gap; an overlapping time uses its first
        // occurrence. A window entirely lost to a clock change is skipped for that date.
        val windows = (-1L..2L).map { dayOffset ->
            val startDate = today.plusDays(dayOffset)
            val endDate = if (crossesMidnight) startDate.plusDays(1) else startDate
            val start = startDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
            val end = endDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
            start to end
        }.filter { (start, end) -> end > start }

        return NightWindowSchedule(
            isWithinWindow = windows.any { (start, end) -> nowMillis >= start && nowMillis < end },
            nextStartMillis = windows.map { it.first }.filter { it > nowMillis }.min(),
            nextStopMillis = windows.map { it.second }.filter { it > nowMillis }.min()
        )
    }
}
