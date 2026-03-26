package com.example.sleepalarm

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri

object RingtoneHelper {

    fun buildPickerIntent(existingUriString: String?): Intent {
        val existingUri = existingUriString?.let { Uri.parse(it) }

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择本体闹铃铃声")
        }
    }

    fun getRingtoneTitle(context: Context, uriString: String?): String {
        if (uriString.isNullOrBlank()) {
            return "默认系统闹铃"
        }

        return try {
            val uri = Uri.parse(uriString)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.getTitle(context) ?: "默认系统闹铃"
        } catch (_: Exception) {
            "默认系统闹铃"
        }
    }
}