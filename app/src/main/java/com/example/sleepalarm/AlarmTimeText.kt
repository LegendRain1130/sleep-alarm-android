package com.example.sleepalarm

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

internal fun formatAlarmTime(timeMillis: Long): String =
    clockFormat.format(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()))

internal fun formatAlarmTime(hour: Int, minute: Int): String =
    clockFormat.format(LocalTime.of(hour, minute))
