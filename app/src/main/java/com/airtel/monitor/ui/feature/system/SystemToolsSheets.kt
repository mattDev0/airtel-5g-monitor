@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.airtel.monitor.ui.feature.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.data.model.LogGrade
import com.airtel.monitor.data.model.LogSettingsConfig
import com.airtel.monitor.data.model.RouterTimeConfig
import com.airtel.monitor.data.model.ScheduledRebootConfig
import com.airtel.monitor.data.model.WanLinkDetectConfig
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.theme.AirtelTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScheduledRebootSheet(
    current: ScheduledRebootConfig?,
    saving: Boolean,
    onSave: (ScheduledRebootConfig, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var enabled by remember(current) { mutableStateOf(current?.enabled ?: false) }
    var hourText by remember(current) { mutableStateOf((current?.hour ?: 3).toString().padStart(2, '0')) }
    var minuteText by remember(current) { mutableStateOf((current?.minute ?: 0).toString().padStart(2, '0')) }
    var selectedDays by remember(current) {
        mutableStateOf(current?.weekDays ?: setOf(1, 2, 3, 4, 5, 6, 0))
    }
    var localError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = stringResource(R.string.system_schedule_sheet_title),
                style = MaterialTheme.typography.titleLarge
            )
            SettingSwitchRow(
                title = stringResource(R.string.system_schedule_enable),
                checked = enabled,
                enabled = !saving,
                onCheckedChange = {
                    enabled = it
                    localError = null
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { hourText = it.filter(Char::isDigit).take(2); localError = null },
                    label = { Text(stringResource(R.string.system_schedule_hour)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled && !saving,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minuteText,
                    onValueChange = { minuteText = it.filter(Char::isDigit).take(2); localError = null },
                    label = { Text(stringResource(R.string.system_schedule_minute)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled && !saving,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = stringResource(R.string.system_schedule_days),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val days = remember {
                listOf(
                    1 to "Mon",
                    2 to "Tue",
                    3 to "Wed",
                    4 to "Thu",
                    5 to "Fri",
                    6 to "Sat",
                    0 to "Sun"
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                days.forEach { (code, label) ->
                    val active = code in selectedDays
                    FilterChip(
                        selected = active,
                        enabled = enabled && !saving,
                        onClick = {
                            selectedDays = if (active) selectedDays - code else selectedDays + code
                            localError = null
                        },
                        label = { Text(label) }
                    )
                }
            }

            if (!localError.isNullOrBlank()) {
                Text(
                    text = localError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(spacing.sm))
                Button(
                    onClick = {
                        val h = hourText.toIntOrNull()
                        val m = minuteText.toIntOrNull()
                        when {
                            h == null || h !in 0..23 -> localError = "Hour must be 0 to 23"
                            m == null || m !in 0..59 -> localError = "Minute must be 0 to 59"
                            enabled && selectedDays.isEmpty() -> localError = "Select at least one weekday"
                            else -> onSave(
                                ScheduledRebootConfig(
                                    enabled = enabled,
                                    hour = h,
                                    minute = m,
                                    weekDays = selectedDays
                                )
                            ) { err -> localError = err }
                        }
                    },
                    enabled = !saving
                ) {
                    if (saving) {
                        LoadingIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                    }
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
fun SystemLogsSheet(
    logSettings: LogSettingsConfig?,
    logLines: List<String>,
    loading: Boolean,
    saving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSaveSettings: (LogGrade, String, onError: (String) -> Unit) -> Unit,
    onRequestClearLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var grade by remember(logSettings) { mutableStateOf(logSettings?.grade ?: LogGrade.DEFAULT) }
    var sizeKb by remember(logSettings) { mutableStateOf((logSettings?.sizeKb ?: 500).toString()) }
    var localError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.system_logs_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh, enabled = !loading && !saving) {
                    if (loading) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_settings)
                        )
                    }
                }
                IconButton(onClick = onRequestClearLogs, enabled = !loading && !saving) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.system_logs_clear_btn),
                        tint = colors.error
                    )
                }
            }

            Text(
                text = stringResource(R.string.system_logs_grade_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                LogGrade.entries.forEach { option ->
                    FilterChip(
                        selected = grade == option,
                        enabled = !saving,
                        onClick = { grade = option; localError = null },
                        label = { Text(option.label) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = sizeKb,
                    onValueChange = { sizeKb = it.filter(Char::isDigit).take(4); localError = null },
                    label = { Text(stringResource(R.string.system_logs_size_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        onSaveSettings(grade, sizeKb) { err -> localError = err }
                    },
                    enabled = !saving
                ) {
                    Text(stringResource(R.string.system_logs_save_settings))
                }
            }

            val shownError = localError ?: errorMessage
            if (!shownError.isNullOrBlank()) {
                Text(
                    text = shownError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Surface(
                shape = AirtelTheme.shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 300.dp)
            ) {
                if (logLines.isEmpty() && !loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.system_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChangePasswordSheet(
    saving: Boolean,
    errorMessage: String?,
    onSubmit: (oldPass: String, newPass: String, confirmPass: String) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var revealPasswords by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.system_password_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { revealPasswords = !revealPasswords }) {
                    Icon(
                        imageVector = if (revealPasswords) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_reveal)
                    )
                }
            }

            Surface(
                shape = AirtelTheme.shapes.tile,
                color = colors.warning.copy(alpha = 0.14f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.system_password_tier2_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                    modifier = Modifier.padding(spacing.sm)
                )
            }

            Text(
                text = stringResource(R.string.system_password_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val visual = if (revealPasswords) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = oldPass,
                onValueChange = { oldPass = it },
                label = { Text(stringResource(R.string.system_password_current)) },
                visualTransformation = visual,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !saving,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newPass,
                onValueChange = { newPass = it },
                label = { Text(stringResource(R.string.system_password_new)) },
                visualTransformation = visual,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !saving,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirmPass,
                onValueChange = { confirmPass = it },
                label = { Text(stringResource(R.string.system_password_confirm)) },
                visualTransformation = visual,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !saving,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(spacing.sm))
                Button(
                    onClick = { onSubmit(oldPass, newPass, confirmPass) },
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (saving) {
                        LoadingIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(spacing.xs))
                    }
                    Text(stringResource(R.string.system_password_submit))
                }
            }
        }
    }
}

private val COMMON_TIMEZONES = listOf(
    "UTC0" to "GMT+00:00",
    "UTC-1" to "GMT+01:00 (WAT)",
    "UTC-2" to "GMT+02:00",
    "UTC-3" to "GMT+03:00",
    "UTC-5:30" to "GMT+05:30",
    "UTC-8" to "GMT+08:00",
    "UTC5" to "GMT-05:00",
    "UTC8" to "GMT-08:00"
)

@Composable
fun RouterTimeSheet(
    config: RouterTimeConfig?,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSave: (RouterTimeConfig, String) -> Unit,
    onSyncPhoneTime: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var timezone by remember(config) { mutableStateOf(config?.timezone?.ifBlank { "UTC-8" } ?: "UTC-8") }
    var ntpEnabled by remember(config) { mutableStateOf(config?.ntpEnabled ?: true) }
    var s1 by remember(config) { mutableStateOf(config?.server1 ?: "") }
    var s2 by remember(config) { mutableStateOf(config?.server2 ?: "") }
    var s3 by remember(config) { mutableStateOf(config?.server3 ?: "") }
    var s4 by remember(config) { mutableStateOf(config?.server4 ?: "") }
    var manualTime by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.system_time_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (isLoading || isSaving) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_refresh_settings))
                    }
                }
            }

            if (!config?.systemTime.isNullOrBlank()) {
                Text(
                    text = "${stringResource(R.string.system_router_time)}: ${config?.systemTime}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = stringResource(R.string.system_time_timezone),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                COMMON_TIMEZONES.forEach { (wire, label) ->
                    FilterChip(
                        selected = timezone.replace(Regex("_\\d+$"), "") == wire,
                        onClick = { timezone = wire },
                        enabled = !isSaving,
                        label = { Text(label) }
                    )
                }
            }
            OutlinedTextField(
                value = timezone,
                onValueChange = { timezone = it },
                label = { Text("Timezone Wire Value (e.g. UTC-1)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )

            SettingSwitchRow(
                title = stringResource(R.string.system_time_ntp_enable),
                checked = ntpEnabled,
                enabled = !isSaving,
                onCheckedChange = { ntpEnabled = it }
            )

            if (ntpEnabled) {
                OutlinedTextField(
                    value = s1,
                    onValueChange = { s1 = it },
                    label = { Text("NTP Server 1") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = s2,
                    onValueChange = { s2 = it },
                    label = { Text("NTP Server 2") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = s3,
                    onValueChange = { s3 = it },
                    label = { Text("NTP Server 3") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = s4,
                    onValueChange = { s4 = it },
                    label = { Text("NTP Server 4") },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = manualTime,
                onValueChange = { manualTime = it },
                label = { Text("Clock (yyyy-MM-dd HH:mm:ss)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = {
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    manualTime = now
                    onSyncPhoneTime(now)
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.system_time_sync_phone))
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        onSave(
                            RouterTimeConfig(
                                systemTime = config?.systemTime.orEmpty(),
                                timezone = timezone.trim(),
                                ntpEnabled = ntpEnabled,
                                server1 = s1.trim(),
                                server2 = s2.trim(),
                                server3 = s3.trim(),
                                server4 = s4.trim()
                            ),
                            manualTime.trim()
                        )
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
fun WanLinkDetectSheet(
    config: WanLinkDetectConfig?,
    rescanAllowed: Boolean,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSave: (WanLinkDetectConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val lockedMix = config?.isMixMode == true
    var enabled by remember(config) { mutableStateOf(lockedMix || (config?.enabled ?: false)) }
    var checkMode by remember(config) { mutableStateOf(config?.checkMode ?: 0) }
    var checkTimeText by remember(config) { mutableStateOf((config?.checkTimeSec ?: 10).toString()) }
    var ip1 by remember(config) { mutableStateOf(config?.ip1 ?: "") }
    var ip2 by remember(config) { mutableStateOf(config?.ip2 ?: "") }
    var ip3 by remember(config) { mutableStateOf(config?.ip3 ?: "") }
    var action by remember(config) { mutableStateOf(config?.action ?: "0") }
    var rebootWaitText by remember(config) { mutableStateOf((config?.rebootWaitMinutes ?: 3).toString()) }
    var rescanText by remember(config) { mutableStateOf((config?.rescanNetworkSec ?: 10).toString()) }
    var v4DnsSw by remember(config) { mutableStateOf(config?.ipv4DnsSw ?: false) }
    var v4Dns1 by remember(config) { mutableStateOf(config?.ipv4Dns1 ?: "") }
    var v6DnsSw by remember(config) { mutableStateOf(config?.ipv6DnsSw ?: false) }
    var v6Dns1 by remember(config) { mutableStateOf(config?.ipv6Dns1 ?: "") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<WanLinkDetectConfig?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.system_wan_detect_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (isLoading || isSaving) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_refresh_settings))
                    }
                }
            }

            SettingSwitchRow(
                title = stringResource(R.string.system_wan_detect_enable),
                subtitle = if (lockedMix) stringResource(R.string.system_wan_detect_mix_note) else null,
                checked = if (lockedMix) true else enabled,
                enabled = !lockedMix && !isSaving,
                onCheckedChange = { enabled = it }
            )

            Text(
                text = "Probe Method",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf(0 to "Auto / ICMP", 2 to "NTP", 3 to "DNS").forEach { (idx, label) ->
                    FilterChip(
                        selected = checkMode == idx,
                        onClick = { checkMode = idx },
                        enabled = !isSaving,
                        label = { Text(label) }
                    )
                }
            }

            OutlinedTextField(
                value = checkTimeText,
                onValueChange = { checkTimeText = it; localError = null },
                label = { Text(stringResource(R.string.system_wan_detect_interval)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ip1,
                onValueChange = { ip1 = it; localError = null },
                label = { Text("Probe Host 1 (required)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ip2,
                onValueChange = { ip2 = it; localError = null },
                label = { Text("Probe Host 2 (optional)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ip3,
                onValueChange = { ip3 = it; localError = null },
                label = { Text("Probe Host 3 (optional)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.system_wan_detect_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                val actions = buildList {
                    add("0" to if (lockedMix) "Switch SIM/WAN" else "Check Only")
                    add("1" to "Reboot Router")
                    if (rescanAllowed) add("3" to "Rescan Network")
                }
                actions.forEach { (code, label) ->
                    FilterChip(
                        selected = action == code,
                        onClick = { action = code },
                        enabled = !isSaving,
                        label = { Text(label) }
                    )
                }
            }

            if (action == "1") {
                OutlinedTextField(
                    value = rebootWaitText,
                    onValueChange = { rebootWaitText = it; localError = null },
                    label = { Text(stringResource(R.string.system_wan_detect_reboot_mins)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (action == "3" && rescanAllowed) {
                OutlinedTextField(
                    value = rescanText,
                    onValueChange = { rescanText = it; localError = null },
                    label = { Text(stringResource(R.string.system_wan_detect_rescan_sec)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionCard(title = "DNS Health Overrides (optional)") {
                SettingSwitchRow(
                    title = "Custom IPv4 probe DNS",
                    checked = v4DnsSw,
                    enabled = !isSaving,
                    onCheckedChange = { v4DnsSw = it }
                )
                if (v4DnsSw) {
                    OutlinedTextField(
                        value = v4Dns1,
                        onValueChange = { v4Dns1 = it; localError = null },
                        label = { Text("IPv4 DNS 1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SettingSwitchRow(
                    title = "Custom IPv6 probe DNS",
                    checked = v6DnsSw,
                    enabled = !isSaving,
                    onCheckedChange = { v6DnsSw = it }
                )
                if (v6DnsSw) {
                    OutlinedTextField(
                        value = v6Dns1,
                        onValueChange = { v6Dns1 = it; localError = null },
                        label = { Text("IPv6 DNS 1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            val shownError = localError ?: errorMessage
            if (!shownError.isNullOrBlank()) {
                Text(
                    text = shownError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val interval = checkTimeText.trim().toIntOrNull()
                        val rebootWait = rebootWaitText.trim().toIntOrNull()
                        val rescanSec = rescanText.trim().toIntOrNull()
                        if (interval == null || rebootWait == null || rescanSec == null) {
                            localError = "Numeric timing values required"
                            return@Button
                        }
                        pendingConfirm = WanLinkDetectConfig(
                            enabled = if (lockedMix) true else enabled,
                            isMixMode = lockedMix,
                            networkMode = config?.networkMode.orEmpty(),
                            checkTimeSec = interval,
                            checkMode = checkMode,
                            ip1 = ip1.trim(),
                            ip2 = ip2.trim(),
                            ip3 = ip3.trim(),
                            action = action,
                            rebootWaitMinutes = rebootWait,
                            rescanNetworkSec = rescanSec,
                            ipv4DnsSw = v4DnsSw,
                            ipv4Dns1 = v4Dns1.trim(),
                            ipv4Dns2 = config?.ipv4Dns2.orEmpty(),
                            ipv4Dns3 = config?.ipv4Dns3.orEmpty(),
                            ipv6DnsSw = v6DnsSw,
                            ipv6Dns1 = v6Dns1.trim(),
                            ipv6Dns2 = config?.ipv6Dns2.orEmpty(),
                            ipv6Dns3 = config?.ipv6Dns3.orEmpty()
                        )
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    pendingConfirm?.let { next ->
        ConfirmSheet(
            title = stringResource(R.string.system_wan_detect_sheet_title),
            impactMessage = stringResource(R.string.system_wan_detect_subtitle),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingConfirm = null
                onSave(next)
            },
            onDismiss = { pendingConfirm = null }
        )
    }
}
