@file:OptIn(
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.feature.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.dto.formatBandPill
import com.airtel.monitor.data.dto.formatCaSummary
import com.airtel.monitor.data.dto.formatCellPill
import com.airtel.monitor.data.dto.formatDataSizeMb
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.ui.components.LockEditorSheet
import com.airtel.monitor.ui.components.MinimalWanSpeedCard
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.designsystem.SignalBars
import com.airtel.monitor.ui.designsystem.StatTile
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

private data class PendingHomeConfirm(
    val title: String,
    val impact: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit
)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    contentPadding: PaddingValues,
    onOpenSignal: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState.routerState ?: RouterState()
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    var pendingConfirm by remember { mutableStateOf<PendingHomeConfirm?>(null) }

    LaunchedEffect(Unit) {
        viewModel.ensureWifiRadiosLoaded()
    }

    val mobileOffTitle = stringResource(R.string.confirm_mobile_data_off_title)
    val mobileOffImpact = stringResource(R.string.confirm_mobile_data_off_impact)
    val wifi24Label = stringResource(R.string.toggle_wifi_24g)
    val wifi5Label = stringResource(R.string.toggle_wifi_5g)
    val wifiOffTitle24 = stringResource(R.string.confirm_wifi_off_title, wifi24Label)
    val wifiOffImpact24 = stringResource(R.string.confirm_wifi_off_impact, wifi24Label)
    val wifiOffTitle5 = stringResource(R.string.confirm_wifi_off_title, wifi5Label)
    val wifiOffImpact5 = stringResource(R.string.confirm_wifi_off_impact, wifi5Label)
    val flightTitle = stringResource(R.string.confirm_flight_mode_on_title)
    val flightImpact = stringResource(R.string.confirm_flight_mode_on_impact)
    val turnOffLabel = stringResource(R.string.confirm_turn_off)
    val enableLabel = stringResource(R.string.confirm_enable)
    val usageHint = stringResource(R.string.home_usage_tap_hint)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        item(key = "connection_hero") {
            ConnectionHeroCard(
                state = state,
                onOpenSignal = onOpenSignal,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            )
        }

        item(key = "live_throughput") {
            MinimalWanSpeedCard(
                wan = state.wan,
                history = state.history,
                sparklineLabel = stringResource(R.string.home_throughput_sparkline_desc),
                sparklineLegend = stringResource(R.string.home_speed_dl_ul)
            )
        }

        item(key = "data_usage") {
            MonthlyDataUsageCard(
                state = state,
                onClick = { viewModel.emitFeedback(usageHint) }
            )
        }

        item(key = "quick_toggles") {
            val mobileOn = state.cellLock.mobileDataOn ?: true
            val flightOn = state.cellLock.flightModeOn ?: false
            val wifi24On = uiState.wifiRadios?.enabled24g ?: true
            val wifi5On = uiState.wifiRadios?.enabled5g ?: true

            SectionCard(title = stringResource(R.string.home_toggles_title)) {
                SettingSwitchRow(
                    title = stringResource(R.string.toggle_mobile_data),
                    checked = mobileOn,
                    enabled = !uiState.networkActionBusy,
                    leadingIcon = Icons.Rounded.CellTower,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            viewModel.setMobileData(true)
                        } else {
                            pendingConfirm = PendingHomeConfirm(
                                title = mobileOffTitle,
                                impact = mobileOffImpact,
                                confirmLabel = turnOffLabel,
                                onConfirm = { viewModel.setMobileData(false) }
                            )
                        }
                    }
                )
                SettingSwitchRow(
                    title = wifi24Label,
                    subtitle = uiState.wifiRadios?.ssid24g?.takeIf { it.isNotBlank() },
                    checked = wifi24On,
                    enabled = !uiState.networkActionBusy,
                    leadingIcon = Icons.Rounded.Wifi,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            viewModel.setWifi24g(true)
                        } else {
                            pendingConfirm = PendingHomeConfirm(
                                title = wifiOffTitle24,
                                impact = wifiOffImpact24,
                                confirmLabel = turnOffLabel,
                                onConfirm = { viewModel.setWifi24g(false) }
                            )
                        }
                    }
                )
                SettingSwitchRow(
                    title = wifi5Label,
                    subtitle = uiState.wifiRadios?.ssid5g?.takeIf { it.isNotBlank() },
                    checked = wifi5On,
                    enabled = !uiState.networkActionBusy,
                    leadingIcon = Icons.Rounded.Wifi,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            viewModel.setWifi5g(true)
                        } else {
                            pendingConfirm = PendingHomeConfirm(
                                title = wifiOffTitle5,
                                impact = wifiOffImpact5,
                                confirmLabel = turnOffLabel,
                                onConfirm = { viewModel.setWifi5g(false) }
                            )
                        }
                    }
                )
                SettingSwitchRow(
                    title = stringResource(R.string.toggle_flight_mode),
                    checked = flightOn,
                    enabled = !uiState.networkActionBusy,
                    leadingIcon = Icons.Rounded.AirplanemodeActive,
                    onCheckedChange = { wantOn ->
                        if (!wantOn) {
                            viewModel.setFlightMode(false)
                        } else {
                            pendingConfirm = PendingHomeConfirm(
                                title = flightTitle,
                                impact = flightImpact,
                                confirmLabel = enableLabel,
                                onConfirm = { viewModel.setFlightMode(true) }
                            )
                        }
                    }
                )
            }
        }

        item(key = "quick_tiles") {
            SectionCard(title = stringResource(R.string.home_tiles_title)) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        StatTile(
                            label = stringResource(R.string.tile_devices_online),
                            value = state.devices.size.toString(),
                            onClick = onOpenDevices,
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = stringResource(R.string.tile_messages),
                            value = if (uiState.smsUnreadCount > 0) {
                                stringResource(R.string.tile_messages_unread_fmt, uiState.smsUnreadCount)
                            } else {
                                stringResource(R.string.tile_messages_none)
                            },
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        val tempText = state.hardware.temperature.takeIf { it != "--" }?.let { "$it°C" } ?: "--"
                        StatTile(
                            label = stringResource(R.string.tile_temp_cpu),
                            value = "$tempText · ${state.hardware.cpuUsage.toInt()}%",
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = stringResource(R.string.tile_uptime),
                            value = state.uptimeText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    pendingConfirm?.let { confirm ->
        ConfirmSheet(
            title = confirm.title,
            impactMessage = confirm.impact,
            confirmLabel = confirm.confirmLabel,
            onConfirm = {
                pendingConfirm = null
                confirm.onConfirm()
            },
            onDismiss = { pendingConfirm = null },
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
private fun ConnectionHeroCard(
    state: RouterState,
    onOpenSignal: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?
) {
    val spacing = AirtelTheme.spacing
    val caSummary = formatCaSummary(state.carriers)
    val bandPill = formatBandPill(state.carriers)
    val cellPill = formatCellPill(state.carriers)

    val pillBaseModifier = Modifier
        .clip(AirtelTheme.shapes.chip)
        .clickable(onClick = onOpenSignal)
    val sharedPillModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            pillBaseModifier.sharedElement(
                sharedContentState = rememberSharedContentState(key = "carrier_summary_pill"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        pillBaseModifier
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = AirtelTheme.shapes.chip,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = state.cellular.networkType.ifEmpty { "--" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)
                    )
                }
                Text(
                    text = state.cellular.operator,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            SignalBars(level = state.cellular.signalLvl, maxBars = 5)
        }

        Surface(
            modifier = sharedPillModifier,
            shape = AirtelTheme.shapes.chip,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = if (caSummary != "-") caSummary else "$bandPill · $cellPill",
                style = MaterialTheme.typography.labelMedium.merge(TabularMonospace),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            StatTile(
                label = stringResource(R.string.home_online_duration),
                value = state.onlineDurationText,
                modifier = Modifier.weight(1f, fill = true)
            )
            StatTile(
                label = stringResource(R.string.home_wan_ip),
                value = state.wanIp,
                modifier = Modifier.weight(1f, fill = true)
            )
            StatTile(
                label = stringResource(R.string.home_cell_tile),
                value = cellPill.removePrefix("PCI ").trim(),
                onClick = onOpenSignal,
                modifier = Modifier.weight(1f, fill = true)
            )
        }
    }
}

@Composable
private fun MonthlyDataUsageCard(
    state: RouterState,
    onClick: () -> Unit
) {
    val usage = state.monthlyUsage
    val colors = AirtelTheme.colors
    val usedText = usage.usedMb?.let(::formatDataSizeMb) ?: "--"
    val limitText = usage.limitMb?.let(::formatDataSizeMb)
    val progress = usage.progressFraction
    val hasLimit = usage.limitEnabled && progress != null

    SectionCard(
        title = stringResource(R.string.home_usage_title),
        trailing = if (hasLimit) {
            {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(40.dp),
                        color = if (usage.isOverWarning) colors.warning else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeWidth = 3.5.dp,
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = "${(progress * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.merge(TabularMonospace),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        val primaryLine = if (hasLimit && limitText != null) {
            stringResource(R.string.home_usage_limit_fmt, usedText, limitText)
        } else {
            stringResource(R.string.home_usage_used_fmt, usedText)
        }
        Text(
            text = primaryLine,
            style = MaterialTheme.typography.titleMedium.merge(TabularMonospace),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        val subtitleParts = buildList {
            if (hasLimit && usage.daysLeftInCycle != null) {
                add(stringResource(R.string.home_usage_days_left_fmt, usage.daysLeftInCycle))
            } else if (!hasLimit) {
                add(stringResource(R.string.home_usage_no_limit))
            }
            if (usage.cycleStartDay != null) {
                add(stringResource(R.string.home_usage_cycle_day_fmt, usage.cycleStartDay))
            }
        }
        if (subtitleParts.isNotEmpty() || !hasLimit) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subtitleParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (!hasLimit) {
                    TextButton(
                        onClick = onClick,
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(stringResource(R.string.home_usage_set_limit))
                    }
                }
            }
        }
    }
}
