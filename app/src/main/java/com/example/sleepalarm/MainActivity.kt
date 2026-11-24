package com.example.sleepalarm

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sleepalarm.ui.theme.SleepAlarmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class AlarmPermission { EXACT, NOTIFICATIONS, FULL_SCREEN }

internal data class AlarmAccess(
    val exact: Boolean = false,
    val notifications: Boolean = false,
    val fullScreen: Boolean = false
)

class MainActivity : ComponentActivity() {
    private var settings by mutableStateOf(AppSettings())
    private var ringtoneTitle by mutableStateOf("")
    private var access by mutableStateOf(AlarmAccess())
    private var nowMillis by mutableLongStateOf(System.currentTimeMillis())

    private val notificationRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAccess() }

    private val ringtonePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                SettingsStore.saveRingtoneUri(this, RingtoneHelper.pickedUri(result.data)?.toString())
                refreshSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshSettings()
        WakeAlarmStateStore.refresh(this)
        setContent {
            SleepAlarmTheme {
                val wakeState by WakeAlarmStateStore.state.collectAsState()
                val monitoring by SleepMonitorService.monitoring.collectAsState()
                val ringing by AlarmPlayerService.ringing.collectAsState()
                AlarmHomeScreen(
                    settings = settings,
                    access = access,
                    ringtoneTitle = ringtoneTitle,
                    nowMillis = nowMillis,
                    wakeState = wakeState,
                    monitoring = monitoring,
                    ringing = ringing,
                    onTogglePlan = ::togglePlan,
                    onEditTime = ::editTime,
                    onEditDuration = { saveSettings(settings.copy(sleepDurationMinutes = it)) },
                    onPickRingtone = ::pickRingtone,
                    onPermission = ::requestAccess,
                    onResumeMonitoring = { NightScheduler.rescheduleIfNeeded(this) },
                    onTryRingtone = if (BuildConfig.DEBUG) ::tryRingtone else null,
                    onStopRinging = { AlarmPlayerService.stop(this, it.id) }
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    nowMillis = System.currentTimeMillis()
                    delay(60_000L - (nowMillis % 60_000L))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val hadExactAccess = access.exact
        refreshAccess()
        WakeAlarmStateStore.refresh(this)
        if (settings.planEnabled && hadExactAccess != access.exact) {
            NightScheduler.rescheduleIfNeeded(this)
        }
    }

    private fun refreshSettings() {
        settings = SettingsStore.load(this)
        ringtoneTitle = RingtoneHelper.getRingtoneTitle(this, settings.ringtoneUriString)
    }

    private fun refreshAccess() {
        val notifications = getSystemService(NotificationManager::class.java)
        val alarmChannel = notifications.getNotificationChannel(AlarmPlayerService.CHANNEL_ID)
        access = AlarmAccess(
            exact = WakeAlarmManager.canScheduleExactAlarms(this),
            notifications = notifications.areNotificationsEnabled() &&
                (alarmChannel == null || alarmChannel.importance >= NotificationManager.IMPORTANCE_HIGH),
            fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                notifications.canUseFullScreenIntent()
        )
    }

    private fun togglePlan(enabled: Boolean) {
        if (enabled) {
            saveSettings(settings.copy(planEnabled = true))
        } else {
            NightScheduler.cancelDailyWindow(this)
            settings = SettingsStore.load(this)
        }
    }

    private fun saveSettings(updated: AppSettings) {
        SettingsStore.save(this, updated)
        settings = updated
        if (updated.planEnabled) NightScheduler.scheduleDailyWindow(this)
    }

    private fun editTime(start: Boolean) {
        val hour = if (start) settings.nightStartHour else settings.nightEndHour
        val minute = if (start) settings.nightStartMinute else settings.nightEndMinute
        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            val updated = if (start) {
                settings.copy(nightStartHour = selectedHour, nightStartMinute = selectedMinute)
            } else {
                settings.copy(nightEndHour = selectedHour, nightEndMinute = selectedMinute)
            }
            saveSettings(updated)
        }, hour, minute, true).show()
    }

    private fun tryRingtone() {
        ContextCompat.startForegroundService(this, AlarmPlayerService.previewIntent(this))
    }

    private fun pickRingtone() {
        try {
            ringtonePicker.launch(RingtoneHelper.buildPickerIntent(settings.ringtoneUriString))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ringtone_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAccess(permission: AlarmPermission) {
        val packageUri = Uri.fromParts("package", packageName, null)
        when (permission) {
            AlarmPermission.EXACT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri))
            }
            AlarmPermission.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri))
            }
            AlarmPermission.NOTIFICATIONS -> requestNotifications()
        }
    }

    private fun requestNotifications() {
        val notifications = getSystemService(NotificationManager::class.java)
        if (notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(AlarmPlayerService.CHANNEL_ID) != null
        ) {
            openSettings(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmPlayerService.CHANNEL_ID)
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val preferences = getPreferences(MODE_PRIVATE)
            val asked = preferences.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
            if (!asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                preferences.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
                notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        openSettings(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.alarm_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
