package com.example.sleepalarm

import android.content.Context

data class AppSettings(
    val nightStartHour: Int = 23,
    val nightStartMinute: Int = 30,
    val nightEndHour: Int = 12,
    val nightEndMinute: Int = 0,
    val sleepDurationMinutes: Int = 420,
    val ringtoneUriString: String? = null,
    val planEnabled: Boolean = false
)

object SettingsStore {

    private const val PREFS_NAME = "sleep_alarm_prefs"

    private const val KEY_NIGHT_START_HOUR = "night_start_hour"
    private const val KEY_NIGHT_START_MINUTE = "night_start_minute"
    private const val KEY_NIGHT_END_HOUR = "night_end_hour"
    private const val KEY_NIGHT_END_MINUTE = "night_end_minute"
    private const val KEY_SLEEP_DURATION_MINUTES = "sleep_duration_minutes"
    private const val KEY_RINGTONE_URI = "ringtone_uri"
    private const val KEY_PLAN_ENABLED = "plan_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): AppSettings {
        val p = prefs(context)

        return AppSettings(
            nightStartHour = p.getInt(KEY_NIGHT_START_HOUR, 23),
            nightStartMinute = p.getInt(KEY_NIGHT_START_MINUTE, 30),
            nightEndHour = p.getInt(KEY_NIGHT_END_HOUR, 12),
            nightEndMinute = p.getInt(KEY_NIGHT_END_MINUTE, 0),
            sleepDurationMinutes = p.getInt(KEY_SLEEP_DURATION_MINUTES, 420),
            ringtoneUriString = p.getString(KEY_RINGTONE_URI, null),
            planEnabled = p.getBoolean(KEY_PLAN_ENABLED, false)
        )
    }

    fun save(context: Context, settings: AppSettings) {
        prefs(context).edit()
            .putInt(KEY_NIGHT_START_HOUR, settings.nightStartHour)
            .putInt(KEY_NIGHT_START_MINUTE, settings.nightStartMinute)
            .putInt(KEY_NIGHT_END_HOUR, settings.nightEndHour)
            .putInt(KEY_NIGHT_END_MINUTE, settings.nightEndMinute)
            .putInt(KEY_SLEEP_DURATION_MINUTES, settings.sleepDurationMinutes)
            .putString(KEY_RINGTONE_URI, settings.ringtoneUriString)
            .putBoolean(KEY_PLAN_ENABLED, settings.planEnabled)
            .apply()
    }

    fun saveRingtoneUri(context: Context, uriString: String?) {
        prefs(context).edit()
            .putString(KEY_RINGTONE_URI, uriString)
            .apply()
    }

    fun getRingtoneUriString(context: Context): String? {
        return prefs(context).getString(KEY_RINGTONE_URI, null)
    }
}