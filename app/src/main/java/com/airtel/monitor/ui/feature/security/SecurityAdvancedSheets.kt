@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.airtel.monitor.ui.feature.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.data.model.DmzConfig
import com.airtel.monitor.data.model.IpPortFilterRule
import com.airtel.monitor.data.model.PortFilterRule
import com.airtel.monitor.data.model.PortForwardRule
import com.airtel.monitor.data.model.TrustedIpConfig
import com.airtel.monitor.data.model.UrlFilterRule
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
private fun SecuritySheetFrame(
    title: String,
    subtitle: String? = null,
    isBusy: Boolean,
    errorMessage: String?,
    onRefresh: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isBusy) {
                    LoadingIndicator(modifier = Modifier.size(22.dp))
                } else if (onRefresh != null) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_settings)
                        )
                    }
                }
            }

            content()

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                secondaryAction?.invoke()
                TextButton(
                    onClick = onDismiss,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = onPrimary,
                    enabled = primaryEnabled && !isBusy,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

@Composable
private fun DefaultFilterPolicyCard(
    acceptAll: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit
) {
    SectionCard(title = stringResource(R.string.security_default_policy_label)) {
        val options = listOf(
            true to stringResource(R.string.security_default_accept),
            false to stringResource(R.string.security_default_reject)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { idx, (value, label) ->
                SegmentedButton(
                    selected = acceptAll == value,
                    enabled = enabled,
                    onClick = { if (acceptAll != value) onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size)
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RuleEntryRow(
    headline: String,
    detail: String,
    enabled: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = enabled,
            enabled = !busy,
            onCheckedChange = onToggle
        )
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Delete rule",
                tint = colors.error
            )
        }
    }
}

@Composable
fun DmzSheet(
    config: DmzConfig,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (Boolean, String) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var ip by remember(config) { mutableStateOf(config.ip) }
    var pendingConfirm by remember { mutableStateOf(false) }

    SecuritySheetFrame(
        title = stringResource(R.string.security_dmz_sheet_title),
        isBusy = isLoading || isSaving,
        errorMessage = errorMessage,
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.action_save),
        primaryEnabled = !enabled || ip.isNotBlank(),
        onPrimary = { pendingConfirm = true }
    ) {
        Surface(
            shape = AirtelTheme.shapes.tile,
            color = AirtelTheme.colors.warning.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = AirtelTheme.colors.warning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.security_dmz_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        SettingSwitchRow(
            title = stringResource(R.string.security_dmz_enable),
            checked = enabled,
            enabled = !isSaving && !isLoading,
            onCheckedChange = { enabled = it }
        )
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text(stringResource(R.string.security_dmz_ip_label)) },
            placeholder = { Text("192.168.1.100") },
            enabled = enabled && !isSaving,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (pendingConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.security_dmz_sheet_title),
            impactMessage = stringResource(R.string.security_dmz_warn),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingConfirm = false
                onSave(enabled, ip.trim())
            },
            onDismiss = { pendingConfirm = false }
        )
    }
}

@Composable
fun PortForwardSheet(
    rules: List<PortForwardRule>,
    systemPorts: Set<Int>,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    findConflict: (PortForwardRule) -> Int?,
    onSave: (List<PortForwardRule>) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    var draft by remember(rules) { mutableStateOf(rules) }
    var extPort by remember { mutableStateOf("") }
    var intPort by remember { mutableStateOf("") }
    var targetIp by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("TCP") }
    var remark by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<List<PortForwardRule>?>(null) }
    var pendingConflictPort by remember { mutableStateOf<Int?>(null) }

    SecuritySheetFrame(
        title = stringResource(R.string.security_pf_sheet_title),
        subtitle = stringResource(R.string.security_rule_count_fmt, draft.size),
        isBusy = isLoading || isSaving,
        errorMessage = localError ?: errorMessage,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.action_save),
        primaryEnabled = true,
        onPrimary = { pendingSave = draft },
        secondaryAction = {
            if (draft.isNotEmpty()) {
                OutlinedButton(
                    onClick = { pendingSave = emptyList() },
                    enabled = !isSaving
                ) {
                    Text(stringResource(R.string.security_clear_all))
                }
            }
        }
    ) {
        if (draft.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.security_port_forward_row)) {
                draft.forEachIndexed { idx, rule ->
                    RuleEntryRow(
                        headline = "${rule.protocol} :${rule.port} -> ${rule.mappingIp}:${rule.mappingPort}",
                        detail = rule.remark.ifBlank { rule.ifName },
                        enabled = rule.enableRule,
                        busy = isSaving,
                        onToggle = { on ->
                            draft = draft.toMutableList().apply { set(idx, rule.copy(enableRule = on)) }
                        },
                        onDelete = {
                            draft = draft.toMutableList().apply { removeAt(idx) }
                        }
                    )
                    if (idx < draft.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.security_pf_add)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("TCP", "UDP", "TCP&UDP").forEach { proto ->
                    FilterChip(
                        selected = protocol == proto,
                        onClick = { protocol = proto },
                        label = { Text(proto) },
                        enabled = !isSaving
                    )
                }
            }
            OutlinedTextField(
                value = extPort,
                onValueChange = { extPort = it; localError = null },
                label = { Text(stringResource(R.string.security_pf_ext_port)) },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = targetIp,
                onValueChange = { targetIp = it; localError = null },
                label = { Text(stringResource(R.string.security_pf_ip)) },
                placeholder = { Text("192.168.1.50") },
                singleLine = true,
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = intPort,
                onValueChange = { intPort = it; localError = null },
                label = { Text(stringResource(R.string.security_pf_int_port)) },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("Remark (optional)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = !isSaving && extPort.isNotBlank() && intPort.isNotBlank() && targetIp.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    if (draft.size >= 32) {
                        localError = "Maximum 32 rules reached"
                        return@OutlinedButton
                    }
                    val candidate = PortForwardRule(
                        port = extPort.trim().replace('-', ':'),
                        mappingPort = intPort.trim().replace(':', '-'),
                        mappingIp = targetIp.trim(),
                        protocol = protocol,
                        remark = remark.trim(),
                        enableRule = true
                    )
                    val conflict = findConflict(candidate)
                    if (conflict != null) {
                        pendingConflictPort = conflict
                    } else {
                        draft = draft + candidate
                        extPort = ""
                        intPort = ""
                        targetIp = ""
                        remark = ""
                        localError = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.security_pf_add))
            }
        }
    }

    pendingConflictPort?.let { conflict ->
        ConfirmSheet(
            title = stringResource(R.string.security_pf_sys_warn_title),
            impactMessage = stringResource(
                R.string.security_pf_sys_warn_body_fmt,
                conflict,
                systemPorts.sorted().joinToString(" ")
            ),
            confirmLabel = stringResource(R.string.action_continue),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingConflictPort = null
                val candidate = PortForwardRule(
                    port = extPort.trim().replace('-', ':'),
                    mappingPort = intPort.trim().replace(':', '-'),
                    mappingIp = targetIp.trim(),
                    protocol = protocol,
                    remark = remark.trim(),
                    enableRule = true
                )
                draft = draft + candidate
                extPort = ""
                intPort = ""
                targetIp = ""
                remark = ""
                localError = null
            },
            onDismiss = { pendingConflictPort = null }
        )
    }

    pendingSave?.let { targetList ->
        ConfirmSheet(
            title = stringResource(R.string.security_pf_confirm_title),
            impactMessage = stringResource(R.string.security_pf_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingSave = null
                onSave(targetList)
            },
            onDismiss = { pendingSave = null }
        )
    }
}

@Composable
fun IpPortFilterSheet(
    rules: List<IpPortFilterRule>,
    acceptAll: Boolean,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSaveRules: (List<IpPortFilterRule>) -> Unit,
    onSaveDefaultPolicy: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    var draft by remember(rules) { mutableStateOf(rules) }
    var ippro by remember { mutableStateOf("IPV4") }
    var protocol by remember { mutableStateOf("TCP") }
    var srcIp by remember { mutableStateOf("") }
    var dstIp by remember { mutableStateOf("") }
    var srcPort by remember { mutableStateOf("") }
    var dstPort by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<List<IpPortFilterRule>?>(null) }
    var pendingPolicy by remember { mutableStateOf<Boolean?>(null) }

    val portsDisabled = protocol == "ICMP" || protocol == "All"

    SecuritySheetFrame(
        title = stringResource(R.string.security_ipf_sheet_title),
        subtitle = stringResource(R.string.security_rule_count_fmt, draft.size),
        isBusy = isLoading || isSaving,
        errorMessage = localError ?: errorMessage,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.action_save),
        primaryEnabled = true,
        onPrimary = { pendingSave = draft },
        secondaryAction = {
            if (draft.isNotEmpty()) {
                OutlinedButton(
                    onClick = { pendingSave = emptyList() },
                    enabled = !isSaving
                ) {
                    Text(stringResource(R.string.security_clear_all))
                }
            }
        }
    ) {
        DefaultFilterPolicyCard(
            acceptAll = acceptAll,
            enabled = !isSaving && !isLoading,
            onSelect = { pendingPolicy = it }
        )
        if (draft.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.security_ip_port_filter_row)) {
                draft.forEachIndexed { idx, rule ->
                    RuleEntryRow(
                        headline = "${rule.ippro} ${rule.protocol} ${rule.ip.ifBlank { "*" }} -> ${rule.destIp.ifBlank { "*" }}",
                        detail = listOfNotNull(
                            rule.srcPort.takeIf { it.isNotBlank() }?.let { "src:$it" },
                            rule.destPort.takeIf { it.isNotBlank() }?.let { "dst:$it" },
                            rule.remark.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        enabled = rule.enableRule,
                        busy = isSaving,
                        onToggle = { on -> draft = draft.toMutableList().apply { set(idx, rule.copy(enableRule = on)) } },
                        onDelete = { draft = draft.toMutableList().apply { removeAt(idx) } }
                    )
                    if (idx < draft.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
        SectionCard(title = "Add Rule") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("IPV4", "IPV6").forEach { fam ->
                    FilterChip(selected = ippro == fam, onClick = { ippro = fam }, label = { Text(fam) }, enabled = !isSaving)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("TCP", "UDP", "ICMP", "All").forEach { proto ->
                    FilterChip(
                        selected = protocol == proto,
                        onClick = {
                            protocol = proto
                            if (proto == "ICMP" || proto == "All") {
                                srcPort = ""
                                dstPort = ""
                            }
                        },
                        label = { Text(proto) },
                        enabled = !isSaving
                    )
                }
            }
            OutlinedTextField(
                value = srcIp,
                onValueChange = { srcIp = it; localError = null },
                label = { Text("Source IP or a-b range (optional)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = dstIp,
                onValueChange = { dstIp = it; localError = null },
                label = { Text("Destination IP or a-b range (optional)") },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            )
            if (!portsDisabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    OutlinedTextField(
                        value = srcPort,
                        onValueChange = { srcPort = it; localError = null },
                        label = { Text("Src Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dstPort,
                        onValueChange = { dstPort = it; localError = null },
                        label = { Text("Dst Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("Remark (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = !isSaving,
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    if (draft.size >= 32) {
                        localError = "Maximum 32 rules reached"
                        return@OutlinedButton
                    }
                    draft = draft + IpPortFilterRule(
                        ip = srcIp.trim(),
                        destIp = dstIp.trim(),
                        srcPort = if (portsDisabled) "" else srcPort.trim(),
                        destPort = if (portsDisabled) "" else dstPort.trim(),
                        protocol = protocol,
                        ippro = ippro,
                        remark = remark.trim(),
                        enableRule = true,
                        enableLink = !acceptAll
                    )
                    srcIp = ""
                    dstIp = ""
                    srcPort = ""
                    dstPort = ""
                    remark = ""
                    localError = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(spacing.xs))
                Text("Add Rule")
            }
        }
    }

    pendingSave?.let { targetList ->
        ConfirmSheet(
            title = stringResource(R.string.security_ipf_sheet_title),
            impactMessage = stringResource(R.string.security_pf_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = { pendingSave = null; onSaveRules(targetList) },
            onDismiss = { pendingSave = null }
        )
    }

    pendingPolicy?.let { nextPolicy ->
        ConfirmSheet(
            title = stringResource(R.string.security_default_policy_label),
            impactMessage = stringResource(R.string.security_pf_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = { pendingPolicy = null; onSaveDefaultPolicy(nextPolicy) },
            onDismiss = { pendingPolicy = null }
        )
    }
}

@Composable
fun PortFilterSheet(
    rules: List<PortFilterRule>,
    acceptAll: Boolean,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSaveRules: (List<PortFilterRule>) -> Unit,
    onSaveDefaultPolicy: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    var draft by remember(rules) { mutableStateOf(rules) }
    var ippro by remember { mutableStateOf("IPV4") }
    var protocol by remember { mutableStateOf("tcp") }
    var port by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<List<PortFilterRule>?>(null) }
    var pendingPolicy by remember { mutableStateOf<Boolean?>(null) }

    SecuritySheetFrame(
        title = stringResource(R.string.security_pfw_sheet_title),
        subtitle = stringResource(R.string.security_rule_count_fmt, draft.size),
        isBusy = isLoading || isSaving,
        errorMessage = localError ?: errorMessage,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.action_save),
        primaryEnabled = true,
        onPrimary = { pendingSave = draft },
        secondaryAction = {
            if (draft.isNotEmpty()) {
                OutlinedButton(onClick = { pendingSave = emptyList() }, enabled = !isSaving) {
                    Text(stringResource(R.string.security_clear_all))
                }
            }
        }
    ) {
        DefaultFilterPolicyCard(
            acceptAll = acceptAll,
            enabled = !isSaving && !isLoading,
            onSelect = { pendingPolicy = it }
        )
        if (draft.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.security_port_filter_row)) {
                draft.forEachIndexed { idx, rule ->
                    RuleEntryRow(
                        headline = "${rule.ippro} ${rule.protocol} :${rule.port}",
                        detail = rule.remark,
                        enabled = rule.enableRule,
                        busy = isSaving,
                        onToggle = { on -> draft = draft.toMutableList().apply { set(idx, rule.copy(enableRule = on)) } },
                        onDelete = { draft = draft.toMutableList().apply { removeAt(idx) } }
                    )
                    if (idx < draft.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
        SectionCard(title = "Add Rule") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("IPV4", "IPV6").forEach { fam ->
                    FilterChip(selected = ippro == fam, onClick = { ippro = fam }, label = { Text(fam) }, enabled = !isSaving)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("tcp", "udp", "All").forEach { proto ->
                    FilterChip(
                        selected = protocol == proto,
                        onClick = { protocol = proto },
                        label = { Text(proto.uppercase(java.util.Locale.US)) },
                        enabled = !isSaving
                    )
                }
            }
            OutlinedTextField(
                value = port,
                onValueChange = { port = it; localError = null },
                label = { Text("Port (1–65535)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("Remark (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = !isSaving && port.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    if (draft.size >= 32) {
                        localError = "Maximum 32 rules reached"
                        return@OutlinedButton
                    }
                    draft = draft + PortFilterRule(
                        port = port.trim(),
                        protocol = protocol,
                        ippro = ippro,
                        remark = remark.trim(),
                        enableRule = true,
                        enableLink = !acceptAll
                    )
                    port = ""
                    remark = ""
                    localError = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(spacing.xs))
                Text("Add Rule")
            }
        }
    }

    pendingSave?.let { targetList ->
        ConfirmSheet(
            title = stringResource(R.string.security_pfw_sheet_title),
            impactMessage = stringResource(R.string.security_pf_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = { pendingSave = null; onSaveRules(targetList) },
            onDismiss = { pendingSave = null }
        )
    }

    pendingPolicy?.let { nextPolicy ->
        ConfirmSheet(
            title = stringResource(R.string.security_default_policy_label),
            impactMessage = stringResource(R.string.security_pf_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = { pendingPolicy = null; onSaveDefaultPolicy(nextPolicy) },
            onDismiss = { pendingPolicy = null }
        )
    }
}

@Composable
fun UrlFilterSheet(
    rules: List<UrlFilterRule>,
    acceptAll: Boolean,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSaveRules: (List<UrlFilterRule>) -> Unit,
    onSaveDefaultPolicy: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    var draft by remember(rules) { mutableStateOf(rules) }
    var ippro by remember { mutableStateOf("IPV4") }
    var urlInput by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<List<UrlFilterRule>?>(null) }
    var pendingPolicy by remember { mutableStateOf<Boolean?>(null) }

    SecuritySheetFrame(
        title = stringResource(R.string.security_urlf_sheet_title),
        subtitle = stringResource(R.string.security_rule_count_fmt, draft.size),
        isBusy = isLoading || isSaving,
        errorMessage = localError ?: errorMessage,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.action_save),
        primaryEnabled = true,
        onPrimary = { pendingSave = draft },
        secondaryAction = {
            if (draft.isNotEmpty()) {
                OutlinedButton(onClick = { pendingSave = emptyList() }, enabled = !isSaving) {
                    Text(stringResource(R.string.security_clear_all))
                }
            }
        }
    ) {
        DefaultFilterPolicyCard(
            acceptAll = acceptAll,
            enabled = !isSaving && !isLoading,
            onSelect = { pendingPolicy = it }
        )
        if (draft.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.security_url_filter_row)) {
                draft.forEachIndexed { idx, rule ->
                    RuleEntryRow(
                        headline = "${rule.ippro} ${rule.url}",
                        detail = rule.remark,
                        enabled = rule.enableRule,
                        busy = isSaving,
                        onToggle = { on -> draft = draft.toMutableList().apply { set(idx, rule.copy(enableRule = on)) } },
                        onDelete = { draft = draft.toMutableList().apply { removeAt(idx) } }
                    )
                    if (idx < draft.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
        SectionCard(title = "Add URL Rule") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                listOf("IPV4", "IPV6").forEach { fam ->
                    FilterChip(selected = ippro == fam, onClick = { ippro = fam }, label = { Text(fam) }, enabled = !isSaving)
                }
            }
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it; localError = null },
                label = { Text("Domain or URL (e.g. example.com)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = remark,
                onValueChange = { remark = it },
                label = { Text("Remark (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = !isSaving && urlInput.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
                onClick = {
                    if (draft.size >= 32) {
                        localError = "Maximum 32 rules reached"
                        return@OutlinedButton
                    }
                    draft = draft + UrlFilterRule(
                        url = urlInput.trim(),
                        ippro = ippro,
                        remark = remark.trim(),
                        enableRule = true,
                        enableLink = !acceptAll
                    )
                    urlInput = ""
                    remark = ""
                    localError = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(spacing.xs))
                Text("Add URL Rule")
            }
        }
    }

    pendingSave?.let { targetList ->
        ConfirmSheet(
            title = stringResource(R.string.security_urlf_sheet_title),
            impactMessage = stringResource(R.string.security_pf_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = { pendingSave = null; onSaveRules(targetList) },
            onDismiss = { pendingSave = null }
        )
    }

    pendingPolicy?.let { nextPolicy ->
        ConfirmSheet(
            title = stringResource(R.string.security_default_policy_label),
            impactMessage = stringResource(R.string.security_pf_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = { pendingPolicy = null; onSaveDefaultPolicy(nextPolicy) },
            onDismiss = { pendingPolicy = null }
        )
    }
}

@Composable
fun TrustedIpSheet(
    config: TrustedIpConfig,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSave: (TrustedIpConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    var enabled by remember(config) { mutableStateOf(config.enabled) }
    var ips by remember(config) { mutableStateOf(config.ips) }
    var ipInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<TrustedIpConfig?>(null) }

    SecuritySheetFrame(
        title = stringResource(R.string.security_trusted_sheet_title),
        subtitle = "${ips.size} / 15 addresses",
        isBusy = isLoading || isSaving,
        errorMessage = localError ?: errorMessage,
        onRefresh = onRefresh,
        onDismiss = onDismiss,
        primaryLabel = stringResource(R.string.action_save),
        primaryEnabled = true,
        onPrimary = {
            val next = TrustedIpConfig(enabled = enabled, ips = ips)
            if (enabled) {
                pendingConfirm = next
            } else {
                onSave(next)
            }
        }
    ) {
        Surface(
            shape = AirtelTheme.shapes.tile,
            color = colors.warning.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.security_trusted_warn),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        SettingSwitchRow(
            title = stringResource(R.string.security_trusted_enable),
            checked = enabled,
            enabled = !isSaving && !isLoading,
            onCheckedChange = { enabled = it }
        )

        if (ips.isNotEmpty()) {
            SectionCard(title = stringResource(R.string.security_trusted_ip_row)) {
                ips.forEachIndexed { idx, addr ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = addr,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { ips = ips.toMutableList().apply { removeAt(idx) } },
                            enabled = !isSaving
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove IP",
                                tint = colors.error
                            )
                        }
                    }
                    if (idx < ips.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ipInput,
                onValueChange = { ipInput = it; localError = null },
                label = { Text(stringResource(R.string.security_trusted_add_hint)) },
                singleLine = true,
                enabled = !isSaving && ips.size < 15,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                enabled = !isSaving && ipInput.isNotBlank() && ips.size < 15,
                onClick = {
                    val clean = ipInput.trim()
                    if (clean in ips) {
                        localError = "Address already added"
                        return@OutlinedButton
                    }
                    ips = ips + clean
                    ipInput = ""
                    localError = null
                }
            ) {
                Text("Add")
            }
        }
    }

    pendingConfirm?.let { next ->
        ConfirmSheet(
            title = stringResource(R.string.security_trusted_confirm_title),
            impactMessage = stringResource(R.string.security_trusted_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingConfirm = null
                onSave(next)
            },
            onDismiss = { pendingConfirm = null }
        )
    }
}
