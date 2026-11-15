@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.devices

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.model.DeviceFilter
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DevicePresenceFilter
import com.airtel.monitor.data.model.DeviceSort
import com.airtel.monitor.ui.components.RenameDeviceDialog
import com.airtel.monitor.ui.designsystem.EmptyState
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SignalBars
import com.airtel.monitor.ui.designsystem.StatTile
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    contentPadding: PaddingValues = PaddingValues(16.dp)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = AirtelTheme.spacing
    val context = LocalContext.current
    val resources = LocalResources.current

    val copyToClipboard: (String, String) -> Unit = { label, value ->
        if (value.isNotBlank()) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
            viewModel.emitFeedback(resources.getString(R.string.copied_to_clipboard_fmt, label))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "fleet_summary") {
            DeviceSummaryTiles(
                uiState = uiState,
                onSelectPresence = viewModel::setPresenceFilter,
                onSelectBand = viewModel::setFilter
            )
        }

        item(key = "fleet_controls") {
            DeviceFilterAndSortCard(
                uiState = uiState,
                onSearchChange = viewModel::setSearchQuery,
                onSelectPresence = viewModel::setPresenceFilter,
                onSelectBand = viewModel::setFilter,
                onSelectSort = viewModel::setSort
            )
        }

        if (uiState.filteredDevices.isEmpty()) {
            item(key = "empty_devices") {
                val hasActiveFilters = uiState.searchQuery.isNotBlank() ||
                    uiState.filterMode != DeviceFilter.ALL ||
                    uiState.presenceFilter != DevicePresenceFilter.ALL
                SectionCard {
                    EmptyState(
                        title = stringResource(R.string.devices_empty_title),
                        message = if (hasActiveFilters) {
                            stringResource(R.string.devices_empty_filtered_message)
                        } else {
                            stringResource(R.string.devices_empty_none_message)
                        },
                        actionLabel = if (hasActiveFilters) {
                            stringResource(R.string.devices_clear_filters)
                        } else {
                            null
                        },
                        onAction = if (hasActiveFilters) viewModel::clearFilters else null
                    )
                }
            }
        } else {
            items(
                items = uiState.filteredDevices,
                key = { "${it.mac}_${it.ip}" }
            ) { device ->
                DeviceListRow(
                    device = device,
                    onClick = { viewModel.selectDevice(device) },
                    onRenameClick = { viewModel.openRenameDialog(device) }
                )
            }
        }
    }

    uiState.selectedDevice?.let { selected ->
        DeviceDetailSheet(
            device = selected,
            onDismiss = viewModel::dismissDetailSheet,
            onCopy = copyToClipboard,
            onRename = { viewModel.openRenameDialog(selected) },
            onResetAlias = { viewModel.clearAlias(selected) }
        )
    }

    uiState.renameTarget?.let { target ->
        RenameDeviceDialog(
            device = target,
            onDismiss = viewModel::dismissRenameDialog,
            onSave = { alias -> viewModel.saveAlias(target, alias) }
        )
    }
}

@Composable
private fun DeviceSummaryTiles(
    uiState: DevicesUiState,
    onSelectPresence: (DevicePresenceFilter) -> Unit,
    onSelectBand: (DeviceFilter) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        StatTile(
            label = stringResource(R.string.device_status_online),
            value = "${uiState.onlineCount}",
            subtitle = if (uiState.offlineCount > 0) "${uiState.offlineCount} offline" else "${uiState.totalCount} total",
            valueColor = colors.success,
            modifier = Modifier.weight(1f),
            onClick = {
                onSelectBand(DeviceFilter.ALL)
                onSelectPresence(
                    if (uiState.presenceFilter == DevicePresenceFilter.ONLINE) {
                        DevicePresenceFilter.ALL
                    } else {
                        DevicePresenceFilter.ONLINE
                    }
                )
            }
        )
        StatTile(
            label = "5 GHz",
            value = "${uiState.wifi5gCount}",
            modifier = Modifier.weight(1f),
            onClick = {
                onSelectBand(
                    if (uiState.filterMode == DeviceFilter.WIFI_5G) DeviceFilter.ALL else DeviceFilter.WIFI_5G
                )
            }
        )
        StatTile(
            label = "2.4 GHz",
            value = "${uiState.wifi24gCount}",
            modifier = Modifier.weight(1f),
            onClick = {
                onSelectBand(
                    if (uiState.filterMode == DeviceFilter.WIFI_24G) DeviceFilter.ALL else DeviceFilter.WIFI_24G
                )
            }
        )
        StatTile(
            label = "LAN",
            value = "${uiState.lanCount}",
            modifier = Modifier.weight(1f),
            onClick = {
                onSelectBand(
                    if (uiState.filterMode == DeviceFilter.LAN) DeviceFilter.ALL else DeviceFilter.LAN
                )
            }
        )
    }
}

@Composable
private fun DeviceFilterAndSortCard(
    uiState: DevicesUiState,
    onSearchChange: (String) -> Unit,
    onSelectPresence: (DevicePresenceFilter) -> Unit,
    onSelectBand: (DeviceFilter) -> Unit,
    onSelectSort: (DeviceSort) -> Unit
) {
    val spacing = AirtelTheme.spacing
    SectionCard {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.devices_search_placeholder),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = AirtelTheme.shapes.tile,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = uiState.presenceFilter == DevicePresenceFilter.ALL,
                onClick = { onSelectPresence(DevicePresenceFilter.ALL) },
                label = { Text(stringResource(R.string.devices_filter_presence_all, uiState.totalCount)) }
            )
            FilterChip(
                selected = uiState.presenceFilter == DevicePresenceFilter.ONLINE,
                onClick = { onSelectPresence(DevicePresenceFilter.ONLINE) },
                label = { Text(stringResource(R.string.devices_filter_presence_online, uiState.onlineCount)) }
            )
            if (uiState.offlineCount > 0 || uiState.presenceFilter == DevicePresenceFilter.OFFLINE) {
                FilterChip(
                    selected = uiState.presenceFilter == DevicePresenceFilter.OFFLINE,
                    onClick = { onSelectPresence(DevicePresenceFilter.OFFLINE) },
                    label = { Text(stringResource(R.string.devices_filter_presence_offline, uiState.offlineCount)) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                DeviceFilter.ALL to "All bands",
                DeviceFilter.WIFI_5G to "5 GHz",
                DeviceFilter.WIFI_24G to "2.4 GHz",
                DeviceFilter.LAN to "LAN"
            ).forEach { (filter, label) ->
                FilterChip(
                    selected = uiState.filterMode == filter,
                    onClick = { onSelectBand(filter) },
                    label = { Text(label) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${stringResource(R.string.devices_sort_label)}:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = spacing.xs)
            )
            listOf(
                DeviceSort.SPEED to "Speed",
                DeviceSort.SIGNAL to "Signal",
                DeviceSort.NAME to "Name",
                DeviceSort.IP to "IP"
            ).forEach { (sort, label) ->
                FilterChip(
                    selected = uiState.sortMode == sort,
                    onClick = { onSelectSort(sort) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun DeviceListRow(
    device: DeviceItem,
    onClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val bandLabel = when {
        device.band.contains("5 GHz", ignoreCase = true) -> "5 GHz"
        device.band.contains("2.4 GHz", ignoreCase = true) -> "2.4 GHz"
        device.isLanConnection() -> "LAN"
        else -> device.band
    }
    val vendorHint = when {
        device.vendor.isNotBlank() -> device.vendor
        device.isPrivateMac -> "Private MAC"
        else -> null
    }
    val speedLabel = device.txRateMbps.takeIf { it.isNotBlank() && it != "-" }?.let { "$it Mbps" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AirtelTheme.shapes.card)
            .clickable(onClick = onClick),
        shape = AirtelTheme.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = if (device.isOnline) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = deviceTypeIcon(device.type),
                            contentDescription = null,
                            tint = if (device.isOnline) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(
                            if (device.isOnline) colors.success else MaterialTheme.colorScheme.outline
                        )
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = device.alias,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (device.hasCustomAlias) {
                        DeviceBadgePill(
                            label = "Renamed",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                val subParts = buildList {
                    if (device.ip.isNotBlank()) add(device.ip)
                    if (vendorHint != null) add(vendorHint)
                }
                if (subParts.isNotEmpty()) {
                    Text(
                        text = subParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall.merge(TabularMonospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val linkParts = buildList {
                    add(if (device.isOnline) bandLabel else "Offline lease")
                    if (device.ssid.isNotBlank() && device.isOnline) add(device.ssid)
                    if (speedLabel != null && device.isOnline) add(speedLabel)
                }
                Text(
                    text = linkParts.joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall.merge(TabularMonospace),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when {
                    !device.isOnline -> {
                        Icon(
                            imageVector = Icons.Rounded.SignalWifiOff,
                            contentDescription = stringResource(R.string.device_status_offline),
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    device.isLanConnection() -> {
                        Icon(
                            imageVector = Icons.Rounded.Cable,
                            contentDescription = "Ethernet",
                            tint = colors.success,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    else -> {
                        SignalBars(level = signalPercentToBars(device.signalPercent))
                    }
                }
                IconButton(
                    onClick = onRenameClick,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.device_action_rename),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
