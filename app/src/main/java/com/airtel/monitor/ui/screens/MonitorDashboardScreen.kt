package com.airtel.monitor.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airtel.monitor.data.model.ConnectionStatus
import com.airtel.monitor.data.model.WanMetrics
import com.airtel.monitor.data.model.CellularMetrics
import com.airtel.monitor.data.model.CellLockInfo
import com.airtel.monitor.ui.components.*
import com.airtel.monitor.ui.viewmodel.MonitorViewModel

@Composable
fun MonitorDashboardScreen(
    viewModel: MonitorViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                snackbar = { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        val scrollState = rememberScrollState()

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Header Bar
            HeaderBar(
                connectionStatus = uiState.connectionStatus,
                isPaused = uiState.isPaused,
                pollInterval = uiState.pollIntervalSeconds,
                routerHost = uiState.routerHost,
                onTogglePause = { viewModel.togglePause() },
                onForceRefresh = { viewModel.forceRefresh() },
                onSelectPollInterval = { viewModel.setPollInterval(it) },
                onOpenDns = { viewModel.openDnsDialog() },
                onOpenReboot = { viewModel.openRebootDialog() },
                onOpenSettings = { viewModel.openSettingsDialog() }
            )

            // Offline / Reconnecting Warning Banner
            if (uiState.connectionStatus == ConnectionStatus.RECONNECTING ||
                uiState.connectionStatus == ConnectionStatus.DISCONNECTED
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Cannot reach router at ${uiState.routerHost}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Ensure your phone is connected to the router's Wi-Fi network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            val rState = uiState.routerState

            // Minimal WAN Speed Card (Download & Upload)
            MinimalWanSpeedCard(
                wan = rState?.wan ?: WanMetrics()
            )

            // Minimal Cellular & Cell Lock Telemetry Card
            CellularCellLockCard(
                cellLock = rState?.cellLock ?: CellLockInfo(),
                cellular = rState?.cellular ?: CellularMetrics(),
                hardware = rState?.hardware
            )

            // Minimal Wi-Fi Radios Card (2.4 GHz & 5 GHz)
            WifiRadiosCard(
                wifiConfig = uiState.wifiRadioConfig,
                isLoading = uiState.wifiRadioLoading,
                isSaving = uiState.wifiRadioSaving,
                message = uiState.wifiRadioMessage,
                onReload = { viewModel.loadWifiRadios() },
                onToggle24g = { viewModel.setWifi24g(it) },
                onToggle5g = { viewModel.setWifi5g(it) }
            )

            // Minimal LAN DNS Card
            LanDnsCard(
                dnsConfig = uiState.dnsConfig,
                isLoaded = uiState.dnsLoaded,
                isLoading = uiState.dnsLoading,
                isSaving = uiState.dnsSaving,
                message = uiState.dnsMessage,
                onReload = { viewModel.loadDns() },
                onSaveDns = { p, s -> viewModel.saveDns(p, s) }
            )

            // Minimal Network Diagnosis Card (Ping & Traceroute)
            NetworkDiagnosisCard(
                diagnosisState = uiState.diagnosisState,
                onModeChanged = { viewModel.setDiagnosisMode(it) },
                onTargetChanged = { viewModel.setDiagnosisTarget(it) },
                onPingTimesChanged = { viewModel.setDiagnosisPingTimes(it) },
                onStart = { viewModel.startDiagnosis() },
                onStop = { viewModel.stopDiagnosis() },
                onClear = { viewModel.clearDiagnosisOutput() }
            )

            // Minimal Connected Devices Fleet Section
            DeviceFleetSection(
                devices = uiState.filteredDevices,
                totalCount = rState?.devices?.size ?: 0,
                filterMode = uiState.filterMode,
                sortMode = uiState.sortMode,
                searchQuery = uiState.searchQuery,
                onFilterChanged = { viewModel.setFilter(it) },
                onSortChanged = { viewModel.setSort(it) },
                onSearchChanged = { viewModel.setSearchQuery(it) },
                onOpenRename = { viewModel.openRenameDialog(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Rename Dialog
    uiState.renameTarget?.let { device ->
        RenameDeviceDialog(
            device = device,
            onDismiss = { viewModel.dismissRenameDialog() },
            onSave = { alias -> viewModel.saveAlias(device, alias) }
        )
    }

    // Reboot Dialog
    if (uiState.showRebootDialog) {
        RebootConfirmDialog(
            onDismiss = { viewModel.dismissRebootDialog() },
            onConfirm = { viewModel.confirmReboot() }
        )
    }

    // Settings Dialog
    if (uiState.showSettingsDialog) {
        SettingsDialog(
            currentHost = uiState.routerHost,
            currentUser = uiState.username,
            currentPollInterval = uiState.pollIntervalSeconds,
            onDismiss = { viewModel.dismissSettingsDialog() },
            onSave = { host, user, pass, interval ->
                viewModel.saveSettings(host, user, pass, interval)
            }
        )
    }
}

