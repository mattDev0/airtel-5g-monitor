@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.security

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

                item(key = "sec_deferred_note") {
                    Surface(
                        shape = AirtelTheme.shapes.tile,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.security_deferred_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
}
