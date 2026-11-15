@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.ui.designsystem.LineChart
import com.airtel.monitor.ui.designsystem.LineChartSeries
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AlignmentScreen(
    viewModel: SignalViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snap = uiState.alignmentSnapshot
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors

    DisposableEffect(Unit) {
        viewModel.enterAlignmentMode()
        onDispose {
            viewModel.exitAlignmentMode()
        }
    }

    DisposableEffect(view) {
        val prevKeepOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = prevKeepOn
        }
    }

    LaunchedEffect(snap?.timestampMs, uiState.alignmentSoundEnabled) {
        val sample = snap ?: return@LaunchedEffect
        val curSinr = sample.primarySinr
        val topSinr = uiState.bestSinr
        val isNewBest = curSinr != null && topSinr != null && curSinr >= topSinr
        haptics.performHapticFeedback(
            if (isNewBest) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
        )
        if (uiState.alignmentSoundEnabled) {
            withContext(Dispatchers.IO) {
                AlignmentTonePlayer.playPip(curSinr)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.alignment_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.alignment_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = viewModel::toggleAlignmentSound) {
                    Icon(
                        imageVector = if (uiState.alignmentSoundEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = stringResource(
                            if (uiState.alignmentSoundEnabled) R.string.alignment_sound_on else R.string.alignment_sound_muted
                        ),
                        tint = if (uiState.alignmentSoundEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = AirtelTheme.shapes.chip,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${snap?.activeTechLabel ?: "--"} · ${snap?.networkType ?: "--"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)
                    )
                }
                Text(
                    text = snap?.carrierSummary?.takeIf { it != "-" } ?: "--",
                    style = MaterialTheme.typography.labelMedium.merge(TabularMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AlignmentBigGaugeCard(
                label = "SINR (${snap?.activeTechLabel ?: "Primary"})",
                current = snap?.primarySinr,
                unit = "dB",
                best = uiState.bestSinr,
                bestLabel = stringResource(R.string.alignment_best_sinr),
                accentColor = colors.success
            )

            AlignmentBigGaugeCard(
                label = "RSRP (${snap?.activeTechLabel ?: "Primary"})",
                current = snap?.primaryRsrp,
                unit = "dBm",
                best = uiState.bestRsrp,
                bestLabel = stringResource(R.string.alignment_best_rsrp),
                accentColor = MaterialTheme.colorScheme.primary
            )

            if (snap?.rsrp5g != null && snap.rsrp4g != null) {
                SectionCard(title = "4G Anchor Reference") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "RSRP ${formatFloatMetric(snap.rsrp4g, "dBm")}",
                            style = MaterialTheme.typography.titleMedium.merge(TabularMonospace)
                        )
                        Text(
                            text = "SINR ${formatFloatMetric(snap.sinr4g, "dB")}",
                            style = MaterialTheme.typography.titleMedium.merge(TabularMonospace)
                        )
                    }
                }
            }

            if (uiState.alignmentSinrHistory.size >= 2 || uiState.alignmentRsrpHistory.size >= 2) {
                SectionCard(title = "Live Aiming Trend (30s)") {
                    LineChart(
                        series = buildList {
                            if (uiState.alignmentSinrHistory.size >= 2) {
                                add(
                                    LineChartSeries(
                                        values = uiState.alignmentSinrHistory,
                                        color = colors.success,
                                        fillArea = true
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                    )
                    Spacer(Modifier.height(spacing.xs))
                    LineChart(
                        series = buildList {
                            if (uiState.alignmentRsrpHistory.size >= 2) {
                                add(
                                    LineChartSeries(
                                        values = uiState.alignmentRsrpHistory,
                                        color = MaterialTheme.colorScheme.primary,
                                        fillArea = false
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                    )
                }
            }

            FilledTonalButton(
                onClick = viewModel::resetAlignmentBest,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes()
            ) {
                Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null)
                Text("  " + stringResource(R.string.alignment_reset_best))
            }
        }
    }
}

@Composable
private fun AlignmentBigGaugeCard(
    label: String,
    current: Float?,
    unit: String,
    best: Float?,
    bestLabel: String,
    accentColor: androidx.compose.ui.graphics.Color
) {
    val spacing = AirtelTheme.spacing
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = AirtelTheme.shapes.chip,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = "$bestLabel: ${formatFloatMetric(best, unit)}",
                    style = MaterialTheme.typography.labelMedium.merge(TabularMonospace),
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = if (current != null) formatFloatNumber(current) else "--",
                style = MaterialTheme.typography.displayMedium.merge(TabularMonospace),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (current != null && best != null) {
                val delta = current - best
                val deltaText = String.format(Locale.US, "%+.1f", delta)
                Text(
                    text = deltaText,
                    style = MaterialTheme.typography.titleSmall.merge(TabularMonospace),
                    color = if (delta >= 0f) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}

private fun formatFloatNumber(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun formatFloatMetric(value: Float?, unit: String): String =
    if (value == null) "--" else "${formatFloatNumber(value)} $unit"
