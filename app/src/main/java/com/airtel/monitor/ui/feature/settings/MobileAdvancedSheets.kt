@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.data.model.ApnConfigSnapshot
import com.airtel.monitor.data.model.ApnProfile
import com.airtel.monitor.data.model.MobileAdvancedConfig
import com.airtel.monitor.data.model.NetworkModeOption
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

@Composable
internal fun NetworkModeSheet(
    config: MobileAdvancedConfig,
    isLoading: Boolean,
    isSaving: Boolean,
    statusMessage: String?,
    onDismiss: () -> Unit,
    onSelectMode: (String) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val options = remember(config.availableModes) {
        config.availableModes.ifEmpty {
            listOf(
                NetworkModeOption("C", "5G NSA Only"),
                NetworkModeOption("1C", "5G SA+NSA/4G"),
                NetworkModeOption("1E", "Auto")
            )
        }
    }
    var selectedCode by remember(config.networkMode) {
        mutableStateOf(config.networkMode.ifEmpty { options.last().code })
    }
    var pendingConfirm by remember { mutableStateOf<NetworkModeOption?>(null) }

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
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = stringResource(R.string.settings_mobile_mode_label),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (config.plmnLocked) {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.settings_mobile_plmn_locked_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(spacing.sm)
                    )
                }
            }

            if (isLoading) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
            }

            options.forEach { option ->
                val isSelected = selectedCode.equals(option.code, ignoreCase = true)
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AirtelTheme.shapes.tile)
                        .clickable(enabled = !config.plmnLocked && !isSaving) {
                            selectedCode = option.code
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            enabled = !config.plmnLocked && !isSaving,
                            onClick = { selectedCode = option.code }
                        )
                        Spacer(Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = "Mode ${option.code}",
                                style = MaterialTheme.typography.labelSmall.merge(TabularMonospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                val isErr = statusMessage.startsWith("Error:")
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isErr) colors.error else colors.success
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val chosen = options.firstOrNull { it.code.equals(selectedCode, ignoreCase = true) }
                        if (chosen != null) {
                            pendingConfirm = chosen
                        }
                    },
                    enabled = !config.plmnLocked && !isSaving && selectedCode.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    pendingConfirm?.let { chosen ->
        ConfirmSheet(
            tier = ConfirmTier.CONNECTIVITY,
            title = stringResource(R.string.settings_network_mode_confirm_title),
            impactMessage = stringResource(R.string.settings_network_mode_confirm_impact, chosen.label),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = {
                pendingConfirm = null
                onSelectMode(chosen.code)
            },
            onDismiss = { pendingConfirm = null }
        )
    }
}

private sealed interface PendingApnConfirm {
    data class Basic(val type: String, val mtu: Int, val nat: Boolean) : PendingApnConfirm
    data class Profiles(val profiles: List<ApnProfile>) : PendingApnConfirm
    data object Reset : PendingApnConfirm
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApnEditorSheet(
    config: ApnConfigSnapshot,
    isLoading: Boolean,
    isSaving: Boolean,
    statusMessage: String?,
    onDismiss: () -> Unit,
    onSaveBasic: (selectType: String, mtu: Int, natEnabled: Boolean) -> Unit,
    onSaveProfiles: (List<ApnProfile>) -> Unit,
    onResetProfiles: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors

    var selectType by remember(config.selectType) { mutableStateOf(config.selectType) }
    var mtuText by remember(config.mtu) { mutableStateOf(config.mtu.toString()) }
    var natEnabled by remember(config.natEnabled) { mutableStateOf(config.natEnabled) }
    var profiles by remember(config.profiles) { mutableStateOf(config.profiles) }

    var editingIndex by remember { mutableIntStateOf(-2) } // -2 = closed, -1 = add new, >=0 = edit idx
    var editName by remember { mutableStateOf("") }
    var editApn by remember { mutableStateOf("") }
    var editAuth by remember { mutableStateOf("0") }
    var editUser by remember { mutableStateOf("") }
    var editPass by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var pendingConfirm by remember { mutableStateOf<PendingApnConfirm?>(null) }

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
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = stringResource(R.string.settings_apn_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (isLoading) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
            }

            // Section 1: Protocol & MTU (cmd 213 subcmd 3)
            val types = listOf("IP", "IPV6", "IPV4V6")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                types.forEachIndexed { idx, value ->
                    SegmentedButton(
                        selected = selectType == value,
                        onClick = { selectType = value; validationError = null },
                        enabled = !isSaving,
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = types.size)
                    ) {
                        Text(value)
                    }
                }
            }

            OutlinedTextField(
                value = mtuText,
                onValueChange = { mtuText = it.filter { ch -> ch in '0'..'9' }; validationError = null },
                label = {
                    val range = if (selectType == "IP") "576–1500" else "1280–1500"
                    Text("APN MTU ($range)")
                },
                enabled = !isSaving,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = AirtelTheme.shapes.input
            )

            SettingSwitchRow(
                title = "Enable NAT",
                checked = natEnabled,
                enabled = !isSaving,
                onCheckedChange = { natEnabled = it }
            )

            Button(
                onClick = {
                    val err = Phase71Validators.validateApnMtu(mtuText, selectType)
                    if (err != null) {
                        validationError = err
                    } else {
                        pendingConfirm = PendingApnConfirm.Basic(selectType, mtuText.trim().toInt(), natEnabled)
                    }
                },
                enabled = !isSaving,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save IP & MTU")
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Section 2: APN Profiles (cmd 248)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "APN Profiles (${profiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                val editableCount = profiles.count { it.isEditable }
                if (editableCount < 4) {
                    TextButton(
                        onClick = {
                            editingIndex = -1
                            editName = ""
                            editApn = ""
                            editAuth = "0"
                            editUser = ""
                            editPass = ""
                            validationError = null
                        },
                        enabled = !isSaving
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Add APN")
                    }
                }
            }

            profiles.forEachIndexed { idx, profile ->
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = if (profile.isDefault) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                profiles = profiles.mapIndexed { i, p -> p.copy(isDefault = i == idx) }
                            },
                            enabled = !isSaving
                        ) {
                            Icon(
                                imageVector = if (profile.isDefault) {
                                    Icons.Rounded.RadioButtonChecked
                                } else {
                                    Icons.Rounded.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name.ifBlank { profile.apnName },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = buildString {
                                    append(profile.apnName)
                                    if (profile.isDefault) append(" · Default")
                                    if (!profile.isEditable) append(" · Carrier")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (profile.isEditable) {
                            IconButton(
                                onClick = {
                                    editingIndex = idx
                                    editName = profile.name
                                    editApn = profile.apnName
                                    editAuth = profile.selectAuthentication
                                    editUser = profile.username
                                    editPass = profile.password
                                    validationError = null
                                },
                                enabled = !isSaving
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            if (profiles.size > 1) {
                                IconButton(
                                    onClick = {
                                        val remaining = profiles.filterIndexed { i, _ -> i != idx }
                                        profiles = if (remaining.none { it.isDefault } && remaining.isNotEmpty()) {
                                            remaining.mapIndexed { i, p -> p.copy(isDefault = i == 0) }
                                        } else {
                                            remaining
                                        }
                                    },
                                    enabled = !isSaving
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (editingIndex >= -1) {
                Surface(
                    shape = AirtelTheme.shapes.card,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Text(
                            text = if (editingIndex == -1) "New APN Profile" else "Edit APN Profile",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it; validationError = null },
                            label = { Text("Profile Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editApn,
                            onValueChange = { editApn = it; validationError = null },
                            label = { Text("APN") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            listOf("0" to "None", "1" to "PAP", "2" to "CHAP", "3" to "PAP/CHAP").forEach { (code, label) ->
                                FilterChip(
                                    selected = editAuth == code,
                                    onClick = { editAuth = code },
                                    label = { Text(label) }
                                )
                            }
                        }
                        if (editAuth != "0") {
                            OutlinedTextField(
                                value = editUser,
                                onValueChange = { editUser = it; validationError = null },
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = editPass,
                                onValueChange = { editPass = it; validationError = null },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { editingIndex = -2 }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Spacer(Modifier.width(spacing.xs))
                            Button(
                                onClick = {
                                    val err = Phase71Validators.validateApnString(editName, "Profile name")
                                        ?: Phase71Validators.validateApnString(editApn, "APN")
                                        ?: Phase71Validators.validateApnCredential(editUser, "Username")
                                        ?: Phase71Validators.validateApnCredential(editPass, "Password")
                                    if (err != null) {
                                        validationError = err
                                        return@Button
                                    }
                                    val updatedItem = ApnProfile(
                                        name = editName.trim(),
                                        apnName = editApn.trim(),
                                        selectAuthentication = editAuth,
                                        username = if (editAuth == "0") "" else editUser,
                                        password = if (editAuth == "0") "" else editPass,
                                        isDefault = if (editingIndex >= 0) profiles[editingIndex].isDefault else false,
                                        isEditable = true
                                    )
                                    profiles = if (editingIndex == -1) {
                                        profiles + updatedItem
                                    } else {
                                        profiles.mapIndexed { i, p -> if (i == editingIndex) updatedItem else p }
                                    }
                                    editingIndex = -2
                                }
                            ) {
                                Text(stringResource(R.string.action_save))
                            }
                        }
                    }
                }
            }

            val err = validationError ?: statusMessage?.takeIf { it.startsWith("Error:") }
            if (err != null) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                OutlinedButton(
                    onClick = { pendingConfirm = PendingApnConfirm.Reset },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Defaults", modifier = Modifier.padding(start = spacing.xs))
                }
                Button(
                    onClick = { pendingConfirm = PendingApnConfirm.Profiles(profiles) },
                    enabled = !isSaving && profiles.isNotEmpty(),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save Profiles")
                }
            }
        }
    }

    pendingConfirm?.let { confirm ->
        when (confirm) {
            is PendingApnConfirm.Basic -> ConfirmSheet(
                tier = ConfirmTier.CONNECTIVITY,
                title = stringResource(R.string.settings_apn_basic_save_title),
                impactMessage = stringResource(R.string.settings_apn_basic_save_impact),
                confirmLabel = stringResource(R.string.action_save),
                onConfirm = {
                    pendingConfirm = null
                    onSaveBasic(confirm.type, confirm.mtu, confirm.nat)
                },
                onDismiss = { pendingConfirm = null }
            )
            is PendingApnConfirm.Profiles -> ConfirmSheet(
                tier = ConfirmTier.CONNECTIVITY,
                title = stringResource(R.string.settings_apn_profiles_save_title),
                impactMessage = stringResource(R.string.settings_apn_profiles_save_impact),
                confirmLabel = stringResource(R.string.action_save),
                onConfirm = {
                    pendingConfirm = null
                    onSaveProfiles(confirm.profiles)
                },
                onDismiss = { pendingConfirm = null }
            )
            PendingApnConfirm.Reset -> ConfirmSheet(
                tier = ConfirmTier.CONNECTIVITY,
                title = stringResource(R.string.settings_apn_reset_title),
                impactMessage = stringResource(R.string.settings_apn_reset_impact),
                confirmLabel = stringResource(R.string.action_continue),
                onConfirm = {
                    pendingConfirm = null
                    onResetProfiles()
                },
                onDismiss = { pendingConfirm = null }
            )
        }
    }
}
