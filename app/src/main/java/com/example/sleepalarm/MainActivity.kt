package com.example.sleepalarm

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sleepalarm.ui.theme.SleepAlarmTheme

private const val SHOW_DEBUG_TOOLS = false

class MainActivity : ComponentActivity() {

    private var ringtoneSummary by mutableStateOf("默认系统闹铃")

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val pickedUri = if (Build.VERSION.SDK_INT >= 33) {
                    result.data?.getParcelableExtra(
                        RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                        Uri::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }

                SettingsStore.saveRingtoneUri(this, pickedUri?.toString())
                ringtoneSummary = RingtoneHelper.getRingtoneTitle(this, pickedUri?.toString())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ringtoneSummary = RingtoneHelper.getRingtoneTitle(
            this,
            SettingsStore.getRingtoneUriString(this)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            SleepAlarmTheme {
                AppScreen(
                    ringtoneSummary = ringtoneSummary,
                    onPickRingtone = {
                        val existingUriString = SettingsStore.getRingtoneUriString(this)
                        ringtonePickerLauncher.launch(
                            RingtoneHelper.buildPickerIntent(existingUriString)
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun AppScreen(
    ringtoneSummary: String,
    onPickRingtone: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val initialSettings = remember { SettingsStore.load(context) }

    var nightStartHour by remember { mutableStateOf(initialSettings.nightStartHour) }
    var nightStartMinute by remember { mutableStateOf(initialSettings.nightStartMinute) }
    var nightEndHour by remember { mutableStateOf(initialSettings.nightEndHour) }
    var nightEndMinute by remember { mutableStateOf(initialSettings.nightEndMinute) }
    var sleepDurationText by remember {
        mutableStateOf(initialSettings.sleepDurationMinutes.toString())
    }
    var status by remember {
        mutableStateOf(if (initialSettings.planEnabled) "夜间计划已开启" else "夜间计划未开启")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { focusManager.clearFocus() }
                )
            }
            .padding(24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Sleep Alarm",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "夜间睡眠闹铃",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                context.stopService(
                    Intent(context, AlarmPlayerService::class.java).apply {
                        action = AlarmPlayerService.ACTION_STOP_ALARM
                    }
                )
                status = "已尝试停止响铃"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("停止当前响铃")
        }

        Spacer(modifier = Modifier.height(20.dp))

        TimeSettingRow(
            label = "夜间开始时间",
            timeText = formatHm(nightStartHour, nightStartMinute),
            onClick = {
                focusManager.clearFocus()
                showTimePicker(
                    context = context,
                    initialHour = nightStartHour,
                    initialMinute = nightStartMinute
                ) { h, m ->
                    nightStartHour = h
                    nightStartMinute = m
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        TimeSettingRow(
            label = "夜间结束时间",
            timeText = formatHm(nightEndHour, nightEndMinute),
            onClick = {
                focusManager.clearFocus()
                showTimePicker(
                    context = context,
                    initialHour = nightEndHour,
                    initialMinute = nightEndMinute
                ) { h, m ->
                    nightEndHour = h
                    nightEndMinute = m
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = sleepDurationText,
            onValueChange = { input ->
                sleepDurationText = input.filter { it.isDigit() }
            },
            label = { Text("睡眠时长（分钟）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text("本体铃声：$ringtoneSummary")

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                focusManager.clearFocus()
                onPickRingtone()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("选择本体铃声")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                focusManager.clearFocus()

                val duration = sleepDurationText.toIntOrNull()
                if (duration == null || duration <= 0) {
                    status = "输入有误：请检查睡眠时长"
                    return@Button
                }

                val old = SettingsStore.load(context)
                SettingsStore.save(
                    context,
                    old.copy(
                        nightStartHour = nightStartHour,
                        nightStartMinute = nightStartMinute,
                        nightEndHour = nightEndHour,
                        nightEndMinute = nightEndMinute,
                        sleepDurationMinutes = duration,
                        planEnabled = true
                    )
                )

                NightScheduler.scheduleDailyWindow(context)
                status = "已保存设置并安排夜间计划"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存设置并安排夜间计划")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                focusManager.clearFocus()

                NightScheduler.cancelDailyWindow(context)
                WakeAlarmManager.cancelInternalWake(context)

                context.stopService(Intent(context, SleepMonitorService::class.java))
                context.stopService(Intent(context, AlarmPlayerService::class.java))

                status = "已关闭夜间计划并停止当前监测"
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("关闭夜间计划")
        }

        if (SHOW_DEBUG_TOOLS) {
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()

                    val duration = sleepDurationText.toIntOrNull()
                    if (duration == null || duration <= 0) {
                        status = "输入有误：请检查睡眠时长"
                        return@Button
                    }

                    val old = SettingsStore.load(context)
                    SettingsStore.save(
                        context,
                        old.copy(
                            nightStartHour = nightStartHour,
                            nightStartMinute = nightStartMinute,
                            nightEndHour = nightEndHour,
                            nightEndMinute = nightEndMinute,
                            sleepDurationMinutes = duration
                        )
                    )

                    val intent = Intent(context, SleepMonitorService::class.java)
                    ContextCompat.startForegroundService(context, intent)
                    status = "已手动启动监测（调试）"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("手动开始监测（调试）")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()

                    WakeAlarmManager.cancelInternalWake(context)
                    context.stopService(Intent(context, SleepMonitorService::class.java))
                    status = "已停止当前监测"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("停止当前监测")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()

                    WakeAlarmManager.scheduleInternalWake(
                        context,
                        System.currentTimeMillis() + 2 * 60 * 1000L
                    )
                    status = "已创建 2 分钟后的本体测试闹铃"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("测试本体闹铃（2分钟）")
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "当前状态：$status",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "提示：夜间开始后会自动进入监测；每次熄屏都会按当前时间与睡眠时长重新预定闹铃，亮屏则取消。",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TimeSettingRow(
    label: String,
    timeText: String,
    onClick: () -> Unit
) {
    Text(label)
    Spacer(modifier = Modifier.height(6.dp))
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(timeText)
    }
}

private fun formatHm(hour: Int, minute: Int): String {
    return "%02d:%02d".format(hour, minute)
}

private fun showTimePicker(
    context: Context,
    initialHour: Int,
    initialMinute: Int,
    onPicked: (Int, Int) -> Unit
) {
    TimePickerDialog(
        context,
        { _, hourOfDay, minute ->
            onPicked(hourOfDay, minute)
        },
        initialHour,
        initialMinute,
        true
    ).show()
}