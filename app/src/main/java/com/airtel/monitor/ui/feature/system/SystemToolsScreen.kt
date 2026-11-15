@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.system

import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Schedule
import com.airtel.monitor.ui.designsystem.SettingRow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.util.SensitiveFormatters
import com.airtel.monitor.data.model.ScheduledRebootConfig
import com.airtel.monitor.data.model.SpeedTestPhase
import com.airtel.monitor.data.model.SpeedTestProgress
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.ErrorState
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingRow
import com.airtel.monitor.ui.designsystem.SettingValueRow
import com.airtel.monitor.ui.designsystem.SkeletonBox
import com.airtel.monitor.ui.designsystem.StatTile
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace
import java.util.Locale

private enum class PendingSystemConfirm {
    CLEAR_LOGS,
    REBOOT_DESTRUCTIVE
}

@Composable
fun SystemToolsScreen(
    viewModel: SystemToolsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    var pendingConfirm by remember { mutableStateOf<PendingSystemConfirm?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = stringResource(R.string.system_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = viewModel::refreshAll,
                enabled = !uiState.isLoading,
                modifier = Modifier.size(48.dp)
            ) {
                if (uiState.isLoading) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_settings)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = spacing.lg,
                end = spacing.lg,
                top = spacing.sm,
                bottom = spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            if (uiState.errorMessage != null && uiState.aboutInfo == null) {
                item(key = "sys_error") {
                    ErrorState(
                        title = stringResource(R.string.system_title),
                        message = uiState.errorMessage!!,
                        onRetry = viewModel::refreshAll
                    )
                }
            } else if (uiState.isLoading && uiState.aboutInfo == null) {
                item(key = "sys_skel_1") {
                    SkeletonBox(modifier = Modifier.fillMaxWidth().size(width = 300.dp, height = 180.dp))
                }
                item(key = "sys_skel_2") {
                    SkeletonBox(modifier = Modifier.fillMaxWidth().size(width = 300.dp, height = 160.dp))
                }
            } else {
                uiState.aboutInfo?.let { about ->
                    item(key = "sys_about") {
                        SectionCard(
                            title = stringResource(R.string.system_about_title),
                            subtitle = stringResource(R.string.system_about_subtitle)
                        ) {
                            val generalRows = buildList {
                                add(R.string.system_odu_model to about.oduModel)
                                if (about.iduModel.isNotBlank()) add(R.string.system_idu_model to about.iduModel)
                                if (about.hardwareVersion.isNotBlank()) add(R.string.system_hw_version to about.hardwareVersion)
                                if (about.firmwareVersion.isNotBlank()) add(R.string.system_fw_version to about.firmwareVersion)
                                if (about.buildDate.isNotBlank()) add(R.string.system_build_date to about.buildDate)
                                if (uiState.routerTime.isNotBlank()) add(R.string.system_router_time to uiState.routerTime)
                                add(R.string.system_uptime to about.uptimeText)
                                if (about.cpuUsage.isNotBlank()) add(R.string.system_cpu to about.cpuUsage)
                                if (about.temperature.isNotBlank()) add(R.string.system_temp to about.temperature)
                                if (about.memoryText.isNotBlank()) add(R.string.system_memory to about.memoryText)
                                if (about.linkRates.isNotEmpty()) {
                                    add(R.string.system_link_rates to about.linkRates.joinToString(" · "))
                                }
                            }
                            generalRows.forEachIndexed { idx, (labelRes, value) ->
                                SettingValueRow(
                                    label = stringResource(labelRes),
                                    value = value
                                )
                                if (idx < generalRows.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                            }

                            val sensitiveRows = buildList {
                                if (about.imei.isNotBlank()) add(Triple("imei", R.string.system_imei, about.imei))
                                if (about.imsi.isNotBlank()) add(Triple("imsi", R.string.system_imsi, about.imsi))
                                if (about.iccid.isNotBlank()) add(Triple("iccid", R.string.system_iccid, about.iccid))
                                if (about.serialNumber.isNotBlank()) add(Triple("serial", R.string.system_serial, about.serialNumber))
                                if (about.msisdn.isNotBlank()) add(Triple("msisdn", R.string.system_msisdn, about.msisdn))
                                if (about.eid.isNotBlank()) add(Triple("eid", R.string.system_eid, about.eid))
                                if (about.iduMac.isNotBlank()) add(Triple("iduMac", R.string.system_idu_mac, about.iduMac))
                                if (about.lanMac.isNotBlank()) add(Triple("lanMac", R.string.system_lan_mac, about.lanMac))
                            }
                            if (sensitiveRows.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                sensitiveRows.forEachIndexed { idx, (key, labelRes, rawValue) ->
                                    val revealed = !uiState.maskSensitive || key in uiState.revealedFields
                                    val displayed = if (revealed) rawValue else SensitiveFormatters.maskIdentifier(rawValue)
                                    SensitiveIdentifierRow(
                                        label = stringResource(labelRes),
                                        value = displayed,
                                        revealed = revealed,
                                        canToggle = uiState.maskSensitive,
                                        onToggle = { viewModel.toggleReveal(key) }
                                    )
                                    if (idx < sensitiveRows.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "sys_speedtest") {
                    SpeedTestCard(
                        progress = uiState.speedTest,
                        speedUnit = uiState.speedUnit,
                        onStart = viewModel::startSpeedTest,
                        onCancel = viewModel::cancelSpeedTest
                    )
                }

                item(key = "sys_selfcheck") {
                    SectionCard(
                        title = stringResource(R.string.system_self_check_title),
                        subtitle = stringResource(R.string.system_self_check_subtitle),
                        trailing = {
                            FilledTonalButton(
                                onClick = viewModel::runSelfCheck,
                                enabled = !uiState.selfCheckLoading
                            ) {
                                if (uiState.selfCheckLoading) {
                                    LoadingIndicator(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(spacing.xs))
                                    Text(stringResource(R.string.system_self_check_running))
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.FactCheck,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(spacing.xs))
                                    Text(stringResource(R.string.system_self_check_btn))
                                }
                            }
                        }
                    ) {
                        if (!uiState.selfCheckError.isNullOrBlank()) {
                            Text(
                                text = uiState.selfCheckError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error
                            )
                        }
                        uiState.selfCheck?.let { check ->
                            val checkRows = listOf(
                                R.string.system_self_check_sim to check.simStatus.ifBlank { "--" },
                                R.string.system_self_check_antenna to check.antennaStatus.ifBlank { "--" },
                                R.string.system_self_check_network to "${check.networkType.ifBlank { "--" }} · Level ${check.signalLevel.ifBlank { "--" }}",
                                R.string.system_self_check_wan to check.wanIpv4.ifBlank { "--" },
                                R.string.system_self_check_wifi to "2.4G: ${if (check.wifi2gEnabled) "On" else "Off"} · 5G: ${if (check.wifi5gEnabled) "On" else "Off"}",
                                R.string.system_self_check_health to "${check.flashLevel.ifBlank { "-" }} / ${check.memoryLevel.ifBlank { "-" }} / ${check.temperatureLevel.ifBlank { "-" }}"
                            )
                            checkRows.forEachIndexed { idx, (labelRes, value) ->
                                SettingValueRow(
                                    label = stringResource(labelRes),
                                    value = value
                                )
                                if (idx < checkRows.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }

                item(key = "sys_maintenance") {
                    SectionCard(title = stringResource(R.string.system_maintenance_title)) {
                        SettingRow(
                            title = stringResource(R.string.system_scheduled_reboot_title),
                            subtitle = formatScheduleSummary(
                                uiState.scheduledReboot,
                                stringResource(R.string.system_scheduled_reboot_disabled)
                            ),
                            leadingIcon = Icons.Rounded.Schedule,
                            onClick = { viewModel.openSheet(ActiveSystemSheet.SCHEDULED_REBOOT) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SettingRow(
                            title = stringResource(R.string.system_logs_title),
                            subtitle = stringResource(R.string.system_logs_subtitle),
                            leadingIcon = Icons.Rounded.Description,
                            onClick = { viewModel.openSheet(ActiveSystemSheet.LOGS) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SettingRow(
                            title = stringResource(R.string.system_change_password_title),
                            subtitle = stringResource(R.string.system_change_password_subtitle),
                            leadingIcon = Icons.Rounded.LockReset,
                            onClick = { viewModel.openSheet(ActiveSystemSheet.CHANGE_PASSWORD) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SettingRow(
                            title = stringResource(R.string.system_reboot_now_title),
                            subtitle = stringResource(R.string.system_reboot_now_subtitle),
                            leadingIcon = Icons.Rounded.PowerSettingsNew,
                            onClick = { pendingConfirm = PendingSystemConfirm.REBOOT_DESTRUCTIVE }
                        )
                    }
                }

                item(key = "sys_time_ntp") {
                    SectionCard(
                        title = stringResource(R.string.system_time_title),
                        subtitle = stringResource(R.string.system_time_subtitle)
                    ) {
                        SettingRow(
                            title = stringResource(R.string.system_time_sheet_title),
                            subtitle = uiState.routerTime.ifBlank { "--" },
                            leadingIcon = Icons.Rounded.Schedule,
                            onClick = { viewModel.openSheet(ActiveSystemSheet.ROUTER_TIME) }
                        )
                    }
                }

                item(key = "sys_wan_detect") {
                    SectionCard(
                        title = stringResource(R.string.system_wan_detect_title),
                        subtitle = stringResource(R.string.system_wan_detect_subtitle)
                    ) {
                        SettingRow(
                            title = stringResource(R.string.system_wan_detect_sheet_title),
                            subtitle = uiState.wanDetectConfig?.let { cfg ->
                                if (cfg.enabled) "Enabled · ${cfg.checkTimeSec}s interval" else "Disabled"
                            } ?: "Configure auto-recovery probes",
                            leadingIcon = Icons.Rounded.NetworkCheck,
                            onClick = { viewModel.openSheet(ActiveSystemSheet.WAN_LINK_DETECT) }
                        )
                    }
                }

                item(key = "sys_factory_reset") {
                    var confirmFactoryReset by remember { mutableStateOf(false) }
                    SectionCard(
                        title = stringResource(R.string.system_factory_reset_title),
                        subtitle = stringResource(R.string.system_factory_reset_subtitle)
                    ) {
                        OutlinedButton(
                            onClick = { confirmFactoryReset = true },
                            enabled = !uiState.sheetBusy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(spacing.xs))
                            Text(stringResource(R.string.system_factory_reset_btn))
                        }
                    }
                    if (confirmFactoryReset) {
                        ConfirmSheet(
                            title = stringResource(R.string.system_factory_reset_confirm_title),
                            impactMessage = stringResource(R.string.system_factory_reset_confirm_impact),
                            confirmLabel = stringResource(R.string.system_factory_reset_btn),
                            tier = ConfirmTier.DESTRUCTIVE,
                            requiredConfirmationText = stringResource(R.string.system_factory_reset_token),
                            onConfirm = {
                                confirmFactoryReset = false
                                viewModel.factoryReset()
                            },
                            onDismiss = { confirmFactoryReset = false }
                        )
                    }
                }
            }
        }
    }

    when (uiState.activeSheet) {
        ActiveSystemSheet.SCHEDULED_REBOOT -> ScheduledRebootSheet(
            current = uiState.scheduledReboot,
            saving = uiState.rebootScheduleSaving,
            onSave = viewModel::saveScheduledReboot,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSystemSheet.LOGS -> SystemLogsSheet(
            logSettings = uiState.logSettings,
            logLines = uiState.sysLogLines,
            loading = uiState.logsLoading,
            saving = uiState.logsSaving,
            errorMessage = uiState.logsError,
            onRefresh = viewModel::loadSysLog,
            onSaveSettings = viewModel::saveLogSettings,
            onRequestClearLogs = { pendingConfirm = PendingSystemConfirm.CLEAR_LOGS },
            onDismiss = viewModel::dismissSheet
        )
        ActiveSystemSheet.CHANGE_PASSWORD -> ChangePasswordSheet(
            saving = uiState.passwordSaving,
            errorMessage = uiState.passwordError,
            onSubmit = viewModel::changeAdminPassword,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSystemSheet.ROUTER_TIME -> RouterTimeSheet(
            config = uiState.timeConfig,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = viewModel::loadRouterTimeConfig,
            onSave = viewModel::saveRouterTimeConfig,
            onSyncPhoneTime = viewModel::syncClientTime,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSystemSheet.WAN_LINK_DETECT -> WanLinkDetectSheet(
            config = uiState.wanDetectConfig,
            rescanAllowed = uiState.featureFlags.wanDetectRescanAvailable,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = viewModel::loadWanLinkDetect,
            onSave = viewModel::saveWanLinkDetect,
            onDismiss = viewModel::dismissSheet
        )
        null -> Unit
    }

    when (pendingConfirm) {
        PendingSystemConfirm.CLEAR_LOGS -> ConfirmSheet(
            title = stringResource(R.string.system_logs_clear_confirm_title),
            impactMessage = stringResource(R.string.system_logs_clear_confirm_impact),
            confirmLabel = stringResource(R.string.system_logs_clear_btn),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingConfirm = null
                viewModel.clearSysLog()
            },
            onDismiss = { pendingConfirm = null }
        )
        PendingSystemConfirm.REBOOT_DESTRUCTIVE -> ConfirmSheet(
            title = stringResource(R.string.reboot_confirm_title),
            impactMessage = stringResource(R.string.reboot_confirm_impact),
            confirmLabel = stringResource(R.string.reboot_confirm_button),
            tier = ConfirmTier.DESTRUCTIVE,
            requiredConfirmationText = stringResource(R.string.reboot_confirm_token),
            onConfirm = {
                pendingConfirm = null
                viewModel.rebootRouter()
            },
            onDismiss = { pendingConfirm = null }
        )
        null -> Unit
    }
}

@Composable
private fun SensitiveIdentifierRow(
    label: String,
    value: String,
    revealed: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = AirtelTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (canToggle) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.merge(TabularMonospace),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (canToggle) {
            IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(R.string.cd_toggle_reveal),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SpeedTestCard(
    progress: SpeedTestProgress,
    speedUnit: SpeedUnitPreference,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val running = progress.phase in setOf(
        SpeedTestPhase.PING,
        SpeedTestPhase.DOWNLOAD,
        SpeedTestPhase.UPLOAD
    )

    fun formatRate(mbps: Double?): String {
        if (mbps == null) return "--"
        return when (speedUnit) {
            SpeedUnitPreference.MBPS -> String.format(Locale.US, "%.1f Mbps", mbps)
            SpeedUnitPreference.MBS -> String.format(Locale.US, "%.2f MB/s", mbps / 8.0)
        }
    }

    SectionCard(
        title = stringResource(R.string.system_speedtest_title),
        subtitle = progress.statusLabel.ifBlank { stringResource(R.string.system_speedtest_subtitle) },
        trailing = {
            if (running) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error)
                ) {
                    Text(stringResource(R.string.system_speedtest_cancel))
                }
            } else {
                Button(onClick = onStart) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text(stringResource(R.string.system_speedtest_start))
                }
            }
        }
    ) {
        if (running) {
            LinearProgressIndicator(
                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            StatTile(
                label = stringResource(R.string.system_speedtest_ping),
                value = progress.pingMs?.let { String.format(Locale.US, "%.0f ms", it) } ?: "--",
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.system_speedtest_jitter),
                value = progress.jitterMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            StatTile(
                label = stringResource(R.string.system_speedtest_download),
                value = formatRate(progress.downloadMbps),
                valueColor = colors.success,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = stringResource(R.string.system_speedtest_upload),
                value = formatRate(progress.uploadMbps),
                valueColor = colors.warning,
                modifier = Modifier.weight(1f)
            )
        }
        if (!progress.errorMessage.isNullOrBlank()) {
            Text(
                text = progress.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        }
        Text(
            text = stringResource(R.string.system_speedtest_privacy_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatScheduleSummary(
    config: ScheduledRebootConfig?,
    disabledLabel: String
): String {
    if (config == null || !config.enabled) return disabledLabel
    val dayNames = mapOf(
        1 to "Mon",
        2 to "Tue",
        3 to "Wed",
        4 to "Thu",
        5 to "Fri",
        6 to "Sat",
        0 to "Sun"
    )
    val ordered = listOf(1, 2, 3, 4, 5, 6, 0).filter { it in config.weekDays }
        .mapNotNull { dayNames[it] }
        .joinToString(", ")
    val timeText = String.format(Locale.US, "%02d:%02d", config.hour, config.minute)
    return if (ordered.isEmpty()) timeText else "$ordered · $timeText"
}
