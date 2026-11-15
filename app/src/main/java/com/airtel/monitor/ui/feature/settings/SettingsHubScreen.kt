@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.datastore.ThemePreference
import com.airtel.monitor.core.notifications.RouterNotificationHelper
import com.airtel.monitor.core.util.NetworkFormatters
import com.airtel.monitor.data.model.SimPinState
import com.airtel.monitor.data.model.WifiBandDetail
import com.airtel.monitor.data.repository.WifiRepository.WifiBand
import com.airtel.monitor.ui.components.NetworkDiagnosisCard
import com.airtel.monitor.ui.components.SettingsDialog
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingRow
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.designsystem.SettingValueRow
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.work.RouterMonitorScheduler

private enum class PendingConnectivityConfirm {
    WIFI_24G_OFF,
    WIFI_5G_OFF,
    MOBILE_DATA_OFF,
    FLIGHT_MODE_ON,
    RESCAN_NETWORK
}

@Composable
fun SettingsHubScreen(
    viewModel: SettingsHubViewModel,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onOpenMessages: () -> Unit = {},
    onOpenDataUsage: () -> Unit = {},
    onOpenSecurity: () -> Unit = {},
    onOpenSystemTools: () -> Unit = {},
    onOpenSetupWizard: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current

    var pendingConfirm by remember { mutableStateOf<PendingConnectivityConfirm?>(null) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.emitFeedback(resources.getString(R.string.settings_notif_permission_denied))
        }
        viewModel.setBackgroundMonitoring(true)
        RouterMonitorScheduler.syncPeriodicMonitor(context, enabled = true)
    }

    val copyToClipboard: (String, String) -> Unit = { label, value ->
        if (value.isNotBlank()) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
            viewModel.emitFeedback(resources.getString(R.string.copied_to_clipboard_fmt, label))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.ensureLoadedOnOpen()
    }

    val isRefreshingAny = uiState.wifiRadioLoading || uiState.lanLoading || uiState.simLoading
    val wifi24 = uiState.wifiBandDetails.band24g
    val wifi5 = uiState.wifiBandDetails.band5g

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "section_wifi") {
            SectionCard(
                title = stringResource(R.string.settings_wifi_section_title),
                subtitle = stringResource(R.string.settings_wifi_section_subtitle),
                trailing = {
                    IconButton(
                        onClick = viewModel::loadWifiRadios,
                        enabled = !isRefreshingAny && !uiState.wifiRadioSaving,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (uiState.wifiRadioLoading) {
                            LoadingIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_band_24g),
                    subtitle = bandSummaryText(wifi24, stringResource(R.string.settings_wifi_band_off)),
                    checked = wifi24.enabled,
                    leadingIcon = Icons.Rounded.Wifi,
                    enabled = !uiState.wifiRadioLoading && !uiState.wifiRadioSaving,
                    onCheckedChange = { enable ->
                        if (enable) {
                            viewModel.setWifi24g(true)
                        } else {
                            pendingConfirm = PendingConnectivityConfirm.WIFI_24G_OFF
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_wifi_edit_24g),
                    subtitle = wifi24.ssid.ifBlank { stringResource(R.string.settings_wifi_edit_hint) },
                    leadingIcon = Icons.Rounded.Tune,
                    enabled = !uiState.wifiRadioSaving,
                    onClick = { viewModel.openSheet(ActiveSettingsSheet.EDIT_WIFI_24G) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_band_5g),
                    subtitle = bandSummaryText(wifi5, stringResource(R.string.settings_wifi_band_off)),
                    checked = wifi5.enabled,
                    leadingIcon = Icons.Rounded.Wifi,
                    enabled = !uiState.wifiRadioLoading && !uiState.wifiRadioSaving,
                    onCheckedChange = { enable ->
                        if (enable) {
                            viewModel.setWifi5g(true)
                        } else {
                            pendingConfirm = PendingConnectivityConfirm.WIFI_5G_OFF
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_wifi_edit_5g),
                    subtitle = wifi5.ssid.ifBlank { stringResource(R.string.settings_wifi_edit_hint) },
                    leadingIcon = Icons.Rounded.Tune,
                    enabled = !uiState.wifiRadioSaving,
                    onClick = { viewModel.openSheet(ActiveSettingsSheet.EDIT_WIFI_5G) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_wifi_share_qr),
                    subtitle = stringResource(R.string.settings_wifi_share_qr_subtitle),
                    leadingIcon = Icons.Rounded.QrCode2,
                    onClick = { viewModel.openSheet(ActiveSettingsSheet.WIFI_QR) }
                )

                if (!uiState.wifiRadioMessage.isNullOrBlank()) {
                    val isErr = uiState.wifiRadioMessage!!.startsWith("Error:") ||
                        uiState.wifiRadioMessage!!.startsWith("Could not")
                    Text(
                        text = uiState.wifiRadioMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErr) colors.error else colors.success,
                        modifier = Modifier.padding(top = spacing.xs)
                    )
                }

                Text(
                    text = stringResource(R.string.settings_wifi_deferred_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs)
                )
            }
        }

        item(key = "section_mobile_sim") {
            SectionCard(
                title = stringResource(R.string.settings_mobile_section_title),
                subtitle = stringResource(R.string.settings_mobile_section_subtitle),
                trailing = {
                    IconButton(
                        onClick = viewModel::loadSimStatus,
                        enabled = !uiState.simLoading && !uiState.simSaving,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (uiState.simLoading) {
                            LoadingIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.toggle_mobile_data),
                    checked = uiState.mobileDataOn ?: false,
                    leadingIcon = Icons.Rounded.CellTower,
                    enabled = uiState.mobileDataOn != null && !uiState.mobileSaving,
                    onCheckedChange = { enable ->
                        if (enable) {
                            viewModel.setMobileData(true)
                        } else {
                            pendingConfirm = PendingConnectivityConfirm.MOBILE_DATA_OFF
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingSwitchRow(
                    title = stringResource(R.string.toggle_flight_mode),
                    checked = uiState.flightModeOn ?: false,
                    leadingIcon = Icons.Rounded.NetworkCheck,
                    enabled = uiState.flightModeOn != null && !uiState.mobileSaving,
                    onCheckedChange = { enable ->
                        if (enable) {
                            pendingConfirm = PendingConnectivityConfirm.FLIGHT_MODE_ON
                        } else {
                            viewModel.setFlightMode(false)
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingValueRow(
                    label = stringResource(R.string.settings_mobile_mode_label),
                    value = buildString {
                        append(uiState.networkModeSummary)
                        append(" · ")
                        append(
                            if (uiState.roamingEnabled) {
                                stringResource(R.string.settings_mobile_roaming_on)
                            } else {
                                stringResource(R.string.settings_mobile_roaming_off)
                            }
                        )
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.signal_rescan_network),
                    leadingIcon = Icons.Rounded.CellTower,
                    enabled = !uiState.mobileSaving,
                    onClick = { pendingConfirm = PendingConnectivityConfirm.RESCAN_NETWORK }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_sim_pin_title),
                    subtitle = simSummaryText(uiState.simPinState, uiState.simLoaded),
                    leadingIcon = Icons.Rounded.Lock,
                    enabled = !uiState.simLoading,
                    onClick = { viewModel.openSheet(ActiveSettingsSheet.SIM_PIN) }
                )

                if (!uiState.mobileMessage.isNullOrBlank()) {
                    val isErr = uiState.mobileMessage!!.startsWith("Error:")
                    Text(
                        text = uiState.mobileMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErr) colors.error else colors.success,
                        modifier = Modifier.padding(top = spacing.xs)
                    )
                }

                Text(
                    text = stringResource(R.string.settings_mobile_deferred_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs)
                )
            }
        }

        item(key = "section_lan_dhcp") {
            val lan = uiState.lanConfig
            SectionCard(
                title = stringResource(R.string.settings_lan_section_title),
                subtitle = stringResource(R.string.settings_lan_section_subtitle),
                trailing = {
                    IconButton(
                        onClick = viewModel::loadDns,
                        enabled = !uiState.lanLoading && !uiState.lanSaving,
                        modifier = Modifier.size(36.dp)
                    ) {
                        if (uiState.lanLoading) {
                            LoadingIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            ) {
                SettingValueRow(
                    label = stringResource(R.string.settings_lan_gateway_label),
                    value = "${lan.lanIp} / ${lan.netMask}"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingValueRow(
                    label = stringResource(R.string.settings_lan_dhcp_label),
                    value = if (!uiState.lanLoaded) {
                        "--"
                    } else if (lan.dhcpEnabled) {
                        val lease = lan.expireTime.ifBlank { "24h" }
                        "${lan.ipBegin} – ${lan.ipEnd} · $lease"
                    } else {
                        stringResource(R.string.settings_lan_dhcp_off)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingValueRow(
                    label = stringResource(R.string.settings_lan_dns_label),
                    value = when {
                        !uiState.lanLoaded -> "--"
                        lan.primaryDns.isBlank() && lan.secondaryDns.isBlank() -> stringResource(R.string.settings_lan_dns_auto)
                        lan.secondaryDns.isBlank() -> lan.primaryDns
                        else -> "${lan.primaryDns} · ${lan.secondaryDns}"
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_lan_edit_btn),
                    leadingIcon = Icons.Rounded.Dns,
                    enabled = !uiState.lanSaving,
                    onClick = { viewModel.openSheet(ActiveSettingsSheet.LAN_DHCP) }
                )

                if (!uiState.lanMessage.isNullOrBlank()) {
                    val isErr = uiState.lanMessage!!.startsWith("Error:") || uiState.lanMessage!!.startsWith("Could not")
                    Text(
                        text = uiState.lanMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErr) colors.error else colors.success,
                        modifier = Modifier.padding(top = spacing.xs)
                    )
                }
            }
        }

        item(key = "section_messages_usage") {
            SectionCard(title = stringResource(R.string.messages_title)) {
                SettingRow(
                    title = stringResource(R.string.settings_messages_shortcut_title),
                    subtitle = stringResource(R.string.settings_messages_shortcut_subtitle),
                    leadingIcon = Icons.Rounded.MailOutline,
                    onClick = onOpenMessages
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_usage_shortcut_title),
                    subtitle = stringResource(R.string.settings_usage_shortcut_subtitle),
                    leadingIcon = Icons.Rounded.DataUsage,
                    onClick = onOpenDataUsage
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_security_shortcut_title),
                    subtitle = stringResource(R.string.settings_security_shortcut_subtitle),
                    leadingIcon = Icons.Rounded.Security,
                    onClick = onOpenSecurity
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingRow(
                    title = stringResource(R.string.settings_system_shortcut_title),
                    subtitle = stringResource(R.string.settings_system_shortcut_subtitle),
                    leadingIcon = Icons.Rounded.Tune,
                    onClick = onOpenSystemTools
                )
            }
        }

        item(key = "section_tools_diag") {
            NetworkDiagnosisCard(
                diagnosisState = uiState.diagnosisState,
                onModeChanged = viewModel::setDiagnosisMode,
                onTargetChanged = viewModel::setDiagnosisTarget,
                onPingTimesChanged = viewModel::setDiagnosisPingTimes,
                onStart = viewModel::startDiagnosis,
                onStop = viewModel::stopDiagnosis,
                onClear = viewModel::clearDiagnosisOutput
            )
        }

        item(key = "section_app_prefs") {
            SectionCard(
                title = stringResource(R.string.settings_connection_title),
                subtitle = stringResource(
                    R.string.settings_connection_subtitle,
                    uiState.routerHost,
                    uiState.username
                )
            ) {
                SettingRow(
                    title = stringResource(R.string.settings_edit_connection),
                    trailingValue = stringResource(
                        R.string.settings_poll_rate_fmt,
                        NetworkFormatters.formatPollInterval(uiState.pollIntervalSeconds)
                    ),
                    leadingIcon = Icons.Rounded.Router,
                    onClick = viewModel::openConnectionDialog
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_mask_sensitive_title),
                    subtitle = stringResource(R.string.settings_mask_sensitive_desc),
                    checked = uiState.maskSensitive,
                    onCheckedChange = viewModel::setMaskSensitive
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.padding(top = spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.settings_theme_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = ThemePreference.entries
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = uiState.themePreference == option,
                                onClick = { viewModel.setThemePreference(option) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                            ) {
                                Text(
                                    text = when (option) {
                                        ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
                                        ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
                                        ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
                                    }
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.settings_speed_unit_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val units = SpeedUnitPreference.entries
                        units.forEachIndexed { index, unit ->
                            SegmentedButton(
                                selected = uiState.speedUnitPreference == unit,
                                onClick = { viewModel.setSpeedUnitPreference(unit) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = units.size)
                            ) {
                                Text(
                                    text = when (unit) {
                                        SpeedUnitPreference.MBPS -> stringResource(R.string.settings_unit_mbps)
                                        SpeedUnitPreference.MBS -> stringResource(R.string.settings_unit_mbs)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "section_notifications") {
            SectionCard(
                title = stringResource(R.string.settings_notifications_title),
                subtitle = stringResource(R.string.settings_notifications_subtitle)
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_bg_monitor_enable),
                    subtitle = stringResource(R.string.settings_bg_monitor_desc),
                    checked = uiState.backgroundMonitorEnabled,
                    leadingIcon = Icons.Rounded.Notifications,
                    onCheckedChange = { enable ->
                        if (enable) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !RouterNotificationHelper.hasNotificationPermission(context)
                            ) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setBackgroundMonitoring(true)
                                RouterMonitorScheduler.syncPeriodicMonitor(context, enabled = true)
                            }
                        } else {
                            viewModel.setBackgroundMonitoring(false)
                            RouterMonitorScheduler.syncPeriodicMonitor(context, enabled = false)
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_notify_quota),
                    subtitle = stringResource(R.string.settings_notify_quota_desc),
                    checked = uiState.notifyQuotaWarning,
                    enabled = uiState.backgroundMonitorEnabled,
                    onCheckedChange = viewModel::setNotifyQuotaWarning
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_notify_sms),
                    subtitle = stringResource(R.string.settings_notify_sms_desc),
                    checked = uiState.notifyNewSms,
                    enabled = uiState.backgroundMonitorEnabled,
                    onCheckedChange = viewModel::setNotifyNewSms
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_notify_device),
                    subtitle = stringResource(R.string.settings_notify_device_desc),
                    checked = uiState.notifyNewDevice,
                    enabled = uiState.backgroundMonitorEnabled,
                    onCheckedChange = viewModel::setNotifyNewDevice
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingSwitchRow(
                    title = stringResource(R.string.settings_notify_unreachable),
                    subtitle = stringResource(R.string.settings_notify_unreachable_desc),
                    checked = uiState.notifyUnreachable,
                    enabled = uiState.backgroundMonitorEnabled,
                    onCheckedChange = viewModel::setNotifyUnreachable
                )
            }
        }

        item(key = "section_about_setup") {
            val appVersion = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty().ifBlank { "3.0.0-dev" }
            }
            SectionCard(title = stringResource(R.string.settings_about_app_title)) {
                SettingRow(
                    title = stringResource(R.string.settings_setup_wizard_title),
                    subtitle = stringResource(R.string.settings_setup_wizard_subtitle),
                    leadingIcon = Icons.Rounded.Router,
                    onClick = onOpenSetupWizard
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                SettingValueRow(
                    label = stringResource(R.string.settings_app_version_label),
                    value = appVersion
                )
                Text(
                    text = stringResource(R.string.settings_licenses_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs)
                )
            }
        }
    }

    if (uiState.showConnectionDialog) {
        SettingsDialog(
            currentHost = uiState.routerHost,
            currentUser = uiState.username,
            currentPassword = viewModel.storedPassword(),
            currentPollInterval = uiState.pollIntervalSeconds,
            onDismiss = viewModel::dismissConnectionDialog,
            onSave = viewModel::saveConnectionSettings
        )
    }

    when (uiState.activeSheet) {
        ActiveSettingsSheet.EDIT_WIFI_24G -> {
            WifiBandEditorSheet(
                band = WifiBand.BAND_24G,
                initialDetail = uiState.wifiBandDetails.band24g,
                hasWifiSamesSupported = uiState.wifiBandDetails.hasWifiSames,
                initialWifiSames = uiState.wifiBandDetails.wifiSames,
                isSaving = uiState.wifiRadioSaving,
                errorMessage = uiState.wifiRadioMessage?.takeIf { it.startsWith("Error:") || it.startsWith("Sent") },
                onDismiss = viewModel::dismissSheet,
                onSave = { ssid, key, auth, visible, sames, syncBoth ->
                    viewModel.saveWifiBandConfig(
                        band = WifiBand.BAND_24G,
                        ssid = ssid,
                        key = key,
                        authenticationType = auth,
                        visibleBroadcast = visible,
                        wifiSames = sames,
                        applySameToBothBands = syncBoth
                    )
                }
            )
        }
        ActiveSettingsSheet.EDIT_WIFI_5G -> {
            WifiBandEditorSheet(
                band = WifiBand.BAND_5G,
                initialDetail = uiState.wifiBandDetails.band5g,
                hasWifiSamesSupported = uiState.wifiBandDetails.hasWifiSames,
                initialWifiSames = uiState.wifiBandDetails.wifiSames,
                isSaving = uiState.wifiRadioSaving,
                errorMessage = uiState.wifiRadioMessage?.takeIf { it.startsWith("Error:") || it.startsWith("Sent") },
                onDismiss = viewModel::dismissSheet,
                onSave = { ssid, key, auth, visible, sames, syncBoth ->
                    viewModel.saveWifiBandConfig(
                        band = WifiBand.BAND_5G,
                        ssid = ssid,
                        key = key,
                        authenticationType = auth,
                        visibleBroadcast = visible,
                        wifiSames = sames,
                        applySameToBothBands = syncBoth
                    )
                }
            )
        }
        ActiveSettingsSheet.WIFI_QR -> {
            WifiQrSheet(
                bandDetails = uiState.wifiBandDetails,
                maskSensitive = uiState.maskSensitive,
                onCopyPassword = copyToClipboard,
                onDismiss = viewModel::dismissSheet
            )
        }
        ActiveSettingsSheet.SIM_PIN -> {
            SimPinSheet(
                state = uiState.simPinState,
                isLoading = uiState.simLoading,
                isSaving = uiState.simSaving,
                statusMessage = uiState.simMessage,
                onSetPinLockEnabled = viewModel::setPinLockEnabled,
                onUnlockPin = viewModel::unlockPin,
                onUnlockPuk = viewModel::unlockPuk,
                onChangePin = viewModel::changePin,
                onDismiss = viewModel::dismissSheet
            )
        }
        ActiveSettingsSheet.LAN_DHCP -> {
            LanDhcpEditorSheet(
                currentHost = uiState.routerHost,
                initialConfig = uiState.lanConfig,
                isSaving = uiState.lanSaving,
                errorMessage = uiState.lanMessage,
                onDismiss = viewModel::dismissSheet,
                onSave = viewModel::saveLanDhcpSettings
            )
        }
        null -> Unit
    }

    pendingConfirm?.let { action ->
        val (title, impact, confirmLabel) = when (action) {
            PendingConnectivityConfirm.WIFI_24G_OFF -> Triple(
                stringResource(R.string.confirm_wifi_off_title, "2.4 GHz"),
                stringResource(R.string.confirm_wifi_off_impact, "2.4 GHz"),
                stringResource(R.string.confirm_turn_off)
            )
            PendingConnectivityConfirm.WIFI_5G_OFF -> Triple(
                stringResource(R.string.confirm_wifi_off_title, "5 GHz"),
                stringResource(R.string.confirm_wifi_off_impact, "5 GHz"),
                stringResource(R.string.confirm_turn_off)
            )
            PendingConnectivityConfirm.MOBILE_DATA_OFF -> Triple(
                stringResource(R.string.confirm_mobile_data_off_title),
                stringResource(R.string.confirm_mobile_data_off_impact),
                stringResource(R.string.confirm_turn_off)
            )
            PendingConnectivityConfirm.FLIGHT_MODE_ON -> Triple(
                stringResource(R.string.confirm_flight_mode_on_title),
                stringResource(R.string.confirm_flight_mode_on_impact),
                stringResource(R.string.confirm_enable)
            )
            PendingConnectivityConfirm.RESCAN_NETWORK -> Triple(
                stringResource(R.string.confirm_rescan_title),
                stringResource(R.string.confirm_rescan_impact),
                stringResource(R.string.confirm_rescan_button)
            )
        }
        ConfirmSheet(
            title = title,
            impactMessage = impact,
            confirmLabel = confirmLabel,
            tier = ConfirmTier.CONNECTIVITY,
            onDismiss = { pendingConfirm = null },
            onConfirm = {
                val target = action
                pendingConfirm = null
                when (target) {
                    PendingConnectivityConfirm.WIFI_24G_OFF -> viewModel.setWifi24g(false)
                    PendingConnectivityConfirm.WIFI_5G_OFF -> viewModel.setWifi5g(false)
                    PendingConnectivityConfirm.MOBILE_DATA_OFF -> viewModel.setMobileData(false)
                    PendingConnectivityConfirm.FLIGHT_MODE_ON -> viewModel.setFlightMode(true)
                    PendingConnectivityConfirm.RESCAN_NETWORK -> viewModel.rescanNetwork()
                }
            }
        )
    }
}

@Composable
private fun bandSummaryText(detail: WifiBandDetail, offLabel: String): String {
    if (!detail.enabled) return offLabel
    val ssid = detail.ssid.ifBlank { "--" }
    return stringResource(
        R.string.settings_wifi_band_summary_fmt,
        ssid,
        detail.channel.ifBlank { "Auto" },
        detail.securityLabel
    )
}

@Composable
private fun simSummaryText(state: SimPinState, loaded: Boolean): String {
    if (!loaded) return "--"
    return when {
        !state.simInserted -> stringResource(R.string.settings_sim_not_inserted)
        state.pukRequired -> stringResource(R.string.settings_sim_puk_locked_fmt, state.pukAttemptsLeft)
        state.pinRequired -> stringResource(R.string.settings_sim_pin_locked_fmt, state.pinAttemptsLeft)
        state.pinLockEnabled -> stringResource(R.string.settings_sim_pin_enabled_fmt, state.pinAttemptsLeft)
        else -> stringResource(R.string.settings_sim_pin_disabled)
    }
}
