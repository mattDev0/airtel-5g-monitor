@file:OptIn(
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.feature.signal

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.dto.formatCaSummary
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.ui.components.LockEditorSheet
import com.airtel.monitor.ui.designsystem.CarrierRow
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.EmptyState
import com.airtel.monitor.ui.designsystem.LineChart
import com.airtel.monitor.ui.designsystem.LineChartSeries
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingValueRow
import com.airtel.monitor.ui.designsystem.SignalMeter
import com.airtel.monitor.ui.designsystem.SignalQualityBand
import com.airtel.monitor.ui.designsystem.StatTile
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

@Composable
fun SignalScreen(
    viewModel: SignalViewModel,
    contentPadding: PaddingValues,
    onOpenAlignment: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState.routerState ?: RouterState()
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    var showRescanConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.ensureNeighborsLoaded()
    }

    val copyToClipboard: (String, String) -> Unit = { label, value ->
        if (value.isNotBlank() && value != "-") {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
            viewModel.emitFeedback(resources.getString(R.string.copied_to_clipboard_fmt, label))
        }
    }

    val caSummary = formatCaSummary(state.carriers).takeIf { it != "-" }
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = "carrier_summary_pill"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "alignment_and_carriers") {
            SectionCard(
                title = stringResource(R.string.signal_carriers_title),
                trailing = {
                    if (caSummary != null) {
                        Surface(
                            modifier = sharedModifier,
                            shape = AirtelTheme.shapes.chip,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = caSummary,
                                style = MaterialTheme.typography.labelSmall.merge(TabularMonospace),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = spacing.sm, vertical = 2.dp)
                            )
                        }
                    }
                }
            ) {
                Button(
                    onClick = onOpenAlignment,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(imageVector = Icons.Rounded.Explore, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(R.string.signal_open_alignment))
                }

                if (state.carriers.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.signal_carriers_title),
                        message = stringResource(R.string.signal_carriers_empty)
                    )
                } else {
                    state.carriers.forEach { carrier ->
                        CarrierRow(carrier = carrier)
                    }
                }
            }
        }

        if (state.cellular.has5gMetrics) {
            item(key = "meters_5g") {
                SectionCard(
                    title = stringResource(R.string.signal_meters_5g),
                    subtitle = state.cellular.networkType
                ) {
                    SignalMeterRow(label = "5G RSRP", raw = state.cellular.rsrp5g, unit = "dBm", eval = ::evalRsrp)
                    SignalMeterRow(label = "5G RSRQ", raw = state.cellular.rsrq5g, unit = "dB", eval = ::evalRsrq)
                    SignalMeterRow(label = "5G SINR", raw = state.cellular.sinr5g, unit = "dB", eval = ::evalSinr)
                    SignalMeterRow(label = "5G RSSI", raw = state.cellular.rssi5g, unit = "dBm", eval = ::evalRssi)
                    SignalMeterRow(label = "5G CQI", raw = state.cellular.nrCqi, unit = "", eval = ::evalCqi)
                }
            }
        }

        item(key = "meters_4g") {
            SectionCard(
                title = stringResource(R.string.signal_meters_4g),
                subtitle = state.cellular.networkType.takeIf { it != "-" }
            ) {
                SignalMeterRow(label = "4G RSRP", raw = state.cellular.rsrp4g, unit = "dBm", eval = ::evalRsrp)
                SignalMeterRow(label = "4G RSRQ", raw = state.cellular.rsrq4g, unit = "dB", eval = ::evalRsrq)
                SignalMeterRow(label = "4G SINR", raw = state.cellular.sinr4g, unit = "dB", eval = ::evalSinr)
                SignalMeterRow(label = "4G RSSI", raw = state.cellular.rssi4g, unit = "dBm", eval = ::evalRssi)
                SignalMeterRow(label = "4G CQI", raw = state.cellular.lteCqi, unit = "", eval = ::evalCqi)
            }
        }

        item(key = "link_details") {
            val link = state.linkDetails
            SectionCard(title = stringResource(R.string.signal_link_details_title)) {
                SettingValueRow(label = "4G DL / UL MCS", value = "${link.dlMcs4g} / ${link.ulMcs4g}")
                SettingValueRow(label = "4G Max DL / UL QAM", value = "${link.maxDlQam4g} / ${link.maxUlQam4g}")
                SettingValueRow(
                    label = "4G Rank / MIMO",
                    value = if (link.mimoStatus != "-") "${link.rank4g} · ${link.mimoStatus}" else link.rank4g
                )
                SettingValueRow(label = "4G BLER", value = link.bler4g)

                if (link.has5gDetails) {
                    SettingValueRow(label = "5G DL / UL MCS", value = "${link.dlMcs5g} / ${link.ulMcs5g}")
                    SettingValueRow(label = "5G Max DL / UL QAM", value = "${link.maxDlQam5g} / ${link.maxUlQam5g}")
                    SettingValueRow(label = "5G Rank / BLER", value = "${link.rank5g} / ${link.bler5g}")
                }
            }
        }

        item(key = "cell_identity") {
            val id = state.cellIdentity
            SectionCard(
                title = stringResource(R.string.signal_cell_identity_title),
                subtitle = stringResource(R.string.signal_cell_identity_hint)
            ) {
                CopyableIdentityRow("PLMN", id.plmn, copyToClipboard)
                CopyableIdentityRow("eNB ID (4G)", id.enbId4g, copyToClipboard)
                CopyableIdentityRow("Cell ID (4G)", id.cellId4g, copyToClipboard)
                CopyableIdentityRow("TAC (4G)", id.tac4g, copyToClipboard)
                CopyableIdentityRow("ECGI", id.ecgi, copyToClipboard)
                if (id.has5gIdentity) {
                    CopyableIdentityRow("gNB ID (5G)", id.enbId5g, copyToClipboard)
                    CopyableIdentityRow("Cell ID (5G)", id.cellId5g, copyToClipboard)
                    CopyableIdentityRow("TAC (5G)", id.tac5g, copyToClipboard)
                    CopyableIdentityRow("NCGI", id.ncgi, copyToClipboard)
                }
            }
        }

        item(key = "signal_history") {
            val pts = uiState.signalHistory
            val primaryColor = MaterialTheme.colorScheme.primary
            val tertiaryColor = MaterialTheme.colorScheme.tertiary
            val rsrpSeries = remember(pts, primaryColor, tertiaryColor) {
                buildList {
                    val r4 = pts.mapNotNull { it.rsrp4g }
                    val r5 = pts.mapNotNull { it.rsrp5g }
                    if (r4.size >= 2) add(LineChartSeries(r4, primaryColor, fillArea = true))
                    if (r5.size >= 2) add(LineChartSeries(r5, tertiaryColor, fillArea = false))
                }
            }
            val sinrSeries = remember(pts, colors.success, colors.info) {
                buildList {
                    val s4 = pts.mapNotNull { it.sinr4g }
                    val s5 = pts.mapNotNull { it.sinr5g }
                    if (s4.size >= 2) add(LineChartSeries(s4, colors.success, fillArea = true))
                    if (s5.size >= 2) add(LineChartSeries(s5, colors.info, fillArea = false))
                }
            }

            SectionCard(
                title = stringResource(R.string.signal_history_title),
                subtitle = if (pts.size >= 2) "${pts.first().timeLabel} – ${pts.last().timeLabel}" else null
            ) {
                if (rsrpSeries.isEmpty() && sinrSeries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.signal_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (rsrpSeries.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.signal_history_legend_rsrp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LineChart(
                            series = rsrpSeries,
                            fixedMin = -125f,
                            fixedMax = -55f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        )
                    }
                    if (sinrSeries.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.signal_history_legend_sinr),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LineChart(
                            series = sinrSeries,
                            fixedMin = -5f,
                            fixedMax = 30f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        )
                    }
                }
            }
        }

        if (uiState.neighborCells.isNotEmpty()) {
            item(key = "neighbor_cells") {
                SectionCard(
                    title = stringResource(R.string.signal_neighbors_title),
                    subtitle = "${uiState.neighborCells.size} cells",
                    trailing = {
                        IconButton(
                            onClick = viewModel::loadNeighborCells,
                            enabled = !uiState.neighborsLoading
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.signal_neighbors_refresh)
                            )
                        }
                    }
                ) {
                    uiState.neighborCells.forEach { cell ->
                        SettingValueRow(
                            label = "${cell.tech.name} · EARFCN ${cell.earfcn} · PCI ${cell.pci}",
                            value = "${cell.rsrp} dBm / ${cell.sinr} dB"
                        )
                    }
                }
            }
        }

        item(key = "locks_and_mode") {
            val mode = state.networkModeInfo
            SectionCard(
                title = stringResource(R.string.signal_radio_mode_title),
                subtitle = stringResource(R.string.signal_radio_mode_readonly_note)
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    StatTile(
                        label = "Mode",
                        value = mode.networkMode.ifEmpty { "-" },
                        modifier = Modifier.weight(1f, fill = true)
                    )
                    StatTile(
                        label = "4G CA",
                        value = if (mode.lteCa) "On" else "Off",
                        modifier = Modifier.weight(1f, fill = true)
                    )
                    StatTile(
                        label = "5G CA",
                        value = if (mode.nrCa) "On" else "Off",
                        modifier = Modifier.weight(1f, fill = true)
                    )
                }

                FilledTonalButton(
                    onClick = viewModel::openLockEditor,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(imageVector = Icons.Rounded.Lock, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(R.string.signal_open_locks))
                }

                OutlinedButton(
                    onClick = { showRescanConfirm = true },
                    enabled = !uiState.rescanBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(R.string.signal_rescan_network))
                }
            }
        }
    }

    if (showRescanConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.confirm_rescan_title),
            impactMessage = stringResource(R.string.confirm_rescan_impact),
            confirmLabel = stringResource(R.string.confirm_rescan_button),
            onConfirm = {
                showRescanConfirm = false
                viewModel.rescanNetwork()
            },
            onDismiss = { showRescanConfirm = false },
            tier = ConfirmTier.CONNECTIVITY
        )
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
            onSaveNr = viewModel::saveNrCellLock,
            carriers = state.carriers
        )
    }
}

@Composable
private fun CopyableIdentityRow(
    label: String,
    value: String,
    onCopy: (String, String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AirtelTheme.shapes.tile)
            .combinedClickable(
                onClick = {},
                onLongClick = { onCopy(label, value) }
            )
            .padding(horizontal = AirtelTheme.spacing.sm, vertical = AirtelTheme.spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.merge(TabularMonospace),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SignalMeterRow(
    label: String,
    raw: String,
    unit: String,
    eval: (Float) -> Pair<SignalQualityBand, Float>
) {
    val parsed = raw.trim().toFloatOrNull()
    val (quality, progress) = if (parsed != null) eval(parsed) else SignalQualityBand.UNKNOWN to 0f
    val valueText = if (parsed != null) {
        if (unit.isNotEmpty()) "$raw $unit" else raw
    } else {
        "--"
    }
    SignalMeter(label = label, valueText = valueText, progress = progress, quality = quality)
}

private fun evalRsrp(v: Float): Pair<SignalQualityBand, Float> {
    val band = when {
        v >= -80f -> SignalQualityBand.EXCELLENT
        v >= -90f -> SignalQualityBand.GOOD
        v >= -100f -> SignalQualityBand.FAIR
        else -> SignalQualityBand.POOR
    }
    return band to ((v + 130f) / 70f).coerceIn(0f, 1f)
}

private fun evalSinr(v: Float): Pair<SignalQualityBand, Float> {
    val band = when {
        v >= 20f -> SignalQualityBand.EXCELLENT
        v >= 13f -> SignalQualityBand.GOOD
        v >= 0f -> SignalQualityBand.FAIR
        else -> SignalQualityBand.POOR
    }
    return band to ((v + 5f) / 35f).coerceIn(0f, 1f)
}

private fun evalRsrq(v: Float): Pair<SignalQualityBand, Float> {
    val band = when {
        v >= -10f -> SignalQualityBand.EXCELLENT
        v >= -15f -> SignalQualityBand.GOOD
        v >= -20f -> SignalQualityBand.FAIR
        else -> SignalQualityBand.POOR
    }
    return band to ((v + 25f) / 22f).coerceIn(0f, 1f)
}

private fun evalRssi(v: Float): Pair<SignalQualityBand, Float> {
    val band = when {
        v >= -65f -> SignalQualityBand.EXCELLENT
        v >= -75f -> SignalQualityBand.GOOD
        v >= -85f -> SignalQualityBand.FAIR
        else -> SignalQualityBand.POOR
    }
    return band to ((v + 110f) / 60f).coerceIn(0f, 1f)
}

private fun evalCqi(v: Float): Pair<SignalQualityBand, Float> {
    val band = when {
        v >= 12f -> SignalQualityBand.EXCELLENT
        v >= 8f -> SignalQualityBand.GOOD
        v >= 4f -> SignalQualityBand.FAIR
        else -> SignalQualityBand.POOR
    }
    return band to (v / 15f).coerceIn(0f, 1f)
}
