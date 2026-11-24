package com.example.sleepalarm

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

object RingtoneHelper {
    fun buildPickerIntent(existingUriString: String?): Intent {
        val existingUri = existingUriString?.let { Uri.parse(it) } ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        }
    }

    internal fun pickedUri(data: Intent?): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }

    fun getRingtoneTitle(context: Context, uriString: String?): String {
        val defaultTitle = context.getString(R.string.ringtone_default)
        if (uriString.isNullOrBlank()) return defaultTitle
        return try {
            val uri = Uri.parse(uriString)
            if (RingtoneManager.isDefault(uri)) return defaultTitle
            RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: defaultTitle
        } catch (_: Exception) {
            defaultTitle
        }
    }
}
