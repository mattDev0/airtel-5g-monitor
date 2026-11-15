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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.airtel.monitor.data.model.ScheduledRebootConfig
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun ScheduledRebootSheet(
    current: ScheduledRebootConfig?,
    saving: Boolean,
    onSave: (ScheduledRebootConfig, onError: (String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
