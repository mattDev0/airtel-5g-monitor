@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.airtel.monitor.ui.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.core.util.OuiVendorLookup
import com.airtel.monitor.data.dto.formatDurationText
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DeviceType
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingValueRow
import com.airtel.monitor.ui.designsystem.SignalBars
import com.airtel.monitor.ui.theme.AirtelTheme

internal fun deviceTypeIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.PHONE -> Icons.Rounded.Smartphone
    DeviceType.TABLET -> Icons.Rounded.Tablet
    DeviceType.PC -> Icons.Rounded.Laptop
    DeviceType.TV -> Icons.Rounded.Tv
    DeviceType.GAMING -> Icons.Rounded.SportsEsports
    DeviceType.ROUTER -> Icons.Rounded.Router
    DeviceType.DEVICE -> Icons.Rounded.Devices
}

internal fun signalPercentToBars(percent: Int): Int = when {
    percent <= 0 -> 0
    percent < 25 -> 1
    percent < 45 -> 2
    percent < 65 -> 3
    percent < 85 -> 4
    else -> 5
}

internal fun DeviceItem.isLanConnection(): Boolean =
    band.contains("LAN", ignoreCase = true) || band.contains("Ethernet", ignoreCase = true)

@Composable
internal fun deviceVendorSummary(device: DeviceItem): String = when {
    device.vendor.isNotBlank() -> device.vendor
    device.isPrivateMac -> stringResource(R.string.device_private_mac)
    else -> stringResource(R.string.device_vendor_unknown)
}

@Composable
internal fun DeviceBadgePill(
    label: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = AirtelTheme.shapes.chip,
        color = containerColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun DeviceDetailSheet(
    device: DeviceItem,
    onDismiss: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onRename: () -> Unit,
    onResetAlias: () -> Unit,
    isReserved: Boolean = false,
    isBlocked: Boolean = false,
    showParentalButton: Boolean = false,
    isPolicySaving: Boolean = false,
    onReserveStaticIp: () -> Unit = {},
    onToggleBlock: () -> Unit = {},
    onOpenParental: () -> Unit = {}
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val formattedMac = OuiVendorLookup.formatMac(device.mac)
    var confirmReserve by remember { mutableStateOf(false) }
    var confirmBlockToggle by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl, vertical = spacing.sm)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = deviceTypeIcon(device.type),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = device.alias,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        DeviceBadgePill(
                            label = if (device.isOnline) {
                                stringResource(R.string.device_status_online)
                            } else {
                                stringResource(R.string.device_status_offline)
                            },
                            containerColor = if (device.isOnline) {
                                colors.success.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (device.isOnline) {
                                colors.success
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        DeviceBadgePill(
                            label = device.band,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (device.isRouter) {
                            DeviceBadgePill(
                                label = stringResource(R.string.device_role_router),
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (isReserved) {
                            DeviceBadgePill(
                                label = stringResource(R.string.device_badge_reserved),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (isBlocked) {
                            DeviceBadgePill(
                                label = stringResource(R.string.device_badge_blocked),
                                containerColor = colors.error.copy(alpha = 0.16f),
                                contentColor = colors.error
                            )
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                if (formattedMac.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { onCopy("MAC", formattedMac) },
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.device_action_copy_mac),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                if (device.ip.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { onCopy("IP", device.ip) },
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.device_action_copy_ip),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = onRename,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.device_action_rename),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                if (device.hasCustomAlias) {
                    OutlinedButton(
                        onClick = onResetAlias,
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.device_action_reset_name),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                if (!device.isRouter && device.ip.isNotBlank() && formattedMac.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { confirmReserve = true },
                        enabled = !isPolicySaving && !isReserved,
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(
                            imageVector = if (isReserved) Icons.Rounded.CheckCircleOutline else Icons.Rounded.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(
                                if (isReserved) R.string.device_action_reserved_ip
                                else R.string.device_action_reserve_ip
                            ),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                if (!device.isRouter && formattedMac.isNotBlank()) {
                    OutlinedButton(
                        onClick = { confirmBlockToggle = true },
                        enabled = !isPolicySaving,
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(
                                if (isBlocked) R.string.device_action_unblock
                                else R.string.device_action_block
                            ),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                if (showParentalButton && !device.isRouter) {
                    OutlinedButton(
                        onClick = onOpenParental,
                        enabled = !isPolicySaving,
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.devices_policy_parental),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }

            SectionCard(title = "Network Identity") {
                SettingValueRow(label = "Vendor", value = deviceVendorSummary(device))
                if (device.isPrivateMac) {
                    Text(
                        text = stringResource(R.string.device_private_mac_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spacing.sm)
                    )
                }
                SettingValueRow(label = "MAC Address", value = formattedMac.ifBlank { "-" })
                SettingValueRow(label = "IPv4 Address", value = device.ip.ifBlank { "-" })
                if (device.ipv6.isNotBlank()) {
                    SettingValueRow(label = "IPv6 Address", value = device.ipv6)
                }
                SettingValueRow(label = "Hostname", value = device.hostname)
            }

            SectionCard(title = "Link & Lease") {
                SettingValueRow(label = "Connection", value = device.band)
                if (device.ssid.isNotBlank()) {
                    SettingValueRow(label = "Wi-Fi SSID", value = device.ssid)
                }
                SettingValueRow(
                    label = "Interface",
                    value = device.interfaceName.ifBlank { "-" }.uppercase()
                )
                if (!device.isLanConnection() && device.rssi.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.sm, vertical = spacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Wi-Fi Signal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${device.rssi} dBm (${device.signalPercent}%)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            SignalBars(level = signalPercentToBars(device.signalPercent))
                        }
                    }
                }
                val rateSummary = formatLinkRateSummary(device)
                if (rateSummary != null) {
                    SettingValueRow(label = "Link Speed", value = rateSummary)
                }
                val leaseText = formatLeaseExpiry(device.expires)
                if (leaseText != null) {
                    SettingValueRow(label = "DHCP Lease", value = leaseText)
                }
            }
        }
    }

    if (confirmReserve) {
        ConfirmSheet(
            title = stringResource(R.string.device_reserve_confirm_title),
            impactMessage = stringResource(
                R.string.device_reserve_confirm_impact,
                device.alias,
                formattedMac,
                device.ip
            ),
            confirmLabel = stringResource(R.string.device_action_reserve_ip),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                confirmReserve = false
                onReserveStaticIp()
            },
            onDismiss = { confirmReserve = false }
        )
    }

    if (confirmBlockToggle) {
        ConfirmSheet(
            title = stringResource(
                if (isBlocked) R.string.device_unblock_confirm_title else R.string.device_block_confirm_title,
                device.alias
            ),
            impactMessage = stringResource(
                if (isBlocked) R.string.device_unblock_confirm_impact else R.string.device_block_confirm_impact,
                formattedMac
            ),
            confirmLabel = stringResource(
                if (isBlocked) R.string.device_action_unblock else R.string.device_action_block
            ),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                confirmBlockToggle = false
                onToggleBlock()
            },
            onDismiss = { confirmBlockToggle = false }
        )
    }
}

internal fun formatLinkRateSummary(device: DeviceItem): String? {
    if (device.isLanConnection()) return "Wired Ethernet"
    val tx = device.txRateMbps.takeIf { it.isNotBlank() && it != "-" }
    val rx = device.rxRateMbps.takeIf { it.isNotBlank() && it != "-" }
    return when {
        tx != null && rx != null -> "TX $tx Mbps · RX $rx Mbps"
        tx != null -> "TX $tx Mbps"
        rx != null -> "RX $rx Mbps"
        else -> null
    }
}

@Composable
private fun formatLeaseExpiry(expires: String): String? {
    val clean = expires.trim()
    if (clean.isEmpty()) return null
    if (clean == "0") return stringResource(R.string.device_lease_expired)
    return formatDurationText(clean)
}
