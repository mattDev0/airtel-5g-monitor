package com.airtel.monitor.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.DeviceFilter
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DeviceSort
import com.airtel.monitor.data.model.DeviceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceFleetSection(
    devices: List<DeviceItem>,
    totalCount: Int,
    filterMode: DeviceFilter,
    sortMode: DeviceSort,
    searchQuery: String,
    onFilterChanged: (DeviceFilter) -> Unit,
    onSortChanged: (DeviceSort) -> Unit,
    onSearchChanged: (String) -> Unit,
    onOpenRename: (DeviceItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(searchQuery.isNotEmpty()) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Title, Count badge, Search toggle, Sort menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Connected Devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "$totalCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Search toggle icon button
                    IconButton(
                        onClick = {
                            searchVisible = !searchVisible
                            if (!searchVisible && searchQuery.isNotEmpty()) {
                                onSearchChanged("")
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Toggle Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Sort button with dropdown
                    Box {
                        IconButton(
                            onClick = { sortMenuExpanded = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            DeviceSort.values().forEach { sort ->
                                val sortLabel = when (sort) {
                                    DeviceSort.SPEED -> "Speed"
                                    DeviceSort.SIGNAL -> "Signal Quality"
                                    DeviceSort.NAME -> "Name"
                                    DeviceSort.IP -> "IP Address"
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = sortLabel,
                                            fontWeight = if (sort == sortMode) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sort == sortMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        onSortChanged(sort)
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Expandable Search Field
            AnimatedVisibility(
                visible = searchVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChanged,
                    placeholder = { Text("Search by name, IP, or MAC...", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }

            // Minimal Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(
                    Pair(DeviceFilter.ALL, "All"),
                    Pair(DeviceFilter.WIFI_5G, "5 GHz"),
                    Pair(DeviceFilter.WIFI_24G, "2.4 GHz"),
                    Pair(DeviceFilter.LAN, "LAN")
                )

                filters.forEach { (filter, label) ->
                    FilterChip(
                        selected = filterMode == filter,
                        onClick = { onFilterChanged(filter) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            // Minimalist Device List
            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching devices found" else "No connected devices",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    devices.forEach { device ->
                        MinimalDeviceItem(
                            device = device,
                            onOpenRename = { onOpenRename(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalDeviceItem(
    device: DeviceItem,
    onOpenRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLan = device.band.contains("LAN", ignoreCase = true) || device.band.contains("Ethernet", ignoreCase = true)

    val deviceIcon: ImageVector = when (device.type) {
        DeviceType.PHONE -> Icons.Default.Smartphone
        DeviceType.TABLET -> Icons.Default.Tablet
        DeviceType.PC -> Icons.Default.Laptop
        DeviceType.TV -> Icons.Default.Tv
        DeviceType.GAMING -> Icons.Default.SportsEsports
        DeviceType.ROUTER -> Icons.Default.Router
        DeviceType.DEVICE -> Icons.Default.Devices
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Leading Icon Circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = deviceIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Name & Info Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = device.alias,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )

                val bandLabel = when {
                    isLan -> "LAN"
                    device.band.contains("5 GHz", ignoreCase = true) -> "5 GHz"
                    else -> "2.4 GHz"
                }
                val speedLabel = if (device.txRateMbps.isNotEmpty() && device.txRateMbps != "-" && device.txRateMbps != "0") " • ${device.txRateMbps} Mbps" else ""

                Text(
                    text = "${device.ip} • $bandLabel$speedLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }

            // Trailing Actions: Copy IP & Rename
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("IP Address", device.ip))
                        Toast.makeText(context, "Copied ${device.ip}", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy IP",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(15.dp)
                    )
                }

                IconButton(
                    onClick = onOpenRename,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Rename",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

