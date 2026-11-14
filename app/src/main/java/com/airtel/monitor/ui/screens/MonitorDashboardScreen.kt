package com.airtel.monitor.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // One scroll position per tab so switching tabs doesn't lose your place.
    val scrollStates = Array(DashTab.entries.size) { rememberScrollState() }
    val scope = rememberCoroutineScope()
    var dnsCardY by remember { mutableIntStateOf(0) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barClearance = GlassNavBarHeight + navBottom + 12.dp

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                modifier = Modifier.padding(bottom = barClearance + 8.dp),
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

        val rState = uiState.routerState

        // Header + offline banner, repeated at the top of every tab.
        val topSection: @Composable () -> Unit = {
            HeaderBar(
                connectionStatus = uiState.connectionStatus,
                isPaused = uiState.isPaused,
                pollInterval = uiState.pollIntervalSeconds,
                routerHost = uiState.routerHost,
                onTogglePause = { viewModel.togglePause() },
                onForceRefresh = { viewModel.forceRefresh() },
                onSelectPollInterval = { viewModel.setPollInterval(it) },
                onOpenDns = {
                    viewModel.loadDns()
                    tabIndex = DashTab.Network.ordinal
                    scope.launch {
                        delay(350) // let the Network tab compose and measure the DNS card
                        scrollStates[DashTab.Network.ordinal].animateScrollTo(dnsCardY)
                    }
                },
                onOpenReboot = { viewModel.openRebootDialog() },
                onOpenSettings = { viewModel.openSettingsDialog() }
            )

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
        }

        Box(modifier = modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 6 * dir } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 6 * dir } + fadeOut())
                },
                label = "dashTab"
            ) { index ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                        .verticalScroll(scrollStates[index])
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = barClearance + 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    topSection()
                    when (DashTab.entries[index]) {
                        DashTab.Home -> {
                            MinimalWanSpeedCard(wan = rState?.wan ?: WanMetrics())

                            CellularCellLockCard(
                                cellLock = rState?.cellLock ?: CellLockInfo(),
                                cellular = rState?.cellular ?: CellularMetrics(),
                                hardware = rState?.hardware,
                                onEditLocks = { viewModel.openLockEditor() },
                                networkBusy = uiState.networkActionBusy,
                                onMobileData = { viewModel.setMobileData(it) },
                                onFlightMode = { viewModel.setFlightMode(it) },
                                onRescan = { viewModel.rescanNetwork() }
                            )
                        }

                        DashTab.Network -> {
                            WifiRadiosCard(
                                wifiConfig = uiState.wifiRadioConfig,
                                isLoading = uiState.wifiRadioLoading,
                                isSaving = uiState.wifiRadioSaving,
                                message = uiState.wifiRadioMessage,
                                onReload = { viewModel.loadWifiRadios() },
                                onToggle24g = { viewModel.setWifi24g(it) },
                                onToggle5g = { viewModel.setWifi5g(it) }
                            )

                            LanDnsCard(
                                modifier = Modifier.onGloballyPositioned { dnsCardY = it.positionInParent().y.toInt() },
                                dnsConfig = uiState.dnsConfig,
                                isLoaded = uiState.dnsLoaded,
                                isLoading = uiState.dnsLoading,
                                isSaving = uiState.dnsSaving,
                                message = uiState.dnsMessage,
                                onReload = { viewModel.loadDns() },
                                onSaveDns = { p, s -> viewModel.saveDns(p, s) }
                            )
                        }

                        DashTab.Tools -> {
                            NetworkDiagnosisCard(
                                diagnosisState = uiState.diagnosisState,
                                onModeChanged = { viewModel.setDiagnosisMode(it) },
                                onTargetChanged = { viewModel.setDiagnosisTarget(it) },
                                onPingTimesChanged = { viewModel.setDiagnosisPingTimes(it) },
                                onStart = { viewModel.startDiagnosis() },
                                onStop = { viewModel.stopDiagnosis() },
                                onClear = { viewModel.clearDiagnosisOutput() }
                            )
                        }

                        DashTab.Devices -> {
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
                        }
                    }
                }
            }

            GlassNavBar(
                selected = tabIndex,
                onSelect = { tabIndex = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBottom + 12.dp)
            )
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
    if (uiState.showLockEditor) {
        LockEditorSheet(
            settings = uiState.lockSettings,
            networkType = uiState.routerState?.cellular?.networkType.orEmpty(),
            isLoading = uiState.lockLoading,
            isSaving = uiState.lockSaving,
            message = uiState.lockMessage,
            onDismiss = { viewModel.dismissLockEditor() },
            onReload = { viewModel.loadLocks() },
            onSaveBands = { l4, b4, l5, b5 -> viewModel.saveBandLock(l4, b4, l5, b5) },
            onSaveLte = { on, cells -> viewModel.saveLteCellLock(on, cells) },
            onSaveNr = { on, cells -> viewModel.saveNrCellLock(on, cells) }
        )
    }

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
            currentPassword = viewModel.storedPassword(),
            currentUser = uiState.username,
            currentPollInterval = uiState.pollIntervalSeconds,
            onDismiss = { viewModel.dismissSettingsDialog() },
            onSave = { host, user, pass, interval ->
                viewModel.saveSettings(host, user, pass, interval)
            }
        )
    }
}
