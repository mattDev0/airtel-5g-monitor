package com.airtel.monitor.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.core.datastore.ThemePreference
import com.airtel.monitor.core.util.NetworkFormatters
import com.airtel.monitor.ui.components.LanDnsCard
import com.airtel.monitor.ui.components.NetworkDiagnosisCard
import com.airtel.monitor.ui.components.SettingsDialog
import com.airtel.monitor.ui.components.WifiRadiosCard
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingRow
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun SettingsHubScreen(
    viewModel: SettingsHubViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = AirtelTheme.spacing

    LaunchedEffect(Unit) {
        viewModel.ensureLoadedOnOpen()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "connection_app") {
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
                    leadingIcon = Icons.Rounded.Router,
                    trailingValue = NetworkFormatters.formatPollInterval(uiState.pollIntervalSeconds),
                    onClick = viewModel::openConnectionDialog
                )
                Text(text = stringResource(R.string.settings_theme_label))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = ThemePreference.entries
                    options.forEachIndexed { index, option ->
                        val label = when (option) {
                            ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
                            ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
                            ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
                        }
                        SegmentedButton(
                            selected = uiState.themePreference == option,
                            onClick = { viewModel.setThemePreference(option) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        item(key = "wifi_radios") {
            WifiRadiosCard(
                wifiConfig = uiState.wifiRadioConfig,
                isLoading = uiState.wifiRadioLoading,
                isSaving = uiState.wifiRadioSaving,
                message = uiState.wifiRadioMessage,
                onReload = viewModel::loadWifiRadios,
                onToggle24g = viewModel::setWifi24g,
                onToggle5g = viewModel::setWifi5g
            )
        }

        item(key = "lan_dns") {
            LanDnsCard(
                dnsConfig = uiState.dnsConfig,
                isLoaded = uiState.dnsLoaded,
                isLoading = uiState.dnsLoading,
                isSaving = uiState.dnsSaving,
                message = uiState.dnsMessage,
                onReload = viewModel::loadDns,
                onSaveDns = viewModel::saveDns
            )
        }

        item(key = "diagnosis") {
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
}
