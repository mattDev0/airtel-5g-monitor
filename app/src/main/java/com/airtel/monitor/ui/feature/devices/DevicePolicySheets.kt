@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.airtel.monitor.ui.feature.devices

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.core.util.NetworkFormatters
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.data.model.MacFilterRule
import com.airtel.monitor.data.model.MacFilterSnapshot
import com.airtel.monitor.data.model.ParentalControlSnapshot
import com.airtel.monitor.data.model.ParentalSpeedRule
import com.airtel.monitor.data.model.ParentalTimeRule
import com.airtel.monitor.data.model.ParentalUrlRule
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.StaticDhcpLease
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

@Composable
fun StaticLeasesSheet(
    leases: List<StaticDhcpLease>,
    prefillIp: String = "",
    prefillMac: String = "",
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSave: (List<StaticDhcpLease>) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    var draft by remember(leases) { mutableStateOf(leases) }
    var ipInput by remember(prefillIp) { mutableStateOf(prefillIp) }
    var macInput by remember(prefillMac) { mutableStateOf(prefillMac) }
    var localError by remember { mutableStateOf<String?>(null) }

    val ipError = ipInput.trim().let {
        when {
            it.isEmpty() -> null
            !NetworkFormatters.isIpv4(it) || it == "0.0.0.0" || it == "255.255.255.255" ->
                "Enter a valid IPv4 address"
            else -> null
        }
    }
    val macError = if (macInput.isBlank()) null else Phase71Validators.validateUnicastMac(macInput)
    val canAdd = ipInput.isNotBlank() && macInput.isNotBlank() &&
        ipError == null && macError == null && draft.size < 20 && !isSaving

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
                .padding(horizontal = spacing.xl, vertical = spacing.sm)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            SheetHeader(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.static_leases_title),
                subtitle = stringResource(R.string.static_leases_subtitle),
                isBusy = isLoading || isSaving,
                onRefresh = onRefresh
            )

            SectionCard {
                if (draft.isEmpty()) {
                    Text(
                        text = stringResource(R.string.static_leases_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    draft.forEachIndexed { idx, lease ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = lease.ip,
                                    style = MaterialTheme.typography.bodyMedium.merge(TabularMonospace),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = lease.mac,
                                    style = MaterialTheme.typography.bodySmall.merge(TabularMonospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                enabled = !isSaving,
                                onClick = {
                                    draft = draft.toMutableList().apply { removeAt(idx) }
                                    localError = null
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete reservation",
                                    tint = colors.error
                                )
                            }
                        }
                        if (idx < draft.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.static_leases_add_btn)) {
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it; localError = null },
                    label = { Text(stringResource(R.string.static_leases_ip_label)) },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = ipError != null,
                    supportingText = ipError?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = macInput,
                    onValueChange = { macInput = it; localError = null },
                    label = { Text(stringResource(R.string.static_leases_mac_label)) },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = macError != null,
                    supportingText = macError?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    enabled = canAdd,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        val cleanIp = ipInput.trim()
                        val normMac = Phase71Validators.normalizeMac(macInput) ?: return@OutlinedButton
                        if (draft.any { it.ip == cleanIp || it.mac.equals(normMac, ignoreCase = true) }) {
                            localError = "IP or MAC is already in the reservation table"
                            return@OutlinedButton
                        }
                        draft = draft + StaticDhcpLease(ip = cleanIp, mac = normMac)
                        ipInput = ""
                        macInput = ""
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(spacing.xs))
                    Text(stringResource(R.string.static_leases_add_btn))
                }
            }

            val shownError = localError ?: errorMessage
            if (!shownError.isNullOrBlank()) {
                Text(
                    text = shownError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled = !isSaving && !isLoading,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
fun MacFilterSheet(
    snapshot: MacFilterSnapshot,
    prefillMac: String = "",
    prefillRemark: String = "",
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSaveRules: (List<MacFilterRule>) -> Unit,
    onSaveDefaultPolicy: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    var draftRules by remember(snapshot.rules) { mutableStateOf(snapshot.rules) }
    var macInput by remember(prefillMac) { mutableStateOf(prefillMac) }
    var remarkInput by remember(prefillRemark) { mutableStateOf(prefillRemark) }
    var ipproInput by remember { mutableStateOf("IPV4") }
    var pendingPolicyConfirm by remember { mutableStateOf<Boolean?>(null) }
    var pendingRulesConfirm by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val macError = if (macInput.isBlank()) null else Phase71Validators.validateUnicastMac(macInput)
    val canAdd = macInput.isNotBlank() && macError == null && draftRules.size < 32 && !isSaving

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
                .padding(horizontal = spacing.xl, vertical = spacing.sm)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            SheetHeader(
                icon = Icons.Rounded.Block,
                title = stringResource(R.string.mac_filter_title),
                subtitle = stringResource(R.string.mac_filter_subtitle),
                isBusy = isLoading || isSaving,
                onRefresh = onRefresh
            )

            SectionCard(title = stringResource(R.string.mac_filter_policy_label)) {
                val options = listOf(
                    true to stringResource(R.string.mac_filter_policy_blacklist),
                    false to stringResource(R.string.mac_filter_policy_whitelist)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { idx, (acceptAll, label) ->
                        SegmentedButton(
                            selected = snapshot.acceptAllIpv4 == acceptAll,
                            enabled = !isSaving && !isLoading,
                            onClick = {
                                if (snapshot.acceptAllIpv4 != acceptAll) {
                                    pendingPolicyConfirm = acceptAll
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(idx, options.size)
                        ) {
                            Text(
                                text = if (acceptAll) "Blacklist" else "Whitelist",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                Text(
                    text = if (snapshot.acceptAllIpv4) {
                        stringResource(R.string.mac_filter_policy_blacklist)
                    } else {
                        stringResource(R.string.mac_filter_policy_whitelist)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard {
                if (draftRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mac_filter_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    draftRules.forEachIndexed { idx, rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rule.mac,
                                    style = MaterialTheme.typography.bodyMedium.merge(TabularMonospace),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val sub = buildList {
                                    if (rule.remark.isNotBlank()) add(rule.remark)
                                    add(rule.ippro)
                                    add(if (snapshot.acceptAllIpv4) "Blocked" else "Allowed")
                                }.joinToString(" · ")
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = rule.enableRule,
                                enabled = !isSaving,
                                onCheckedChange = { checked ->
                                    draftRules = draftRules.toMutableList().apply {
                                        this[idx] = rule.copy(enableRule = checked)
                                    }
                                }
                            )
                            IconButton(
                                enabled = !isSaving,
                                onClick = {
                                    draftRules = draftRules.toMutableList().apply { removeAt(idx) }
                                    localError = null
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete rule",
                                    tint = colors.error
                                )
                            }
                        }
                        if (idx < draftRules.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.mac_filter_add_btn)) {
                OutlinedTextField(
                    value = macInput,
                    onValueChange = { macInput = it; localError = null },
                    label = { Text(stringResource(R.string.mac_filter_mac_label)) },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    enabled = !isSaving,
                    isError = macError != null,
                    supportingText = macError?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = remarkInput,
                    onValueChange = { remarkInput = it.take(32) },
                    label = { Text(stringResource(R.string.mac_filter_remark_label)) },
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    listOf("IPV4", "IPV6").forEach { proto ->
                        FilterChip(
                            selected = ipproInput == proto,
                            onClick = { ipproInput = proto },
                            label = { Text(proto) }
                        )
                    }
                }
                OutlinedButton(
                    enabled = canAdd,
                    shapes = ButtonDefaults.shapes(),
                    onClick = {
                        val norm = Phase71Validators.normalizeMac(macInput) ?: return@OutlinedButton
                        if (draftRules.any { it.mac.equals(norm, ignoreCase = true) && it.ippro == ipproInput }) {
                            localError = "Rule for $norm ($ipproInput) already exists"
                            return@OutlinedButton
                        }
                        draftRules = draftRules + MacFilterRule(
                            mac = norm,
                            remark = remarkInput.trim().take(32),
                            ippro = ipproInput,
                            enableRule = true,
                            enableLink = !snapshot.acceptAllIpv4
                        )
                        macInput = ""
                        remarkInput = ""
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(spacing.xs))
                    Text(stringResource(R.string.mac_filter_add_btn))
                }
            }

            val shownError = localError ?: errorMessage
            if (!shownError.isNullOrBlank()) {
                Text(
                    text = shownError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { pendingRulesConfirm = true },
                    enabled = !isSaving && !isLoading,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    pendingPolicyConfirm?.let { nextAcceptAll ->
        ConfirmSheet(
            title = stringResource(R.string.mac_filter_policy_confirm_title),
            impactMessage = stringResource(R.string.mac_filter_policy_confirm_impact),
            confirmLabel = stringResource(R.string.action_continue),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingPolicyConfirm = null
                onSaveDefaultPolicy(nextAcceptAll)
            },
            onDismiss = { pendingPolicyConfirm = null }
        )
    }

    if (pendingRulesConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.mac_filter_title),
            impactMessage = stringResource(R.string.mac_filter_policy_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingRulesConfirm = false
                onSaveRules(draftRules)
            },
            onDismiss = { pendingRulesConfirm = false }
        )
    }
}

private enum class ParentalTab(val labelRes: Int) {
    SPEED(R.string.parental_tab_speed),
    URL(R.string.parental_tab_url),
    TIME(R.string.parental_tab_time)
}

@Composable
fun ParentalControlSheet(
    snapshot: ParentalControlSnapshot,
    featureFlags: RouterFeatureFlags,
    prefillIp: String = "",
    prefillMac: String = "",
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveSpeedRules: (List<ParentalSpeedRule>) -> Unit,
    onSaveUrlRules: (List<ParentalUrlRule>) -> Unit,
    onSaveTimeRules: (List<ParentalTimeRule>) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    val visibleTabs = remember(featureFlags) {
        buildList {
            if (featureFlags.parentSpeedVisible) add(ParentalTab.SPEED)
            if (featureFlags.parentUrlVisible) add(ParentalTab.URL)
            if (featureFlags.parentTimeVisible) add(ParentalTab.TIME)
        }
    }
    var selectedTabIndex by remember(visibleTabs) { mutableIntStateOf(0) }

    var draftSpeed by remember(snapshot.speedRules) { mutableStateOf(snapshot.speedRules) }
    var draftUrls by remember(snapshot.urlRules) { mutableStateOf(snapshot.urlRules) }
    var draftTimes by remember(snapshot.timeRules) { mutableStateOf(snapshot.timeRules) }

    var speedIp by remember(prefillIp) { mutableStateOf(prefillIp) }
    var speedLimit by remember { mutableStateOf("500") }
    var urlMac by remember(prefillMac) { mutableStateOf(prefillMac) }
    var urlText by remember { mutableStateOf("") }
    var timeMac by remember(prefillMac) { mutableStateOf(prefillMac) }
    var timeStart by remember { mutableStateOf("08:00") }
    var timeEnd by remember { mutableStateOf("20:00") }
    var selectedDays by remember { mutableStateOf(setOf("1", "2", "3", "4", "5", "6", "0")) }
    var localError by remember { mutableStateOf<String?>(null) }

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
                .padding(horizontal = spacing.xl, vertical = spacing.sm)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            SheetHeader(
                icon = Icons.Rounded.AdminPanelSettings,
                title = stringResource(R.string.parental_title),
                subtitle = stringResource(R.string.parental_subtitle),
                isBusy = isLoading || isSaving,
                onRefresh = onRefresh
            )

            SectionCard {
                SettingSwitchRow(
                    title = stringResource(R.string.parental_enable_title),
                    subtitle = stringResource(R.string.parental_enable_desc),
                    checked = snapshot.enabled,
                    enabled = !isSaving && !isLoading,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (visibleTabs.isEmpty()) {
                SectionCard {
                    Text(
                        text = stringResource(R.string.parental_no_tabs_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val safeIndex = selectedTabIndex.coerceIn(0, visibleTabs.lastIndex)
                if (visibleTabs.size > 1) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        visibleTabs.forEachIndexed { idx, tab ->
                            SegmentedButton(
                                selected = safeIndex == idx,
                                onClick = { selectedTabIndex = idx; localError = null },
                                shape = SegmentedButtonDefaults.itemShape(idx, visibleTabs.size)
                            ) {
                                Text(stringResource(tab.labelRes))
                            }
                        }
                    }
                }

                when (visibleTabs[safeIndex]) {
                    ParentalTab.SPEED -> {
                        SectionCard {
                            if (draftSpeed.isEmpty()) {
                                EmptyTabNote()
                            } else {
                                draftSpeed.forEachIndexed { idx, r ->
                                    RuleDeleteRow(
                                        primary = r.ip,
                                        secondary = "${r.maxSpeedKbps} KB/s · ${r.ippro}",
                                        enabled = !isSaving,
                                        onDelete = {
                                            draftSpeed = draftSpeed.toMutableList().apply { removeAt(idx) }
                                        }
                                    )
                                    if (idx < draftSpeed.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                        SectionCard(title = stringResource(R.string.parental_tab_speed)) {
                            val ipErr = if (speedIp.isBlank()) null else Phase71Validators.validateIpOrRange(speedIp, "IPV4")
                            val spdErr = if (speedLimit.isBlank()) null else Phase71Validators.validateParentalSpeed(speedLimit)
                            OutlinedTextField(
                                value = speedIp,
                                onValueChange = { speedIp = it; localError = null },
                                label = { Text(stringResource(R.string.parental_speed_ip_label)) },
                                singleLine = true,
                                enabled = !isSaving,
                                isError = ipErr != null,
                                supportingText = ipErr?.let { msg -> { Text(msg) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = speedLimit,
                                onValueChange = { speedLimit = it; localError = null },
                                label = { Text(stringResource(R.string.parental_speed_max_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !isSaving,
                                isError = spdErr != null,
                                supportingText = spdErr?.let { msg -> { Text(msg) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(
                                enabled = !isSaving && ipErr == null && spdErr == null && speedIp.isNotBlank() && speedLimit.isNotBlank() && draftSpeed.size < 32,
                                shapes = ButtonDefaults.shapes(),
                                onClick = {
                                    val maxKbps = speedLimit.trim().toIntOrNull() ?: return@OutlinedButton
                                    draftSpeed = draftSpeed + ParentalSpeedRule(
                                        ip = speedIp.trim(),
                                        maxSpeedKbps = maxKbps
                                    )
                                    speedIp = ""
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(spacing.xs))
                                Text(stringResource(R.string.mac_filter_add_btn))
                            }
                            SaveCancelRow(
                                isSaving = isSaving,
                                isLoading = isLoading,
                                onDismiss = onDismiss,
                                onSave = { onSaveSpeedRules(draftSpeed) }
                            )
                        }
                    }

                    ParentalTab.URL -> {
                        SectionCard {
                            if (draftUrls.isEmpty()) {
                                EmptyTabNote()
                            } else {
                                draftUrls.forEachIndexed { idx, r ->
                                    RuleDeleteRow(
                                        primary = r.url,
                                        secondary = r.mac,
                                        enabled = !isSaving,
                                        onDelete = {
                                            draftUrls = draftUrls.toMutableList().apply { removeAt(idx) }
                                        }
                                    )
                                    if (idx < draftUrls.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                        SectionCard(title = stringResource(R.string.parental_tab_url)) {
                            val macErr = if (urlMac.isBlank()) null else Phase71Validators.validateUnicastMac(urlMac)
                            val urlErr = if (urlText.isBlank()) null else Phase71Validators.validateFilterUrl(urlText)
                            OutlinedTextField(
                                value = urlMac,
                                onValueChange = { urlMac = it; localError = null },
                                label = { Text(stringResource(R.string.parental_url_mac_label)) },
                                singleLine = true,
                                enabled = !isSaving,
                                isError = macErr != null,
                                supportingText = macErr?.let { msg -> { Text(msg) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = urlText,
                                onValueChange = { urlText = it; localError = null },
                                label = { Text(stringResource(R.string.parental_url_domain_label)) },
                                placeholder = { Text("example.com") },
                                singleLine = true,
                                enabled = !isSaving,
                                isError = urlErr != null,
                                supportingText = urlErr?.let { msg -> { Text(msg) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(
                                enabled = !isSaving && macErr == null && urlErr == null && urlMac.isNotBlank() && urlText.isNotBlank() && draftUrls.size < 32,
                                shapes = ButtonDefaults.shapes(),
                                onClick = {
                                    val norm = Phase71Validators.normalizeMac(urlMac) ?: return@OutlinedButton
                                    if (draftUrls.count { it.mac.equals(norm, ignoreCase = true) } >= 10) {
                                        localError = "Maximum 10 blocked URLs per device ($norm)"
                                        return@OutlinedButton
                                    }
                                    draftUrls = draftUrls + ParentalUrlRule(
                                        mac = norm,
                                        url = urlText.trim()
                                    )
                                    urlText = ""
                                    localError = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(spacing.xs))
                                Text(stringResource(R.string.mac_filter_add_btn))
                            }
                            SaveCancelRow(
                                isSaving = isSaving,
                                isLoading = isLoading,
                                onDismiss = onDismiss,
                                onSave = { onSaveUrlRules(draftUrls) }
                            )
                        }
                    }

                    ParentalTab.TIME -> {
                        SectionCard {
                            if (draftTimes.isEmpty()) {
                                EmptyTabNote()
                            } else {
                                draftTimes.forEachIndexed { idx, r ->
                                    RuleDeleteRow(
                                        primary = "${r.mac} · ${r.startTime}–${r.endTime}",
                                        secondary = "Days: ${formatScheduleDays(r.scheduleDays)}",
                                        enabled = !isSaving,
                                        onDelete = {
                                            draftTimes = draftTimes.toMutableList().apply { removeAt(idx) }
                                        }
                                    )
                                    if (idx < draftTimes.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    }
                                }
                            }
                        }
                        SectionCard(title = stringResource(R.string.parental_tab_time)) {
                            val macErr = if (timeMac.isBlank()) null else Phase71Validators.validateUnicastMac(timeMac)
                            val winErr = if (timeStart.isBlank() || timeEnd.isBlank()) null else {
                                Phase71Validators.validateTimeWindow(timeStart, timeEnd)
                            }
                            OutlinedTextField(
                                value = timeMac,
                                onValueChange = { timeMac = it; localError = null },
                                label = { Text(stringResource(R.string.parental_time_mac_label)) },
                                singleLine = true,
                                enabled = !isSaving,
                                isError = macErr != null,
                                supportingText = macErr?.let { msg -> { Text(msg) } },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                OutlinedTextField(
                                    value = timeStart,
                                    onValueChange = { timeStart = it; localError = null },
                                    label = { Text(stringResource(R.string.parental_time_start_label)) },
                                    singleLine = true,
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = timeEnd,
                                    onValueChange = { timeEnd = it; localError = null },
                                    label = { Text(stringResource(R.string.parental_time_end_label)) },
                                    singleLine = true,
                                    enabled = !isSaving,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (winErr != null) {
                                Text(winErr, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                verticalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                WEEKDAY_CODES.forEach { (code, label) ->
                                    FilterChip(
                                        selected = code in selectedDays,
                                        onClick = {
                                            selectedDays = if (code in selectedDays) {
                                                selectedDays - code
                                            } else {
                                                selectedDays + code
                                            }
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                            OutlinedButton(
                                enabled = !isSaving && macErr == null && winErr == null &&
                                    timeMac.isNotBlank() && selectedDays.isNotEmpty() && draftTimes.size < 32,
                                shapes = ButtonDefaults.shapes(),
                                onClick = {
                                    val norm = Phase71Validators.normalizeMac(timeMac) ?: return@OutlinedButton
                                    val ordered = WEEKDAY_CODES.map { it.first }.filter { it in selectedDays }
                                    draftTimes = draftTimes + ParentalTimeRule(
                                        mac = norm,
                                        startTime = timeStart.trim(),
                                        endTime = timeEnd.trim(),
                                        scheduleDays = ordered.joinToString(",")
                                    )
                                    localError = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(spacing.xs))
                                Text(stringResource(R.string.mac_filter_add_btn))
                            }
                            SaveCancelRow(
                                isSaving = isSaving,
                                isLoading = isLoading,
                                onDismiss = onDismiss,
                                onSave = { onSaveTimeRules(draftTimes) }
                            )
                        }
                    }
                }
            }

            val shownError = localError ?: errorMessage
            if (!shownError.isNullOrBlank()) {
                Text(
                    text = shownError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isBusy: Boolean,
    onRefresh: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = AirtelTheme.shapes.tile,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRefresh, enabled = !isBusy) {
            if (isBusy) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.cd_refresh_settings))
            }
        }
    }
}

@Composable
private fun EmptyTabNote() {
    Text(
        text = stringResource(R.string.parental_empty_tab),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun RuleDeleteRow(
    primary: String,
    secondary: String,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    val colors = AirtelTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = primary,
                style = MaterialTheme.typography.bodyMedium.merge(TabularMonospace),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(enabled = enabled, onClick = onDelete) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = colors.error)
        }
    }
}

@Composable
private fun SaveCancelRow(
    isSaving: Boolean,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        TextButton(
            onClick = onDismiss,
            enabled = !isSaving,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = onSave,
            enabled = !isSaving && !isLoading,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

private val WEEKDAY_CODES = listOf(
    "1" to "Mon",
    "2" to "Tue",
    "3" to "Wed",
    "4" to "Thu",
    "5" to "Fri",
    "6" to "Sat",
    "0" to "Sun"
)

private fun formatScheduleDays(raw: String): String {
    val map = WEEKDAY_CODES.toMap()
    val names = raw.split(',').mapNotNull { map[it.trim()] }
    return if (names.isEmpty()) raw else names.joinToString(", ")
}
