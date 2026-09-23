package com.airtel.monitor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.CellLockInfo
import com.airtel.monitor.data.model.CellularMetrics
import com.airtel.monitor.data.model.HardwareMetrics
import com.airtel.monitor.ui.theme.StatusSuccess
import com.airtel.monitor.ui.theme.StatusWarning

@Composable
fun CellularCellLockCard(
    cellLock: CellLockInfo,
    cellular: CellularMetrics,
    hardware: HardwareMetrics? = null,
    onEditLocks: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

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
            // Header Row: Network Badge, Operator, Signal Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Network Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = if (cellular.networkType.isNotEmpty() && cellular.networkType != "-") cellular.networkType else "No Signal",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Operator
                    Text(
                        text = if (cellular.operator.isNotEmpty() && cellular.operator != "-") cellular.operator else "Airtel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Signal indicator
                MinimalSignalIndicator(
                    signalLevel = cellular.signalLvl,
                    rsrp = cellular.rsrp5g
                )
            }

            // Minimal Telemetry Pills Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val band5g = if (cellLock.currentBand5g.isNotEmpty() && cellLock.currentBand5g != "-") "n${cellLock.currentBand5g}" else "-"
                val pci = if (cellLock.servingPci5g.isNotEmpty() && cellLock.servingPci5g != "-") "PCI ${cellLock.servingPci5g}" else "-"

                TelemetryPill(label = "Band", value = band5g, modifier = Modifier.weight(1f))
                TelemetryPill(label = "Cell", value = pci, modifier = Modifier.weight(1f))

                if (hardware != null && hardware.temperature.isNotEmpty() && hardware.temperature != "--") {
                    TelemetryPill(label = "Temp", value = "${hardware.temperature}°C", modifier = Modifier.weight(1f))
                    TelemetryPill(label = "CPU", value = "${hardware.cpuUsage.toInt()}%", modifier = Modifier.weight(1f))
                } else {
                    val sinrVal = if (cellular.sinr5g.isNotEmpty() && cellular.sinr5g != "-") "${cellular.sinr5g} dB" else "-"
                    TelemetryPill(label = "SINR", value = sinrVal, modifier = Modifier.weight(1f))
                }
            }

            // Cell Lock Status & Expand Toggle
            val isLocked = cellLock.lteLockEnabled || cellLock.nrLockEnabled || cellLock.bandLock4gEnabled || cellLock.bandLock5gEnabled
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isLocked) StatusWarning else StatusSuccess)
                    )
                    Text(
                        text = if (isLocked) "Cell Lock Active" else "Cell Lock: Auto (Default)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (isExpanded) "Hide details" else "Details",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Expandable Detailed Telemetry
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRow(title = "5G Serving EARFCN/ARFCN", value = cellLock.servingFreq5g)
                    DetailRow(title = "4G Serving PCI / EARFCN", value = "${cellLock.servingPci4g} / ${cellLock.servingFreq4g}")
                    DetailRow(title = "4G Active Bands", value = if (cellLock.currentBands4g.isNotEmpty()) "B${cellLock.currentBands4g.replace("+", " + B")}" else "-")
                    DetailRow(title = "4G Signal (RSRP / SINR)", value = "${cellular.rsrp4g} dBm / ${cellular.sinr4g} dB")
                    DetailRow(title = "5G CQI / 4G CQI", value = "${cellular.nrCqi} / ${cellular.lteCqi}")
                    DetailRow(
                        title = "4G Cell Lock",
                        value = if (cellLock.lteLockEnabled) "PCI ${cellLock.lteLockPci} @ ${cellLock.lteLockFreq}" else "Auto"
                    )
                    DetailRow(
                        title = "5G Cell Lock",
                        value = if (cellLock.nrLockEnabled) "PCI ${cellLock.nrLockPci} @ ${cellLock.nrLockFreq}" else "Auto"
                    )
                }
            }

            onEditLocks?.let {
                FilledTonalButton(
                    onClick = it,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Band & Cell Lock")
                }
            }
        }
    }
}

@Composable
private fun TelemetryPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MinimalSignalIndicator(
    signalLevel: Int,
    rsrp: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (rsrp.isNotEmpty() && rsrp != "-") {
            Text(
                text = "$rsrp dBm",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val heights = listOf(4.dp, 7.dp, 10.dp, 14.dp)
            heights.forEachIndexed { index, h ->
                val active = index < signalLevel
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(h)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                )
            }
        }
    }
}

@Composable
private fun DetailRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (value.isNotBlank() && value != "-") value else "None",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

