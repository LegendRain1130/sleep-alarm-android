package com.example.sleepalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import android.app.PendingIntent
import android.util.Log
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
        DebugNotifier.show(
            context = this,
            title = "SleepAlarm 调试",
            text = "AlarmPlayerService 已启动",
            id = 9002
        )

        if (intent?.action == ACTION_STOP_ALARM) {
            stopAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        playAlarm()
        startVibration()

        handler.removeCallbacks(autoStopRunnable)
        handler.postDelayed(autoStopRunnable, 120_000L)

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()

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

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AlarmPlayerService::class.java).apply {
            action = ACTION_STOP_ALARM
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            4001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            4002,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SleepAlarm")
            .setContentText("闹铃响了")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "停止闹铃",
                stopPendingIntent
            )
            .build()
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 500, 300, 500, 300, 500),
                        0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 500, 300, 500, 300, 500), 0)
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
                "Alarm Sound",
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
        const val ACTION_STOP_ALARM = "com.example.sleepalarm.ACTION_STOP_ALARM"

        private const val CHANNEL_ID = "alarm_sound_channel"
        private const val NOTIFICATION_ID = 2001
        private const val PREPARATION_TIMEOUT_MS = 10_000L
        private const val TAG = "AlarmPlayerService"
    }
}
