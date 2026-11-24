package com.example.sleepalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RingingAlarm(
    val id: String,
    val wakeAtMillis: Long,
    val isPreview: Boolean
)

class AlarmPlayerService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val soundSequence = AlarmSoundSequence<Uri>()
    private var preparationTimeout: Runnable? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val autoStopRunnable = Runnable {
        stopAlarm()
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getVibrator()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = mutableRinging.value
        val action = intent?.action
        if (action == ACTION_STOP_ALARM) {
            if (current == null || current.id == intent?.getStringExtra(EXTRA_RINGING_ID)) {
                stopAlarm()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val preview = BuildConfig.DEBUG && action == ACTION_PREVIEW_ALARM
        val wakeAtMillis = if (preview) {
            System.currentTimeMillis()
        } else {
            intent?.getLongExtra(EXTRA_WAKE_AT, 0L) ?: 0L
        }
        if (!preview && (action != ACTION_PLAY_ALARM || wakeAtMillis <= 0L ||
                !SettingsStore.load(this).planEnabled)
        ) {
            if (current == null) stopSelf()
            return START_NOT_STICKY
        }
        if (current != null && (preview || (!current.isPreview && current.wakeAtMillis == wakeAtMillis))) {
            return START_NOT_STICKY
        }

        val ringingAlarm = RingingAlarm(UUID.randomUUID().toString(), wakeAtMillis, preview)
        mutableRinging.value = ringingAlarm
        startAsForeground(ringingAlarm)
        playAlarm()
        startVibration()

        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, if (preview) PREVIEW_DURATION_MS else MAX_RING_DURATION_MS)

        return START_NOT_STICKY
    }

    private fun startAsForeground(alarm: RingingAlarm) {
        val notification = buildNotification(alarm)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(alarm: RingingAlarm): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            4001,
            stopIntent(this, alarm.id),
            PendingIntent.FLAG_IMMUTABLE
        )

        val ringingScreen = PendingIntent.getActivity(
            this,
            4002,
            AlarmRingingActivity.showIntent(this, alarm.id),
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (alarm.isPreview) R.string.alarm_preview_title else R.string.alarm_ringing_title
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(title))
            .setContentText(getString(R.string.alarm_ringing_notification, formatAlarmTime(alarm.wakeAtMillis)))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(ringingScreen)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.alarm_stop),
                stopPendingIntent
            )
        if (!alarm.isPreview) {
            // Android can show a heads-up alarm with the same stop action when full screen is unavailable.
            builder.setFullScreenIntent(ringingScreen, true)
        }
        return builder.build()
    }

    private fun playAlarm() {
        soundSequence.stop()
        stopMediaPlayerOnly()
        abandonAudioFocus()
        requestAudioFocus()

        val attempt = soundSequence.start(resolveAlarmUris())
        if (attempt == null) {
            abandonAudioFocus()
        } else {
            playSound(attempt)
        }
    }

    private fun playSound(attempt: AlarmSoundSequence.Attempt<Uri>) {
        if (!soundSequence.isCurrent(attempt)) return

        try {
            val player = MediaPlayer()
            // Own the player before any operation that can fail so it is always released.
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(this, attempt.source)
            player.isLooping = true
            player.setOnPreparedListener { preparedPlayer ->
                if (soundSequence.isCurrent(attempt) && mediaPlayer === preparedPlayer) {
                    clearPreparationTimeout()
                    try {
                        preparedPlayer.start()
                    } catch (e: Exception) {
                        onSoundFailed(attempt, "Unable to start alarm audio", e)
                    }
                }
            }
            player.setOnErrorListener { _, _, _ ->
                onSoundFailed(attempt, "Alarm audio playback failed")
                true
            }
            preparationTimeout = Runnable {
                onSoundFailed(attempt, "Alarm audio preparation timed out")
            }.also { handler.postDelayed(it, PREPARATION_TIMEOUT_MS) }
            player.prepareAsync()
        } catch (e: Exception) {
            onSoundFailed(attempt, "Unable to prepare alarm audio", e)
        }
    }

    private fun onSoundFailed(
        attempt: AlarmSoundSequence.Attempt<Uri>,
        message: String,
        error: Exception? = null
    ) {
        // Released players can still have callbacks queued; they must not restart an alarm.
        if (!soundSequence.isCurrent(attempt)) return
        Log.w(TAG, message, error)
        val nextAttempt = soundSequence.nextAfterFailure(attempt)
        stopMediaPlayerOnly()
        if (nextAttempt != null) {
            playSound(nextAttempt)
        } else {
            abandonAudioFocus()
            DebugNotifier.show(
                context = this,
                title = "SleepAlarm 调试",
                text = "所有闹铃铃声均播放失败，保留振动提醒",
                id = 9004
            )
        }
    }

    private fun resolveAlarmUris(): List<Uri> {
        val customUriString = SettingsStore.getRingtoneUriString(this)
        return buildList {
            if (!customUriString.isNullOrBlank()) {
                runCatching { Uri.parse(customUriString) }.getOrNull()?.let { add(it) }
            }
            // A valid URI can still refer to a deleted file or a revoked permission.
            listOf(
                RingtoneManager.TYPE_ALARM,
                RingtoneManager.TYPE_NOTIFICATION,
                RingtoneManager.TYPE_RINGTONE
            ).forEach { type -> RingtoneManager.getDefaultUri(type)?.let { add(it) } }
        }
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()

            am.requestAudioFocus(focusRequest)
            audioFocusRequest = focusRequest
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun startVibration() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 300, 500), 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attributes = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_ALARM)
                    .build()
                vibrator?.vibrate(effect, attributes)
            } else {
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                @Suppress("DEPRECATION")
                vibrator?.vibrate(effect, attributes)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun stopMediaPlayerOnly() {
        clearPreparationTimeout()
        val player = mediaPlayer
        mediaPlayer = null
        try {
            player?.setOnPreparedListener(null)
            player?.setOnErrorListener(null)
        } catch (_: Exception) {
        }
        // release() is valid even when setDataSource/prepareAsync failed or is still pending.
        try {
            player?.release()
        } catch (_: Exception) {
        }
    }

    private fun clearPreparationTimeout() {
        preparationTimeout?.let { handler.removeCallbacks(it) }
        preparationTimeout = null
    }

    private fun stopAlarm() {
        handler.removeCallbacks(autoStopRunnable)
        soundSequence.stop()
        stopVibration()
        stopMediaPlayerOnly()
        abandonAudioFocus()
        mutableRinging.value = null
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.alarm_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_PLAY_ALARM = "com.example.sleepalarm.ACTION_PLAY_ALARM"
        private const val ACTION_PREVIEW_ALARM = "com.example.sleepalarm.ACTION_PREVIEW_ALARM"
        private const val ACTION_STOP_ALARM = "com.example.sleepalarm.ACTION_STOP_ALARM"
        private const val EXTRA_WAKE_AT = "wake_at_millis"
        private const val EXTRA_RINGING_ID = "ringing_id"

        internal const val CHANNEL_ID = "alarm_sound_channel"
        private const val NOTIFICATION_ID = 2001
        private const val PREPARATION_TIMEOUT_MS = 10_000L
        private const val PREVIEW_DURATION_MS = 10_000L
        private const val MAX_RING_DURATION_MS = 120_000L
        private const val TAG = "AlarmPlayerService"

        private val mutableRinging = MutableStateFlow<RingingAlarm?>(null)
        internal val ringing = mutableRinging.asStateFlow()

        internal fun playIntent(context: Context, wakeAtMillis: Long): Intent =
            Intent(context, AlarmPlayerService::class.java)
                .setAction(ACTION_PLAY_ALARM)
                .putExtra(EXTRA_WAKE_AT, wakeAtMillis)

        internal fun previewIntent(context: Context): Intent =
            Intent(context, AlarmPlayerService::class.java).setAction(ACTION_PREVIEW_ALARM)

        internal fun stop(context: Context, alarmId: String) {
            if (mutableRinging.value?.id == alarmId) {
                context.startService(stopIntent(context, alarmId))
            }
        }

        private fun stopIntent(context: Context, alarmId: String): Intent =
            Intent(context, AlarmPlayerService::class.java)
                .setAction(ACTION_STOP_ALARM)
                .setData(Uri.parse("sleepalarm://ringing/$alarmId/stop"))
                .putExtra(EXTRA_RINGING_ID, alarmId)
    }
}
