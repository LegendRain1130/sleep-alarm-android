package com.example.sleepalarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.sleepalarm.ui.theme.DawnBackground
import com.example.sleepalarm.ui.theme.SleepAlarmTheme

class AlarmRingingActivity : ComponentActivity() {
    private var alarmId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!selectAlarm(intent)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContent {
            SleepAlarmTheme {
                val ringing by AlarmPlayerService.ringing.collectAsState()
                val alarm = ringing?.takeIf { it.id == alarmId }
                LaunchedEffect(alarm) {
                    if (alarm == null) finish()
                }
                if (alarm != null) {
                    AlarmRingingScreen(alarm) { AlarmPlayerService.stop(this, alarm.id) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectAlarm(intent)
    }

    private fun selectAlarm(intent: Intent): Boolean {
        val current = AlarmPlayerService.ringing.value
        if (current == null || current.id != intent.data?.lastPathSegment) {
            finish()
            return false
        }
        alarmId = current.id
        return true
    }

    companion object {
        internal fun showIntent(context: Context, alarmId: String): Intent =
            Intent(context, AlarmRingingActivity::class.java)
                .setData(Uri.parse("sleepalarm://ringing/$alarmId"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun AlarmRingingScreen(alarm: RingingAlarm, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val title = if (alarm.isPreview) R.string.alarm_preview_title else R.string.alarm_ringing_title
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.background, DawnBackground, colors.background)))
            .safeDrawingPadding()
    ) {
        val screenHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = screenHeight)
                .padding(horizontal = 28.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.app_name), color = colors.onSurfaceVariant)
            Column(
                modifier = Modifier.padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(colors.secondary.copy(alpha = 0.18f), style = Stroke(1.dp.toPx()))
                        drawCircle(colors.secondary.copy(alpha = 0.28f), radius = size.minDimension * 0.37f,
                            style = Stroke(1.dp.toPx()))
                    }
                    NightEmblem(colors.secondary, Modifier.size(36.dp))
                }
                Text(
                    text = formatAlarmTime(alarm.wakeAtMillis),
                    color = colors.onBackground,
                    style = MaterialTheme.typography.displayLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(title),
                    color = colors.secondary,
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.secondary,
                    contentColor = colors.onSecondary
                )
            ) {
                Text(
                    stringResource(R.string.alarm_stop),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
