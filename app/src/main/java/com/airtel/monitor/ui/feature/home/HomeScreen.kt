package com.airtel.monitor.ui.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.ui.components.CellularCellLockCard
import com.airtel.monitor.ui.components.LockEditorSheet
import com.airtel.monitor.ui.components.MinimalWanSpeedCard
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState.routerState ?: RouterState()
    val spacing = AirtelTheme.spacing

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "wan_speed") {
            MinimalWanSpeedCard(wan = state.wan)
        }
        item(key = "cell_overview") {
            CellularCellLockCard(
                cellLock = state.cellLock,
                cellular = state.cellular,
                carriers = state.carriers,
                hardware = state.hardware,
                onEditLocks = viewModel::openLockEditor,
                networkBusy = uiState.networkActionBusy,
                onMobileData = viewModel::setMobileData,
                onFlightMode = viewModel::setFlightMode,
                onRescan = viewModel::rescanNetwork
            )
        }
    }

    if (uiState.showLockEditor) {
        LockEditorSheet(
            settings = uiState.lockSettings,
            networkType = state.cellular.networkType,
            isLoading = uiState.lockLoading,
            isSaving = uiState.lockSaving,
            message = uiState.lockMessage,
            onDismiss = viewModel::dismissLockEditor,
            onReload = viewModel::loadLocks,
            onSaveBands = viewModel::saveBandLock,
            onSaveLte = viewModel::saveLteCellLock,
            onSaveNr = viewModel::saveNrCellLock
        )
    }
}
