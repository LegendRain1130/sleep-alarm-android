package com.example.sleepalarm

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun AlarmHomeScreen(
    settings: AppSettings,
    access: AlarmAccess,
    ringtoneTitle: String,
    nowMillis: Long,
    wakeState: WakeAlarmState,
    monitoring: Boolean,
    ringing: RingingAlarm?,
    onTogglePlan: (Boolean) -> Unit,
    onEditTime: (Boolean) -> Unit,
    onEditDuration: (Int) -> Unit,
    onPickRingtone: () -> Unit,
    onPermission: (AlarmPermission) -> Unit,
    onResumeMonitoring: () -> Unit,
    onTryRingtone: (() -> Unit)?,
    onStopRinging: (RingingAlarm) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val window = remember(settings, nowMillis) { NightWindow.calculate(settings, nowMillis) }
    var durationDraft by rememberSaveable { mutableStateOf<String?>(null) }
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceVariant) {
                    NightEmblem(colors.primary, Modifier.padding(9.dp).size(23.dp))
                }
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.weight(1f),
                    color = colors.onBackground,
                    style = MaterialTheme.typography.titleLarge
                )
                if (onTryRingtone != null) {
                    OutlinedButton(
                        onClick = onTryRingtone,
                        enabled = ringing == null,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.alarm_preview))
                    }
                }
            }
            PlanCard(settings, access, window, nowMillis, wakeState, monitoring, ringing, onTogglePlan,
                onResumeMonitoring, onStopRinging)
            AccessCard(access, onPermission)
            SettingsCard(
                settings, ringtoneTitle,
                onEditTime = onEditTime,
                onEditDuration = { durationDraft = settings.sleepDurationMinutes.toString() },
                onPickRingtone = onPickRingtone
            )
        }
    }

    durationDraft?.let { draft ->
        val minutes = draft.toIntOrNull()?.takeIf { it in 1..1440 }
        AlertDialog(
            title = { Text(stringResource(R.string.sleep_duration)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { input -> durationDraft = input.filter { it in '0'..'9' }.take(4) },
                    label = { Text(stringResource(R.string.duration_minutes_label)) },
                    supportingText = { Text(stringResource(R.string.duration_range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = minutes != null,
                    onClick = {
                        minutes?.let(onEditDuration)
                        durationDraft = null
                    }
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { durationDraft = null }) { Text(stringResource(R.string.cancel)) }
            },
            onDismissRequest = { durationDraft = null }
        )
    }
}

private data class PlanSummary(@param:StringRes val label: Int, val timeMillis: Long = 0L)

@Composable
private fun PlanCard(
    settings: AppSettings,
    access: AlarmAccess,
    window: NightWindowSchedule,
    nowMillis: Long,
    wakeState: WakeAlarmState,
    monitoring: Boolean,
    ringing: RingingAlarm?,
    onTogglePlan: (Boolean) -> Unit,
    onResumeMonitoring: () -> Unit,
    onStopRinging: (RingingAlarm) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val summary = when {
        ringing != null -> PlanSummary(
            if (ringing.isPreview) R.string.alarm_preview_title else R.string.alarm_ringing_title,
            ringing.wakeAtMillis
        )
        !settings.planEnabled -> PlanSummary(R.string.plan_off)
        !access.exact -> PlanSummary(R.string.plan_requires_alarm_access)
        wakeState.wakeAtMillis != 0L -> PlanSummary(R.string.plan_scheduled, wakeState.wakeAtMillis)
        !window.isWithinWindow -> PlanSummary(R.string.plan_next_start, window.nextStartMillis)
        !monitoring -> PlanSummary(R.string.plan_paused)
        else -> PlanSummary(
            R.string.plan_projected_wake,
            SleepMonitorPolicy.screenOff(WakeAlarmState(), nowMillis, settings.sleepDurationMinutes, true).wakeAtMillis
        )
    }

    Surface(shape = RoundedCornerShape(28.dp), color = colors.surface) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(colors.surfaceVariant, colors.surface)))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val label = stringResource(R.string.night_plan)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = typography.titleMedium, color = colors.onSurface)
                Switch(
                    checked = settings.planEnabled,
                    onCheckedChange = onTogglePlan,
                    modifier = Modifier.semantics { contentDescription = label }
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(summary.label),
                    color = if (ringing != null) colors.secondary else colors.primary,
                    style = typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (summary.timeMillis != 0L) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = formatAlarmTime(summary.timeMillis),
                            color = colors.onSurface,
                            style = typography.displayLarge,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = relativeDate(nowMillis, summary.timeMillis),
                            modifier = Modifier.padding(bottom = 10.dp),
                            color = colors.onSurfaceVariant,
                            style = typography.labelLarge
                        )
                    }
                }
            }
            when {
                ringing != null -> Button(
                    onClick = { onStopRinging(ringing) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.secondary,
                        contentColor = colors.onSecondary
                    )
                ) { Text(stringResource(R.string.alarm_stop)) }
                summary.label == R.string.plan_paused -> TextButton(onClick = onResumeMonitoring) {
                    Text(stringResource(R.string.plan_resume))
                }
                settings.planEnabled && wakeState.cancelledWakeAtMillis != 0L ->
                    Text(
                        text = stringResource(R.string.plan_cancelled_recently,
                            formatAlarmTime(wakeState.cancelledWakeAtMillis)),
                        color = colors.onSurfaceVariant,
                        style = typography.bodyMedium
                    )
                settings.planEnabled && access.exact -> Text(
                    text = stringResource(R.string.plan_screen_rule),
                    color = colors.onSurfaceVariant,
                    style = typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun relativeDate(nowMillis: Long, scheduledMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val date = Instant.ofEpochMilli(scheduledMillis).atZone(zone).toLocalDate()
    return when (date) {
        today -> stringResource(R.string.today)
        today.plusDays(1) -> stringResource(R.string.tomorrow)
        else -> stringResource(R.string.month_day, date.monthValue, date.dayOfMonth)
    }
}

@Composable
private fun AccessCard(access: AlarmAccess, onPermission: (AlarmPermission) -> Unit) {
    val permission = when {
        !access.exact -> AlarmPermission.EXACT
        !access.notifications -> AlarmPermission.NOTIFICATIONS
        !access.fullScreen -> AlarmPermission.FULL_SCREEN
        else -> return
    }
    val description = when (permission) {
        AlarmPermission.EXACT -> R.string.access_exact_description
        AlarmPermission.NOTIFICATIONS -> R.string.access_notifications_description
        AlarmPermission.FULL_SCREEN -> R.string.access_full_screen_description
    }
    val action = when (permission) {
        AlarmPermission.EXACT -> R.string.access_exact_action
        AlarmPermission.NOTIFICATIONS -> R.string.access_notifications_action
        AlarmPermission.FULL_SCREEN -> R.string.access_full_screen_action
    }
    SettingsSurface {
        Text(
            stringResource(description),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            modifier = Modifier.padding(start = 6.dp),
            onClick = { onPermission(permission) }
        ) { Text(stringResource(action)) }
    }
}

@Composable
private fun SettingsCard(
    settings: AppSettings,
    ringtoneTitle: String,
    onEditTime: (Boolean) -> Unit,
    onEditDuration: () -> Unit,
    onPickRingtone: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.alarm_settings), color = colors.onBackground,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SettingsSurface {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TimeTile(
                    label = stringResource(R.string.window_start),
                    value = formatAlarmTime(settings.nightStartHour, settings.nightStartMinute),
                    onClick = { onEditTime(true) },
                    modifier = Modifier.weight(1f)
                )
                TimeTile(
                    label = stringResource(R.string.window_end),
                    value = formatAlarmTime(settings.nightEndHour, settings.nightEndMinute),
                    onClick = { onEditTime(false) },
                    modifier = Modifier.weight(1f)
                )
            }
            val allDay = settings.nightStartHour == settings.nightEndHour &&
                settings.nightStartMinute == settings.nightEndMinute
            Text(
                text = stringResource(if (allDay) R.string.window_all_day else R.string.window_keep_booking),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
            HorizontalDivider(color = colors.outlineVariant)
            SettingRow(stringResource(R.string.sleep_duration), durationLabel(settings.sleepDurationMinutes), onEditDuration)
            HorizontalDivider(color = colors.outlineVariant)
            SettingRow(stringResource(R.string.alarm_ringtone), ringtoneTitle, onPickRingtone)
        }
    }
}

@Composable
private fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun TimeTile(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = colors.surfaceVariant
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(value, color = colors.onSurface, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                value,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Canvas(Modifier.size(12.dp)) {
            val mid = size.width * 0.62f
            val start = size.width * 0.28f
            drawLine(colors.onSurfaceVariant, Offset(start, size.height * 0.21f),
                Offset(mid, size.height * 0.5f), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(colors.onSurfaceVariant, Offset(mid, size.height * 0.5f),
                Offset(start, size.height * 0.79f), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.duration_minutes, remainder)
        remainder == 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, remainder)
    }
}
