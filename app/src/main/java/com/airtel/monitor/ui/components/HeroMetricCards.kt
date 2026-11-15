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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.R
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.util.NetworkFormatters
import com.airtel.monitor.data.model.BandwidthPoint
import com.airtel.monitor.data.model.CellularMetrics
import com.airtel.monitor.data.model.HardwareMetrics
import com.airtel.monitor.data.model.WanMetrics
import com.airtel.monitor.ui.designsystem.Sparkline
import com.airtel.monitor.ui.theme.AirtelTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

@Composable
fun MinimalWanSpeedCard(
    wan: WanMetrics,
    modifier: Modifier = Modifier,
    history: List<BandwidthPoint> = emptyList(),
    sparklineLabel: String? = null,
    sparklineLegend: String? = null,
    speedUnit: SpeedUnitPreference = SpeedUnitPreference.MBPS
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_speed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.home_speed_total_fmt, wan.totalFlowMb),
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
                    label = stringResource(R.string.home_speed_download),
                    speedMbps = wan.downloadMbps,
                    speedKbps = wan.downloadKbps,
                    peakMbps = wan.peakDlMbps,
                    progress = dlProgress,
                    isDownload = true,
                    speedUnit = speedUnit,
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
                    label = stringResource(R.string.home_speed_upload),
                    speedMbps = wan.uploadMbps,
                    speedKbps = wan.uploadKbps,
                    peakMbps = wan.peakUlMbps,
                    progress = ulProgress,
                    isDownload = false,
                    speedUnit = speedUnit,
                    modifier = Modifier.weight(1f)
                )
            }

            if (history.size >= 2) {
                val colors = AirtelTheme.colors
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                if (!sparklineLabel.isNullOrBlank() || !sparklineLegend.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sparklineLabel.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!sparklineLegend.isNullOrBlank()) {
                            Text(
                                text = sparklineLegend,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                val dlValues = remember(history) { history.map { it.downloadMbps.toFloat() } }
                val ulValues = remember(history) { history.map { it.uploadMbps.toFloat() } }
                Sparkline(
                    values = dlValues,
                    lineColor = colors.downloadSeries,
                    secondaryValues = ulValues,
                    secondaryColor = colors.uploadSeries,
                    modifier = Modifier.fillMaxWidth()
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
    speedUnit: SpeedUnitPreference,
    modifier: Modifier = Modifier
) {
    val icon = if (isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward
    val activeColor = if (isDownload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val containerColor = if (isDownload) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val (formattedSpeed, unitLabel) = NetworkFormatters.formatThroughput(speedMbps, speedUnit)
    val peakText = NetworkFormatters.formatPeakThroughput(peakMbps, speedUnit)

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
                text = formattedSpeed,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 30.sp
            )
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        // Live load bar: the Expressive wavy indicator ripples while traffic flows
        LinearWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = activeColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )

        // Subtext: kB/s & Peak (wraps cleanly at 200% font scale)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.home_speed_kbps_fmt, String.format(Locale.US, "%.0f", speedKbps)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = peakText,
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

