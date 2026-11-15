@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.dto.formatCaSummary
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.ui.components.LockEditorSheet
import com.airtel.monitor.ui.designsystem.CarrierRow
import com.airtel.monitor.ui.designsystem.EmptyState
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SignalMeter
import com.airtel.monitor.ui.designsystem.SignalQualityBand
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun SignalScreen(
    viewModel: SignalViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState.routerState ?: RouterState()
    val spacing = AirtelTheme.spacing
    val caSummary = formatCaSummary(state.carriers).takeIf { it != "-" }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "carriers_card") {
            SectionCard(
                title = stringResource(R.string.signal_carriers_title),
                subtitle = caSummary,
                trailing = {
                    FilledTonalButton(
                        onClick = viewModel::openLockEditor,
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Icon(imageVector = Icons.Rounded.Lock, contentDescription = null)
                        Text(" Locks")
                    }
                }
            ) {
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

        item(key = "signal_meters") {
            SectionCard(
                title = "Signal Quality",
                subtitle = state.cellular.networkType.takeIf { it != "-" }
            ) {
                if (state.cellular.rsrp5g != "-" && state.cellular.rsrp5g.isNotBlank()) {
                    val (q5, p5) = rsrpQuality(state.cellular.rsrp5g)
                    SignalMeter(
                        label = "5G RSRP",
                        valueText = "${state.cellular.rsrp5g} dBm",
                        progress = p5,
                        quality = q5
                    )
                }
                if (state.cellular.sinr5g != "-" && state.cellular.sinr5g.isNotBlank()) {
                    val (qs5, ps5) = sinrQuality(state.cellular.sinr5g)
                    SignalMeter(
                        label = "5G SINR",
                        valueText = "${state.cellular.sinr5g} dB",
                        progress = ps5,
                        quality = qs5
                    )
                }
                val (q4, p4) = rsrpQuality(state.cellular.rsrp4g)
                SignalMeter(
                    label = "4G RSRP",
                    valueText = if (state.cellular.rsrp4g == "-") "--" else "${state.cellular.rsrp4g} dBm",
                    progress = p4,
                    quality = q4
                )
                val (qs4, ps4) = sinrQuality(state.cellular.sinr4g)
                SignalMeter(
                    label = "4G SINR",
                    valueText = if (state.cellular.sinr4g == "-") "--" else "${state.cellular.sinr4g} dB",
                    progress = ps4,
                    quality = qs4
                )
                FilledTonalButton(
                    onClick = viewModel::openLockEditor,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.signal_manage_locks))
                }
            }
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

private fun rsrpQuality(raw: String): Pair<SignalQualityBand, Float> {
    val v = raw.trim().toFloatOrNull() ?: return SignalQualityBand.UNKNOWN to 0f
    val band = when {
        v >= -85f -> SignalQualityBand.EXCELLENT
        v >= -95f -> SignalQualityBand.GOOD
        v >= -105f -> SignalQualityBand.FAIR
        else -> SignalQualityBand.POOR
    }
    val progress = ((v + 140f) / 80f).coerceIn(0f, 1f)
    return band to progress
}

private fun sinrQuality(raw: String): Pair<SignalQualityBand, Float> {
    val v = raw.trim().toFloatOrNull() ?: return SignalQualityBand.UNKNOWN to 0f
    val band = when {
        v >= 20f -> SignalQualityBand.EXCELLENT
        v >= 13f -> SignalQualityBand.GOOD
        v >= 5f -> SignalQualityBand.FAIR
        else -> SignalQualityBand.POOR
    }
    val progress = ((v + 5f) / 35f).coerceIn(0f, 1f)
    return band to progress
}
