@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.security

import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnLock
import com.airtel.monitor.ui.designsystem.SettingRow
import com.airtel.monitor.ui.feature.devices.MacFilterSheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.model.AlgSettings
import com.airtel.monitor.data.model.FirewallLevel
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.ErrorState
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.designsystem.SkeletonBox
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = AirtelTheme.spacing
    var confirmFirewallDisable by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(
                text = stringResource(R.string.security_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = viewModel::refresh,
                enabled = !uiState.isLoading && !uiState.isSaving,
                modifier = Modifier.size(48.dp)
            ) {
                if (uiState.isLoading || uiState.isSaving) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_settings)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = spacing.lg,
                end = spacing.lg,
                top = spacing.sm,
                bottom = spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            if (uiState.errorMessage != null && !uiState.firewallLoaded && !uiState.algLoaded) {
                item(key = "sec_error") {
                    ErrorState(
                        title = stringResource(R.string.security_title),
                        message = uiState.errorMessage!!,
                        onRetry = viewModel::refresh
                    )
                }
            } else if (uiState.isLoading && !uiState.firewallLoaded && !uiState.algLoaded) {
                item(key = "sec_skeleton_1") {
                    SkeletonBox(modifier = Modifier.fillMaxWidth().size(width = 300.dp, height = 140.dp))
                }
                item(key = "sec_skeleton_2") {
                    SkeletonBox(modifier = Modifier.fillMaxWidth().size(width = 300.dp, height = 220.dp))
                }
            } else {
                item(key = "sec_firewall") {
                    SectionCard(
                        title = stringResource(R.string.security_firewall_title),
                        subtitle = stringResource(R.string.security_firewall_subtitle)
                    ) {
                        if (uiState.firewallRestricted) {
                            Text(
                                text = stringResource(R.string.security_limited_access),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SettingSwitchRow(
                                title = stringResource(R.string.security_firewall_toggle),
                                checked = uiState.firewallEnabled,
                                leadingIcon = Icons.Rounded.Shield,
                                enabled = uiState.firewallLoaded && !uiState.isSaving,
                                onCheckedChange = { enable ->
                                    if (enable) {
                                        viewModel.saveFirewall(true, uiState.firewallLevel)
                                    } else {
                                        confirmFirewallDisable = true
                                    }
                                }
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                                modifier = Modifier.padding(top = spacing.xs)
                            ) {
                                Text(
                                    text = stringResource(R.string.security_firewall_level_label),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val levels = FirewallLevel.entries
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    levels.forEachIndexed { index, level ->
                                        SegmentedButton(
                                            selected = uiState.firewallLevel == level,
                                            enabled = uiState.firewallLoaded && uiState.firewallEnabled && !uiState.isSaving,
                                            onClick = { viewModel.saveFirewall(true, level) },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = levels.size
                                            )
                                        ) {
                                            Text(text = level.label)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "sec_alg") {
                    SectionCard(
                        title = stringResource(R.string.security_alg_title),
                        subtitle = stringResource(R.string.security_alg_subtitle)
                    ) {
                        if (uiState.algRestricted) {
                            Text(
                                text = stringResource(R.string.security_limited_access),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val alg = uiState.alg
                            val rows = listOf(
                                Triple(R.string.security_alg_l2tp, alg.l2tp) { on: Boolean -> alg.copy(l2tp = on) },
                                Triple(R.string.security_alg_ipsec, alg.ipsec) { on: Boolean -> alg.copy(ipsec = on) },
                                Triple(R.string.security_alg_h323, alg.h323) { on: Boolean -> alg.copy(h323 = on) },
                                Triple(R.string.security_alg_rtsp, alg.rtsp) { on: Boolean -> alg.copy(rtsp = on) },
                                Triple(R.string.security_alg_sip, alg.sip) { on: Boolean -> alg.copy(sip = on) },
                                Triple(R.string.security_alg_ftp, alg.ftp) { on: Boolean -> alg.copy(ftp = on) },
                                Triple(R.string.security_alg_pptp, alg.pptp) { on: Boolean -> alg.copy(pptp = on) }
                            )
                            rows.forEachIndexed { idx, (labelRes, checked, transform) ->
                                SettingSwitchRow(
                                    title = stringResource(labelRes),
                                    checked = checked,
                                    enabled = uiState.algLoaded && !uiState.isSaving,
                                    onCheckedChange = { on -> viewModel.saveAlg(transform(on)) }
                                )
                                if (idx < rows.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }

                val flags = uiState.featureFlags
                val hasAdvTools = flags.ddosAvailable || flags.upnpAvailable || flags.dmzAvailable ||
                    flags.portForwardAvailable || flags.firewallRuleAvailable ||
                    flags.urlFilterAvailable || flags.trustedIpAvailable
                if (hasAdvTools) {
                    item(key = "sec_adv_tools") {
                        SectionCard(
                            title = stringResource(R.string.security_adv_title),
                            subtitle = stringResource(R.string.security_adv_subtitle)
                        ) {
                            val itemsToShow = mutableListOf<@Composable () -> Unit>()
                            if (flags.ddosAvailable) {
                                itemsToShow.add {
                                    SettingSwitchRow(
                                        title = stringResource(R.string.security_ddos_toggle),
                                        subtitle = stringResource(R.string.security_ddos_desc),
                                        checked = uiState.ddos.enabled,
                                        leadingIcon = Icons.Rounded.Security,
                                        enabled = !uiState.isSaving,
                                        onCheckedChange = viewModel::saveDdos
                                    )
                                }
                            }
                            if (flags.upnpAvailable) {
                                itemsToShow.add {
                                    SettingSwitchRow(
                                        title = stringResource(R.string.security_upnp_toggle),
                                        subtitle = stringResource(R.string.security_upnp_desc),
                                        checked = uiState.upnp.enabled,
                                        leadingIcon = Icons.Rounded.Router,
                                        enabled = !uiState.isSaving,
                                        onCheckedChange = viewModel::saveUpnp
                                    )
                                }
                            }
                            if (flags.dmzAvailable) {
                                itemsToShow.add {
                                    SettingRow(
                                        title = stringResource(R.string.security_dmz_row),
                                        subtitle = if (uiState.dmz.enabled && uiState.dmz.ip.isNotBlank()) {
                                            stringResource(R.string.security_dmz_desc_fmt, uiState.dmz.ip)
                                        } else {
                                            stringResource(R.string.security_dmz_off)
                                        },
                                        leadingIcon = Icons.Rounded.Public,
                                        onClick = { viewModel.openSheet(ActiveSecuritySheet.DMZ) }
                                    )
                                }
                            }
                            if (flags.portForwardAvailable) {
                                itemsToShow.add {
                                    SettingRow(
                                        title = stringResource(R.string.security_port_forward_row),
                                        subtitle = stringResource(R.string.security_rule_count_fmt, uiState.portForward.size),
                                        leadingIcon = Icons.Rounded.Tune,
                                        onClick = { viewModel.openSheet(ActiveSecuritySheet.PORT_FORWARD) }
                                    )
                                }
                            }
                            if (flags.firewallRuleAvailable) {
                                itemsToShow.add {
                                    SettingRow(
                                        title = stringResource(R.string.security_ip_port_filter_row),
                                        leadingIcon = Icons.Rounded.Shield,
                                        onClick = { viewModel.openSheet(ActiveSecuritySheet.IP_PORT_FILTER) }
                                    )
                                }
                                itemsToShow.add {
                                    SettingRow(
                                        title = stringResource(R.string.security_port_filter_row),
                                        leadingIcon = Icons.Rounded.Shield,
                                        onClick = { viewModel.openSheet(ActiveSecuritySheet.PORT_FILTER) }
                                    )
                                }
                            }
                            if (flags.urlFilterAvailable) {
                                itemsToShow.add {
                                    SettingRow(
                                        title = stringResource(R.string.security_url_filter_row),
                                        leadingIcon = Icons.Rounded.Public,
                                        onClick = { viewModel.openSheet(ActiveSecuritySheet.URL_FILTER) }
                                    )
                                }
                            }
                            if (flags.firewallRuleAvailable) {
                                itemsToShow.add {
                                    SettingRow(
                                        title = stringResource(R.string.security_mac_filter_row),
                                        leadingIcon = Icons.Rounded.Security,
                                        onClick = { viewModel.openSheet(ActiveSecuritySheet.MAC_FILTER) }
                                    )
                                }
                            }
                            if (flags.trustedIpAvailable) {
                                itemsToShow.add {
                                    SettingRow(
                                        title = stringResource(R.string.security_trusted_ip_row),
                                        subtitle = if (uiState.trustedIp.enabled) {
                                            "${uiState.trustedIp.ips.size} allowed"
                                        } else {
                                            stringResource(R.string.security_dmz_off)
                                        },
                                        leadingIcon = Icons.Rounded.VpnLock,
                                        onClick = { viewModel.openSheet(ActiveSecuritySheet.TRUSTED_IP) }
                                    )
                                }
                            }
                            itemsToShow.forEachIndexed { idx, rowContent ->
                                rowContent()
                                if (idx < itemsToShow.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmFirewallDisable) {
        ConfirmSheet(
            title = stringResource(R.string.security_firewall_disable_confirm_title),
            impactMessage = stringResource(R.string.security_firewall_disable_confirm_impact),
            confirmLabel = stringResource(R.string.security_firewall_disable_confirm_btn),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                confirmFirewallDisable = false
                viewModel.saveFirewall(enabled = false, level = uiState.firewallLevel)
            },
            onDismiss = { confirmFirewallDisable = false }
        )
    }

    when (uiState.activeSheet) {
        ActiveSecuritySheet.DMZ -> DmzSheet(
            config = uiState.dmz,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onSave = viewModel::saveDmz,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSecuritySheet.PORT_FORWARD -> PortForwardSheet(
            rules = uiState.portForward,
            systemPorts = uiState.systemPorts,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = { viewModel.refreshSheet(ActiveSecuritySheet.PORT_FORWARD) },
            findConflict = viewModel::findSystemPortConflict,
            onSave = viewModel::savePortForwardRules,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSecuritySheet.IP_PORT_FILTER -> IpPortFilterSheet(
            rules = uiState.ipPortFilter,
            acceptAll = uiState.macFilter.acceptAllIpv4,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = { viewModel.refreshSheet(ActiveSecuritySheet.IP_PORT_FILTER) },
            onSaveRules = viewModel::saveIpPortFilterRules,
            onSaveDefaultPolicy = viewModel::saveDefaultFilterPolicy,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSecuritySheet.PORT_FILTER -> PortFilterSheet(
            rules = uiState.portFilter,
            acceptAll = uiState.macFilter.acceptAllIpv4,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = { viewModel.refreshSheet(ActiveSecuritySheet.PORT_FILTER) },
            onSaveRules = viewModel::savePortFilterRules,
            onSaveDefaultPolicy = viewModel::saveDefaultFilterPolicy,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSecuritySheet.URL_FILTER -> UrlFilterSheet(
            rules = uiState.urlFilter,
            acceptAll = uiState.urlDefaultAcceptAll,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = { viewModel.refreshSheet(ActiveSecuritySheet.URL_FILTER) },
            onSaveRules = viewModel::saveUrlFilterRules,
            onSaveDefaultPolicy = viewModel::saveUrlDefaultPolicy,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSecuritySheet.MAC_FILTER -> MacFilterSheet(
            snapshot = uiState.macFilter,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = { viewModel.refreshSheet(ActiveSecuritySheet.MAC_FILTER) },
            onSaveRules = viewModel::saveMacFilterRules,
            onSaveDefaultPolicy = viewModel::saveDefaultFilterPolicy,
            onDismiss = viewModel::dismissSheet
        )
        ActiveSecuritySheet.TRUSTED_IP -> TrustedIpSheet(
            config = uiState.trustedIp,
            isLoading = uiState.sheetBusy,
            isSaving = uiState.sheetBusy,
            errorMessage = uiState.sheetError,
            onRefresh = { viewModel.refreshSheet(ActiveSecuritySheet.TRUSTED_IP) },
            onSave = viewModel::saveTrustedIps,
            onDismiss = viewModel::dismissSheet
        )
        null -> Unit
    }
}
