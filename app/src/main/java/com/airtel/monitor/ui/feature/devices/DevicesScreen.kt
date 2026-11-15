package com.airtel.monitor.ui.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.ui.components.DeviceFleetSection
import com.airtel.monitor.ui.components.RenameDeviceDialog
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = AirtelTheme.spacing

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "device_fleet") {
            DeviceFleetSection(
                devices = uiState.filteredDevices,
                totalCount = uiState.totalCount,
                filterMode = uiState.filterMode,
                sortMode = uiState.sortMode,
                searchQuery = uiState.searchQuery,
                onFilterChanged = viewModel::setFilter,
                onSortChanged = viewModel::setSort,
                onSearchChanged = viewModel::setSearchQuery,
                onOpenRename = viewModel::openRenameDialog
            )
        }
    }

    val renameTarget = uiState.renameTarget
    if (renameTarget != null) {
        RenameDeviceDialog(
            device = renameTarget,
            onDismiss = viewModel::dismissRenameDialog,
            onSave = { newAlias: String -> viewModel.saveAlias(renameTarget, newAlias) }
        )
    }
}
