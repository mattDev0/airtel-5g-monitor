package com.airtel.monitor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.CellularMetrics
import com.airtel.monitor.data.model.HardwareMetrics
import com.airtel.monitor.data.model.WanMetrics

@Composable
fun MinimalWanSpeedCard(
    wan: WanMetrics,
    modifier: Modifier = Modifier
) {
    val dlProgress by animateFloatAsState(
        targetValue = (wan.downloadMbps / 100.0).toFloat().coerceIn(0.01f, 1f),
        label = "dlProgress"
    )
    val ulProgress by animateFloatAsState(
        targetValue = (wan.uploadMbps / 50.0).toFloat().coerceIn(0.01f, 1f),
        label = "ulProgress"
    )

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Label & Session Total
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Internet Speed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "Total: ${wan.totalFlowMb} MB",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Dual Speed Indicators (Download & Upload)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Download Column
                SpeedMetricColumn(
                    label = "Download",
                    speedMbps = wan.downloadMbps,
                    speedKbps = wan.downloadKbps,
                    peakMbps = wan.peakDlMbps,
                    progress = dlProgress,
                    isDownload = true,
                    modifier = Modifier.weight(1f)
                )

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(72.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )

                // Upload Column
                SpeedMetricColumn(
                    label = "Upload",
                    speedMbps = wan.uploadMbps,
                    speedKbps = wan.uploadKbps,
                    peakMbps = wan.peakUlMbps,
                    progress = ulProgress,
                    isDownload = false,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SpeedMetricColumn(
    label: String,
    speedMbps: Double,
    speedKbps: Double,
    peakMbps: Double,
    progress: Float,
    isDownload: Boolean,
    modifier: Modifier = Modifier
) {
    val icon = if (isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward
    val activeColor = if (isDownload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val containerColor = if (isDownload) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tag row with icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = activeColor,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Speed Numbers
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = String.format("%.2f", speedMbps),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 30.sp
            )
            Text(
                text = "Mbps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // Slim Progress Bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = activeColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round
        )

        // Subtext: KB/s & Peak
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${String.format("%.0f", speedKbps)} kB/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Peak ${String.format("%.1f", peakMbps)}M",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Backward compatibility wrapper for HeroMetricsGrid.
 */
@Composable
fun HeroMetricsGrid(
    wan: WanMetrics,
    cellular: CellularMetrics,
    hardware: HardwareMetrics,
    deviceCount: Int,
    modifier: Modifier = Modifier
) {
    MinimalWanSpeedCard(
        wan = wan,
        modifier = modifier
    )
}

