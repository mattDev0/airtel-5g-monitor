@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.core.util.NetworkFormatters
import com.airtel.monitor.core.util.QrCodeGenerator
import com.airtel.monitor.core.util.WifiAndLanValidators
import com.airtel.monitor.data.model.LanConfig
import com.airtel.monitor.data.model.SimPinState
import com.airtel.monitor.data.model.WifiBandDetail
import com.airtel.monitor.data.model.WifiBandDetails
import com.airtel.monitor.data.repository.WifiRepository.WifiBand
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.QrCode
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

@Composable
fun WifiBandEditorSheet(
    band: WifiBand,
    initialDetail: WifiBandDetail,
    hasWifiSamesSupported: Boolean,
    initialWifiSames: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (
        ssid: String,
        key: String,
        authType: String,
        visibleBroadcast: Boolean,
        wifiSames: Boolean?,
        applySameToBothBands: Boolean
    ) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val preservedWpaType = remember(initialDetail.authenticationType) {
        if (initialDetail.authenticationType == "0") "2" else initialDetail.authenticationType
    }

    var ssidInput by remember(band, initialDetail.ssid) { mutableStateOf(initialDetail.ssid) }
    var authTypeInput by remember(band, initialDetail.authenticationType) {
        mutableStateOf(initialDetail.authenticationType)
    }
    var keyInput by remember(band, initialDetail.key) { mutableStateOf(initialDetail.key) }
    var passwordVisible by remember { mutableStateOf(false) }
    var visibleBroadcast by remember(band, initialDetail.visibleBroadcast) {
        mutableStateOf(initialDetail.visibleBroadcast)
    }
    var syncBothBands by remember(band, initialWifiSames) { mutableStateOf(initialWifiSames) }
    var showConfirm by remember { mutableStateOf(false) }

    val ssidError = WifiAndLanValidators.validateSsid(ssidInput)
    val keyError = WifiAndLanValidators.validateWifiPassword(keyInput, authTypeInput)
    val canSave = ssidError == null && keyError == null && !isSaving

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
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Column {
                    Text(
                        text = if (band == WifiBand.BAND_24G) {
                            stringResource(R.string.settings_wifi_edit_24g)
                        } else {
                            stringResource(R.string.settings_wifi_edit_5g)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_wifi_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = ssidInput,
                onValueChange = { ssidInput = it },
                label = { Text(stringResource(R.string.settings_wifi_ssid_label)) },
                singleLine = true,
                enabled = !isSaving,
                isError = ssidError != null,
                supportingText = if (ssidError != null) {
                    { Text(ssidError) }
                } else null,
                shape = AirtelTheme.shapes.input,
                modifier = Modifier.fillMaxWidth()
            )

            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = stringResource(R.string.settings_wifi_security_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val encryptedLabel = remember(preservedWpaType) {
                    WifiBandDetail(authenticationType = preservedWpaType).securityLabel
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val isWpa = authTypeInput != "0"
                    SegmentedButton(
                        selected = isWpa,
                        onClick = { authTypeInput = preservedWpaType },
                        enabled = !isSaving,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(encryptedLabel)
                    }
                    SegmentedButton(
                        selected = !isWpa,
                        onClick = { authTypeInput = "0" },
                        enabled = !isSaving,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.settings_wifi_security_open))
                    }
                }
            }

            if (authTypeInput != "0") {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text(stringResource(R.string.settings_wifi_password_label)) },
                    singleLine = true,
                    enabled = !isSaving,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_password)
                            )
                        }
                    },
                    isError = keyError != null,
                    supportingText = if (keyError != null) {
                        { Text(keyError) }
                    } else null,
                    shape = AirtelTheme.shapes.input,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Surface(
                shape = AirtelTheme.shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_wifi_broadcast_label),
                        subtitle = stringResource(R.string.settings_wifi_broadcast_desc),
                        checked = visibleBroadcast,
                        onCheckedChange = { visibleBroadcast = it },
                        enabled = !isSaving
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_wifi_same_ssid),
                        subtitle = stringResource(R.string.settings_wifi_same_ssid_desc),
                        checked = syncBothBands,
                        onCheckedChange = { syncBothBands = it },
                        enabled = !isSaving
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !isSaving) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { showConfirm = true },
                    enabled = canSave
                ) {
                    if (isSaving) {
                        LoadingIndicator(
                            modifier = Modifier
                                .padding(end = spacing.sm)
                                .size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (showConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.settings_wifi_save_title),
            impactMessage = stringResource(R.string.settings_wifi_save_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                onSave(
                    ssidInput,
                    keyInput,
                    authTypeInput,
                    visibleBroadcast,
                    if (hasWifiSamesSupported) syncBothBands else null,
                    syncBothBands
                )
            }
        )
    }
}

@Composable
fun WifiQrSheet(
    bandDetails: WifiBandDetails,
    maskSensitive: Boolean,
    onCopyPassword: (label: String, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedBand by remember(bandDetails) {
        mutableStateOf(
            if (bandDetails.band5g.enabled || bandDetails.band5g.ssid.isNotBlank()) {
                WifiBand.BAND_5G
            } else {
                WifiBand.BAND_24G
            }
        )
    }
    var revealPassword by remember(maskSensitive) { mutableStateOf(!maskSensitive) }

    val activeDetail = if (selectedBand == WifiBand.BAND_24G) bandDetails.band24g else bandDetails.band5g
    val payload = remember(activeDetail) {
        QrCodeGenerator.formatWifiQrPayload(
            ssid = activeDetail.ssid.ifBlank { "Airtel-5G" },
            key = activeDetail.key,
            authenticationType = activeDetail.authenticationType,
            hidden = !activeDetail.visibleBroadcast
        )
    }
    val qrMatrix = remember(payload) { QrCodeGenerator.encode(payload) }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val qrForeground = if (isDark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    val qrBackground = if (isDark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerLowest

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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
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
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.settings_wifi_qr_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_wifi_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                WifiBand.entries.forEachIndexed { idx, band ->
                    SegmentedButton(
                        selected = selectedBand == band,
                        onClick = { selectedBand = band },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = WifiBand.entries.size)
                    ) {
                        Text(band.label)
                    }
                }
            }

            QrCode(
                matrix = qrMatrix,
                contentDescriptionText = "${activeDetail.ssid} QR Code",
                foreground = qrForeground,
                background = qrBackground,
                modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth()
            )

            Surface(
                shape = AirtelTheme.shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Text(
                        text = activeDetail.ssid.ifBlank { "--" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Surface(
                            shape = AirtelTheme.shapes.chip,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = if (activeDetail.isOpenNetwork) {
                                    stringResource(R.string.settings_wifi_open_badge)
                                } else {
                                    activeDetail.securityLabel
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (!activeDetail.visibleBroadcast) {
                            Surface(
                                shape = AirtelTheme.shapes.chip,
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_wifi_hidden_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    if (!activeDetail.isOpenNetwork && activeDetail.key.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (revealPassword) activeDetail.key else "•".repeat(activeDetail.key.length.coerceAtMost(12)),
                                style = MaterialTheme.typography.bodyMedium.merge(TabularMonospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { revealPassword = !revealPassword }) {
                                Icon(
                                    imageVector = if (revealPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(R.string.cd_toggle_password)
                                )
                            }
                            OutlinedButton(
                                onClick = { onCopyPassword(selectedBand.label + " Password", activeDetail.key) }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                )
                                Text(stringResource(R.string.settings_wifi_copy_password))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SimPinSheet(
    state: SimPinState,
    isLoading: Boolean,
    isSaving: Boolean,
    statusMessage: String?,
    onSetPinLockEnabled: (enable: Boolean, pin: String) -> Unit,
    onUnlockPin: (pin: String) -> Unit,
    onUnlockPuk: (puk: String, newPin: String) -> Unit,
    onChangePin: (oldPin: String, newPin: String) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var pinInput by remember { mutableStateOf("") }
    var pukInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var showChangeSection by remember { mutableStateOf(false) }
    var pendingConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

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
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.settings_sim_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_sim_attempts_fmt,
                            state.pinAttemptsLeft,
                            state.pukAttemptsLeft
                        ),
                        style = MaterialTheme.typography.bodySmall.merge(TabularMonospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    LoadingIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (!state.simInserted) {
                Text(
                    text = stringResource(R.string.settings_sim_not_inserted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.pukRequired) {
                val pukErr = WifiAndLanValidators.validatePuk(pukInput)
                val newPinErr = WifiAndLanValidators.validatePin(newPinInput)
                val mismatch = confirmPinInput.isNotEmpty() && confirmPinInput != newPinInput

                OutlinedTextField(
                    value = pukInput,
                    onValueChange = { pukInput = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.settings_sim_puk_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = pukInput.isNotEmpty() && pukErr != null,
                    supportingText = if (pukInput.isNotEmpty() && pukErr != null) { { Text(pukErr) } } else null,
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = { newPinInput = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.settings_sim_new_pin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = newPinInput.isNotEmpty() && newPinErr != null,
                    supportingText = if (newPinInput.isNotEmpty() && newPinErr != null) { { Text(newPinErr) } } else null,
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPinInput,
                    onValueChange = { confirmPinInput = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.settings_sim_confirm_pin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.settings_sim_pin_mismatch)) }
                    } else null,
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        pendingConfirmAction = {
                            onUnlockPuk(pukInput, newPinInput)
                            pukInput = ""
                            newPinInput = ""
                            confirmPinInput = ""
                        }
                    },
                    enabled = !isSaving && pukErr == null && newPinErr == null && confirmPinInput == newPinInput,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_sim_unlock_puk_btn))
                }
            } else if (state.pinRequired) {
                val pinErr = WifiAndLanValidators.validatePin(pinInput)
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.settings_sim_current_pin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = pinInput.isNotEmpty() && pinErr != null,
                    supportingText = if (pinInput.isNotEmpty() && pinErr != null) { { Text(pinErr) } } else null,
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        pendingConfirmAction = {
                            onUnlockPin(pinInput)
                            pinInput = ""
                        }
                    },
                    enabled = !isSaving && pinErr == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_sim_unlock_pin_btn))
                }
            } else {
                val pinErr = WifiAndLanValidators.validatePin(pinInput)
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.settings_sim_current_pin)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    isError = pinInput.isNotEmpty() && pinErr != null,
                    supportingText = if (pinInput.isNotEmpty() && pinErr != null) { { Text(pinErr) } } else null,
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val targetEnable = !state.pinLockEnabled
                        pendingConfirmAction = {
                            onSetPinLockEnabled(targetEnable, pinInput)
                            pinInput = ""
                        }
                    },
                    enabled = !isSaving && pinErr == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.pinLockEnabled) {
                            stringResource(R.string.settings_sim_disable_pin_btn)
                        } else {
                            stringResource(R.string.settings_sim_enable_pin_btn)
                        }
                    )
                }

                if (state.pinLockEnabled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    TextButton(
                        onClick = { showChangeSection = !showChangeSection },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(stringResource(R.string.settings_sim_change_pin_btn))
                    }
                    if (showChangeSection) {
                        val newPinErr = WifiAndLanValidators.validatePin(newPinInput)
                        val mismatch = confirmPinInput.isNotEmpty() && confirmPinInput != newPinInput
                        OutlinedTextField(
                            value = newPinInput,
                            onValueChange = { newPinInput = it.filter(Char::isDigit).take(8) },
                            label = { Text(stringResource(R.string.settings_sim_new_pin)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            isError = newPinInput.isNotEmpty() && newPinErr != null,
                            supportingText = if (newPinInput.isNotEmpty() && newPinErr != null) { { Text(newPinErr) } } else null,
                            singleLine = true,
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = confirmPinInput,
                            onValueChange = { confirmPinInput = it.filter(Char::isDigit).take(8) },
                            label = { Text(stringResource(R.string.settings_sim_confirm_pin)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            isError = mismatch,
                            supportingText = if (mismatch) {
                                { Text(stringResource(R.string.settings_sim_pin_mismatch)) }
                            } else null,
                            singleLine = true,
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                pendingConfirmAction = {
                                    onChangePin(pinInput, newPinInput)
                                    pinInput = ""
                                    newPinInput = ""
                                    confirmPinInput = ""
                                }
                            },
                            enabled = !isSaving && pinErr == null && newPinErr == null && confirmPinInput == newPinInput,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_sim_change_pin_btn))
                        }
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                val isOk = statusMessage.contains("unlock", ignoreCase = true) ||
                    statusMessage.contains("updated", ignoreCase = true) ||
                    statusMessage.contains("enabled", ignoreCase = true) ||
                    statusMessage.contains("disabled", ignoreCase = true)
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOk) colors.success else colors.error
                )
            }
        }
    }

    pendingConfirmAction?.let { action ->
        ConfirmSheet(
            title = stringResource(R.string.settings_sim_confirm_title),
            impactMessage = stringResource(R.string.settings_sim_confirm_impact),
            confirmLabel = stringResource(R.string.action_continue),
            tier = ConfirmTier.CONNECTIVITY,
            onDismiss = { pendingConfirmAction = null },
            onConfirm = {
                pendingConfirmAction = null
                action()
            }
        )
    }
}

@Composable
fun LanDhcpEditorSheet(
    currentHost: String,
    initialConfig: LanConfig,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (
        lanIp: String,
        netMask: String,
        dhcpEnabled: Boolean,
        ipBegin: String,
        ipEnd: String,
        expireTime: String,
        primaryDns: String,
        secondaryDns: String,
        updateHostIfChanged: Boolean
    ) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var lanIp by remember(initialConfig.lanIp) { mutableStateOf(initialConfig.lanIp) }
    var netMask by remember(initialConfig.netMask) { mutableStateOf(initialConfig.netMask) }
    var dhcpEnabled by remember(initialConfig.dhcpEnabled) { mutableStateOf(initialConfig.dhcpEnabled) }
    var ipBegin by remember(initialConfig.ipBegin) { mutableStateOf(initialConfig.ipBegin) }
    var ipEnd by remember(initialConfig.ipEnd) { mutableStateOf(initialConfig.ipEnd) }
    var expireTime by remember(initialConfig.expireTime) { mutableStateOf(initialConfig.expireTime.ifBlank { "24h" }) }
    var primaryDns by remember(initialConfig.primaryDns) { mutableStateOf(initialConfig.primaryDns) }
    var secondaryDns by remember(initialConfig.secondaryDns) { mutableStateOf(initialConfig.secondaryDns) }
    var autoUpdateHost by remember { mutableStateOf(true) }
    var showConfirm by remember { mutableStateOf(false) }

    val cleanIp = lanIp.trim()
    val cleanMask = netMask.trim()
    val cleanBegin = ipBegin.trim()
    val cleanEnd = ipEnd.trim()
    val cleanPrimaryDns = primaryDns.trim()
    val cleanSecondaryDns = secondaryDns.trim()

    val dhcpError = WifiAndLanValidators.validateDhcpSettings(
        lanIp = cleanIp,
        netMask = cleanMask,
        dhcpEnabled = dhcpEnabled,
        ipBegin = cleanBegin,
        ipEnd = cleanEnd
    )
    val dnsError = when {
        cleanPrimaryDns.isNotEmpty() && !NetworkFormatters.isIpv4(cleanPrimaryDns) -> "Invalid Primary DNS IPv4"
        cleanSecondaryDns.isNotEmpty() && !NetworkFormatters.isIpv4(cleanSecondaryDns) -> "Invalid Secondary DNS IPv4"
        cleanPrimaryDns.isNotEmpty() && cleanPrimaryDns == cleanSecondaryDns -> "Secondary DNS must differ from Primary"
        else -> null
    }
    val validationError = dhcpError ?: dnsError
    val lanIpChanged = cleanIp != currentHost && NetworkFormatters.isIpv4(cleanIp)

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
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Router,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.settings_lan_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                OutlinedTextField(
                    value = lanIp,
                    onValueChange = { lanIp = it },
                    label = { Text(stringResource(R.string.settings_lan_ip_field)) },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = AirtelTheme.shapes.input,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = netMask,
                    onValueChange = { netMask = it },
                    label = { Text(stringResource(R.string.settings_lan_mask_field)) },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = AirtelTheme.shapes.input,
                    modifier = Modifier.weight(1f)
                )
            }

            if (lanIpChanged) {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_lan_ip_change_warn),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        SettingSwitchRow(
                            title = stringResource(R.string.settings_lan_auto_update_host),
                            checked = autoUpdateHost,
                            onCheckedChange = { autoUpdateHost = it },
                            enabled = !isSaving
                        )
                    }
                }
            }

            Surface(
                shape = AirtelTheme.shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                BoxSettingSwitch(
                    checked = dhcpEnabled,
                    onCheckedChange = { dhcpEnabled = it },
                    enabled = !isSaving
                )
            }

            if (dhcpEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    OutlinedTextField(
                        value = ipBegin,
                        onValueChange = { ipBegin = it },
                        label = { Text(stringResource(R.string.settings_lan_start_ip)) },
                        singleLine = true,
                        enabled = !isSaving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = AirtelTheme.shapes.input,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = ipEnd,
                        onValueChange = { ipEnd = it },
                        label = { Text(stringResource(R.string.settings_lan_end_ip)) },
                        singleLine = true,
                        enabled = !isSaving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = AirtelTheme.shapes.input,
                        modifier = Modifier.weight(1f)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.settings_lan_lease_time),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        for (preset in listOf("1h", "12h", "24h", "7d")) {
                            FilterChip(
                                selected = expireTime == preset,
                                onClick = { expireTime = preset },
                                label = { Text(preset) }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                OutlinedTextField(
                    value = primaryDns,
                    onValueChange = { primaryDns = it },
                    label = { Text(stringResource(R.string.settings_lan_primary_dns)) },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = AirtelTheme.shapes.input,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = secondaryDns,
                    onValueChange = { secondaryDns = it },
                    label = { Text(stringResource(R.string.settings_lan_secondary_dns)) },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = AirtelTheme.shapes.input,
                    modifier = Modifier.weight(1f)
                )
            }

            val shownError = validationError ?: errorMessage?.takeIf { it.startsWith("Error:") || it.startsWith("Invalid") }
            if (shownError != null) {
                Text(
                    text = shownError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !isSaving) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { showConfirm = true },
                    enabled = validationError == null && !isSaving
                ) {
                    if (isSaving) {
                        LoadingIndicator(
                            modifier = Modifier
                                .padding(end = spacing.sm)
                                .size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (showConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.settings_lan_confirm_title),
            impactMessage = if (lanIpChanged) {
                stringResource(R.string.settings_lan_ip_change_warn)
            } else {
                stringResource(R.string.settings_lan_confirm_impact)
            },
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                onSave(
                    cleanIp,
                    cleanMask,
                    dhcpEnabled,
                    cleanBegin,
                    cleanEnd,
                    expireTime,
                    cleanPrimaryDns,
                    cleanSecondaryDns,
                    lanIpChanged && autoUpdateHost
                )
            }
        )
    }
}

@Composable
private fun BoxSettingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    val spacing = AirtelTheme.spacing
    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
        SettingSwitchRow(
            title = stringResource(R.string.settings_lan_dhcp_switch),
            subtitle = stringResource(R.string.settings_lan_dhcp_switch_desc),
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
