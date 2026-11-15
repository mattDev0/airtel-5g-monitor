@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.airtel.monitor.ui.feature.settings

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
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.core.util.WifiAndLanValidators
import com.airtel.monitor.data.model.GuestWifiBandConfig
import com.airtel.monitor.data.model.GuestWifiSnapshot
import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.WifiAdvancedBandConfig
import com.airtel.monitor.data.model.WifiPower411Config
import com.airtel.monitor.data.model.WifiScheduleConfig
import com.airtel.monitor.data.model.WpsSnapshot
import com.airtel.monitor.data.repository.WifiRepository.WifiBand
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.designsystem.SettingValueRow
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace
import java.util.Locale

@Composable
fun GuestWifiSheet(
    snapshot: GuestWifiSnapshot,
    featureFlags: RouterFeatureFlags,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSave: (WifiBand, GuestWifiBandConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    val bands = remember(featureFlags) {
        buildList {
            if (featureFlags.guestWifi24gAvailable) add(WifiBand.BAND_24G)
            if (featureFlags.guestWifi5gAvailable) add(WifiBand.BAND_5G)
        }
    }
    var bandIdx by remember(bands) { mutableIntStateOf(0) }
    val activeBand = bands.getOrElse(bandIdx) { WifiBand.BAND_24G }
    val currentConfig = if (activeBand == WifiBand.BAND_24G) snapshot.band24g else snapshot.band5g

    var wifiOpen by remember(activeBand, currentConfig.wifiOpen) { mutableStateOf(currentConfig.wifiOpen) }
    var visibleBroadcast by remember(activeBand, currentConfig.visibleBroadcast) {
        mutableStateOf(currentConfig.visibleBroadcast)
    }
    var ssidInput by remember(activeBand, currentConfig.ssid) { mutableStateOf(currentConfig.ssid) }
    var keyInput by remember(activeBand, currentConfig.key) { mutableStateOf(currentConfig.key) }
    var authInput by remember(activeBand, currentConfig.authenticationType) {
        mutableStateOf(currentConfig.authenticationType)
    }
    var pwdVisible by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val ssidErr = if (!wifiOpen) null else WifiAndLanValidators.validateSsid(ssidInput)
    val sameErr = if (wifiOpen && ssidInput in currentConfig.forbiddenSameSsids) {
        "Guest SSID cannot match primary Wi-Fi SSID"
    } else null
    val keyErr = if (!wifiOpen) null else WifiAndLanValidators.validateWifiPassword(keyInput, authInput)
    val canSave = !isSaving && !isLoading && (!wifiOpen || (currentConfig.mainWifiOpen && ssidErr == null && sameErr == null && keyErr == null))

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
            WifiSheetHeader(
                icon = Icons.Rounded.Wifi,
                title = stringResource(R.string.settings_wifi_guest_title),
                subtitle = stringResource(R.string.settings_wifi_guest_subtitle),
                isBusy = isLoading || isSaving,
                onRefresh = onRefresh
            )

            if (bands.size > 1) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    bands.forEachIndexed { idx, band ->
                        SegmentedButton(
                            selected = bandIdx == idx,
                            onClick = { bandIdx = idx },
                            shape = SegmentedButtonDefaults.itemShape(idx, bands.size)
                        ) {
                            Text(band.label)
                        }
                    }
                }
            }

            if (!currentConfig.mainWifiOpen) {
                SectionCard {
                    Text(
                        text = stringResource(R.string.settings_wifi_guest_main_off, activeBand.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning
                    )
                }
            }

            SectionCard {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_guest_enable),
                    checked = wifiOpen,
                    enabled = !isSaving && currentConfig.mainWifiOpen,
                    onCheckedChange = { wifiOpen = it }
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_broadcast_label),
                    subtitle = stringResource(R.string.settings_wifi_broadcast_desc),
                    checked = visibleBroadcast,
                    enabled = !isSaving && wifiOpen,
                    onCheckedChange = { visibleBroadcast = it }
                )
                OutlinedTextField(
                    value = ssidInput,
                    onValueChange = { ssidInput = it },
                    label = { Text(stringResource(R.string.settings_wifi_ssid_label)) },
                    singleLine = true,
                    enabled = !isSaving && wifiOpen,
                    isError = ssidErr != null || sameErr != null,
                    supportingText = (sameErr ?: ssidErr)?.let { msg -> { Text(msg) } },
                    modifier = Modifier.fillMaxWidth()
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val authOptions = listOf("2" to "WPA2", "3" to "WPA/WPA2", "4" to "WPA2/WPA3", "0" to "Open")
                    authOptions.forEachIndexed { idx, (code, label) ->
                        SegmentedButton(
                            selected = authInput == code,
                            enabled = !isSaving && wifiOpen,
                            onClick = { authInput = code },
                            shape = SegmentedButtonDefaults.itemShape(idx, authOptions.size)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (authInput != "0") {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text(stringResource(R.string.settings_wifi_password_label)) },
                        singleLine = true,
                        enabled = !isSaving && wifiOpen,
                        isError = keyErr != null,
                        supportingText = keyErr?.let { msg -> { Text(msg) } },
                        visualTransformation = if (pwdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { pwdVisible = !pwdVisible }) {
                                Icon(
                                    imageVector = if (pwdVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(R.string.cd_toggle_password)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            SheetActionRow(
                canSave = canSave,
                onDismiss = onDismiss,
                onSave = { showConfirm = true }
            )
        }
    }

    if (showConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.settings_wifi_save_title),
            impactMessage = stringResource(R.string.settings_wifi_save_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                showConfirm = false
                onSave(
                    activeBand,
                    currentConfig.copy(
                        wifiOpen = wifiOpen,
                        visibleBroadcast = visibleBroadcast,
                        ssid = ssidInput,
                        key = keyInput,
                        authenticationType = authInput
                    )
                )
            },
            onDismiss = { showConfirm = false }
        )
    }
}

@Composable
fun WifiAdvancedSheet(
    adv24g: WifiAdvancedBandConfig?,
    adv5g: WifiAdvancedBandConfig?,
    power411: WifiPower411Config?,
    featureFlags: RouterFeatureFlags,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSaveBand: (WifiBand, WifiAdvancedBandConfig) -> Unit,
    onSavePower411: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    var selectedBandIdx by remember { mutableIntStateOf(0) }
    val activeBand = if (selectedBandIdx == 0) WifiBand.BAND_24G else WifiBand.BAND_5G
    val cfg = (if (activeBand == WifiBand.BAND_24G) adv24g else adv5g) ?: WifiAdvancedBandConfig()

    var channelInput by remember(activeBand, cfg.channel) { mutableStateOf(cfg.channel) }
    var bwInput by remember(activeBand, cfg.bandWidth) { mutableStateOf(cfg.bandWidth) }
    var txInput by remember(activeBand, cfg.txPower) { mutableStateOf(cfg.txPower) }
    var maxNumInput by remember(activeBand, cfg.maxNum) { mutableStateOf(cfg.maxNum.toString()) }
    var connectInput by remember(activeBand, cfg.connectNum) { mutableStateOf(cfg.connectNum.toString()) }
    var kickInput by remember(activeBand, cfg.eliminateNum) { mutableStateOf(cfg.eliminateNum.toString()) }
    var wmmInput by remember(activeBand, cfg.wifiWmm) { mutableStateOf(cfg.wifiWmm) }
    var perfInput by remember(activeBand, cfg.performance) { mutableStateOf(cfg.performance) }
    var dfsInput by remember(activeBand, cfg.dfsSwitch) { mutableStateOf(cfg.dfsSwitch) }
    var power411Input by remember(power411?.txPower24g) { mutableStateOf(power411?.txPower24g ?: "50") }
    var pendingSaveType by remember { mutableStateOf<Int?>(null) } // 1 = band adv, 2 = 411

    val maxNumParsed = maxNumInput.trim().toIntOrNull()
    val connParsed = connectInput.trim().toIntOrNull()
    val kickParsed = kickInput.trim().toIntOrNull()
    val validationErr = when {
        cfg.clientStaReadOnly -> null
        maxNumParsed == null || maxNumParsed !in 1..cfg.maxNumLimit ->
            "Max clients must be 1–${cfg.maxNumLimit}"
        connParsed == null || connParsed !in -99..0 ->
            "Connect RSSI must be -99 to 0"
        kickParsed == null || kickParsed !in -99..0 ->
            "Kickout RSSI must be -99 to 0"
        connParsed < kickParsed ->
            "Connect RSSI must be >= Kickout RSSI"
        else -> null
    }

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
            WifiSheetHeader(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.settings_wifi_advanced_title),
                subtitle = stringResource(R.string.settings_wifi_advanced_subtitle),
                isBusy = isLoading || isSaving,
                onRefresh = onRefresh
            )

            if (featureFlags.wifiPowerCmd411Available) {
                SectionCard(title = stringResource(R.string.settings_wifi_adv_tx_power)) {
                    val powerChoices = listOf(
                        "15" to stringResource(R.string.settings_wifi_power_short),
                        "50" to stringResource(R.string.settings_wifi_power_medium),
                        "100" to stringResource(R.string.settings_wifi_power_long)
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        powerChoices.forEachIndexed { idx, (code, label) ->
                            SegmentedButton(
                                selected = power411Input == code,
                                enabled = !isSaving,
                                onClick = { power411Input = code },
                                shape = SegmentedButtonDefaults.itemShape(idx, powerChoices.size)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Button(
                        onClick = { pendingSaveType = 2 },
                        enabled = !isSaving && !isLoading,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(WifiBand.BAND_24G, WifiBand.BAND_5G).forEachIndexed { idx, band ->
                    SegmentedButton(
                        selected = selectedBandIdx == idx,
                        onClick = { selectedBandIdx = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, 2)
                    ) {
                        Text(band.label)
                    }
                }
            }

            if (cfg.clientStaReadOnly) {
                SectionCard {
                    Text(
                        text = stringResource(R.string.settings_wifi_adv_sta_readonly),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning
                    )
                }
            }

            SectionCard {
                SettingValueRow(
                    label = "Country / Work Mode",
                    value = listOfNotNull(
                        cfg.countryCode.takeIf { it.isNotBlank() },
                        cfg.wifiWorkMode.takeIf { it.isNotBlank() }?.let { "Mode $it" }
                    ).joinToString(" · ").ifBlank { "--" }
                )

                Text(
                    text = stringResource(R.string.settings_wifi_adv_bandwidth),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val bwOptions = if (activeBand == WifiBand.BAND_24G) {
                    listOf("0" to "20 MHz", "1" to "40 MHz", "2" to "20/40 MHz")
                } else {
                    buildList {
                        add("0" to "20 MHz")
                        add("1" to "40 MHz")
                        add("3" to "80 MHz")
                        if (dfsInput) add("4" to "160 MHz")
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    bwOptions.forEach { (code, label) ->
                        FilterChip(
                            selected = bwInput == code,
                            enabled = !cfg.clientStaReadOnly && !isSaving,
                            onClick = { bwInput = code },
                            label = { Text(label) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.settings_wifi_adv_channel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val baseChannels = if (activeBand == WifiBand.BAND_24G) {
                    listOf("auto", "1", "6", "11")
                } else {
                    listOf("auto", "36", "40", "44", "48", "149", "153", "157", "161")
                }
                val channelChoices = remember(baseChannels, cfg.channel) {
                    if (cfg.channel.isNotBlank() && cfg.channel !in baseChannels) {
                        listOf(cfg.channel) + baseChannels
                    } else {
                        baseChannels
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    channelChoices.forEach { ch ->
                        FilterChip(
                            selected = channelInput.equals(ch, ignoreCase = true),
                            enabled = !cfg.clientStaReadOnly && !isSaving,
                            onClick = { channelInput = ch },
                            label = { Text(if (ch.equals("auto", true)) "Auto" else "Ch $ch") }
                        )
                    }
                }

                if (!featureFlags.wifiPowerCmd411Available) {
                    Text(
                        text = stringResource(R.string.settings_wifi_adv_tx_power),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val txChoices = remember(cfg.txPower) {
                        val std = listOf("25", "50", "75", "100")
                        if (cfg.txPower.isNotBlank() && cfg.txPower !in std) std + cfg.txPower else std
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        txChoices.forEach { p ->
                            FilterChip(
                                selected = txInput == p,
                                enabled = !cfg.clientStaReadOnly && !isSaving,
                                onClick = { txInput = p },
                                label = { Text("$p%") }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = maxNumInput,
                    onValueChange = { maxNumInput = it },
                    label = { Text("${stringResource(R.string.settings_wifi_adv_max_clients)} (1–${cfg.maxNumLimit})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !cfg.clientStaReadOnly && !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    OutlinedTextField(
                        value = connectInput,
                        onValueChange = { connectInput = it },
                        label = { Text(stringResource(R.string.settings_wifi_adv_connect_rssi)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !cfg.clientStaReadOnly && !isSaving,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = kickInput,
                        onValueChange = { kickInput = it },
                        label = { Text(stringResource(R.string.settings_wifi_adv_kick_rssi)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !cfg.clientStaReadOnly && !isSaving,
                        modifier = Modifier.weight(1f)
                    )
                }

                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_adv_wmm),
                    checked = wmmInput,
                    enabled = !cfg.clientStaReadOnly && !isSaving,
                    onCheckedChange = { wmmInput = it }
                )
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_adv_perf),
                    checked = perfInput,
                    enabled = !cfg.clientStaReadOnly && !isSaving,
                    onCheckedChange = { perfInput = it }
                )
                if (activeBand == WifiBand.BAND_5G) {
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_wifi_adv_dfs),
                        subtitle = stringResource(R.string.settings_wifi_adv_dfs_desc),
                        checked = dfsInput,
                        enabled = !cfg.clientStaReadOnly && !isSaving,
                        onCheckedChange = { enabled ->
                            dfsInput = enabled
                            if (!enabled && bwInput == "4") bwInput = "3"
                        }
                    )
                }
            }

            val shownErr = validationErr ?: errorMessage
            if (!shownErr.isNullOrBlank()) {
                Text(shownErr, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            SheetActionRow(
                canSave = !cfg.clientStaReadOnly && validationErr == null && !isSaving && !isLoading,
                onDismiss = onDismiss,
                onSave = { pendingSaveType = 1 }
            )
        }
    }

    pendingSaveType?.let { saveType ->
        ConfirmSheet(
            title = stringResource(R.string.settings_wifi_adv_confirm_title),
            impactMessage = stringResource(R.string.settings_wifi_adv_confirm_impact),
            confirmLabel = stringResource(R.string.action_save),
            tier = ConfirmTier.CONNECTIVITY,
            onConfirm = {
                pendingSaveType = null
                if (saveType == 2) {
                    onSavePower411(power411Input)
                } else {
                    onSaveBand(
                        activeBand,
                        cfg.copy(
                            txPower = txInput,
                            channel = channelInput,
                            bandWidth = bwInput,
                            maxNum = maxNumParsed ?: cfg.maxNum,
                            connectNum = connParsed ?: cfg.connectNum,
                            eliminateNum = kickParsed ?: cfg.eliminateNum,
                            wifiWmm = wmmInput,
                            performance = perfInput,
                            dfsSwitch = dfsInput
                        )
                    )
                }
            },
            onDismiss = { pendingSaveType = null }
        )
    }
}

@Composable
fun WpsSheet(
    snapshot: WpsSnapshot,
    randomPin: String?,
    secondsLeft: Int,
    pairedSuccess: Boolean,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSetWpsEnabled: (WifiBand, Boolean) -> Unit,
    onPushButton: (WifiBand) -> Unit,
    onSubmitPin: (WifiBand, String) -> Unit,
    onGenerateRandomPin: (WifiBand) -> Unit,
    onStopRandomPin: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    var bandIdx by remember { mutableIntStateOf(0) }
    val activeBand = if (bandIdx == 0) WifiBand.BAND_24G else WifiBand.BAND_5G
    val bandState = if (activeBand == WifiBand.BAND_24G) snapshot.band24g else snapshot.band5g
    var enrolleePin by remember { mutableStateOf("") }
    val pinErr = if (enrolleePin.isBlank()) null else Phase71Validators.validateWpsPin(enrolleePin)

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
            WifiSheetHeader(
                icon = Icons.Rounded.QrCode2,
                title = stringResource(R.string.settings_wifi_wps_title),
                subtitle = stringResource(R.string.settings_wifi_wps_subtitle),
                isBusy = isLoading || isSaving,
                onRefresh = onRefresh
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(WifiBand.BAND_24G, WifiBand.BAND_5G).forEachIndexed { idx, band ->
                    SegmentedButton(
                        selected = bandIdx == idx,
                        onClick = { bandIdx = idx },
                        shape = SegmentedButtonDefaults.itemShape(idx, 2)
                    ) {
                        Text(band.label)
                    }
                }
            }

            SectionCard {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_wps_enable_band, activeBand.label),
                    checked = bandState.enabled,
                    enabled = !isSaving && (bandState.eligibleForWps || bandState.enabled),
                    onCheckedChange = { onSetWpsEnabled(activeBand, it) }
                )
                if (!bandState.eligibleForWps) {
                    Text(
                        text = stringResource(R.string.settings_wifi_wps_ineligible, activeBand.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning
                    )
                }
            }

            SectionCard {
                Button(
                    onClick = { onPushButton(activeBand) },
                    enabled = bandState.enabled && !isSaving,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_wifi_wps_pbc))
                }

                OutlinedTextField(
                    value = enrolleePin,
                    onValueChange = { enrolleePin = it.filter { ch -> ch in '0'..'9' }.take(8) },
                    label = { Text(stringResource(R.string.settings_wifi_wps_pin_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = bandState.enabled && !isSaving,
                    isError = pinErr != null,
                    supportingText = pinErr?.let { msg -> { Text(msg) } },
                    leadingIcon = { Icon(Icons.Rounded.Password, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { onSubmitPin(activeBand, enrolleePin) },
                    enabled = bandState.enabled && enrolleePin.length == 8 && pinErr == null && !isSaving,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_wifi_wps_pin_connect))
                }

                if (!randomPin.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.settings_wifi_wps_random_active_fmt, randomPin, secondsLeft),
                        style = MaterialTheme.typography.titleSmall.merge(TabularMonospace),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = onStopRandomPin, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_wifi_wps_stop_poll))
                    }
                } else {
                    OutlinedButton(
                        onClick = { onGenerateRandomPin(activeBand) },
                        enabled = bandState.enabled && !isSaving,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(spacing.xs))
                        Text(stringResource(R.string.settings_wifi_wps_random_pin))
                    }
                }

                if (pairedSuccess) {
                    Text(
                        text = stringResource(R.string.settings_wifi_wps_paired_success),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.success
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
fun WifiScheduleSheet(
    config: WifiScheduleConfig,
    isLoading: Boolean,
    isSaving: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onSave: (WifiScheduleConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    var enabledInput by remember(config.enabled) { mutableStateOf(config.enabled) }
    var onTimeText by remember(config.onHour, config.onMinute) {
        mutableStateOf(String.format(Locale.US, "%02d:%02d", config.onHour, config.onMinute))
    }
    var offTimeText by remember(config.offHour, config.offMinute) {
        mutableStateOf(String.format(Locale.US, "%02d:%02d", config.offHour, config.offMinute))
    }
    val hhmmRegex = Regex("""^([01]?\d|2[0-3]):([0-5]?\d)$""")
    val onMatch = hhmmRegex.matchEntire(onTimeText.trim())
    val offMatch = hhmmRegex.matchEntire(offTimeText.trim())
    val formatErr = if (!enabledInput) null else when {
        onMatch == null -> "Turn ON time must use HH:mm (24h)"
        offMatch == null -> "Turn OFF time must use HH:mm (24h)"
        else -> null
    }

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
            WifiSheetHeader(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.settings_wifi_schedule_title),
                subtitle = stringResource(R.string.settings_wifi_schedule_subtitle),
                isBusy = isLoading || isSaving,
                onRefresh = onRefresh
            )

            SectionCard {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_wifi_schedule_enable),
                    checked = enabledInput,
                    enabled = !isSaving,
                    onCheckedChange = { enabledInput = it }
                )
                OutlinedTextField(
                    value = onTimeText,
                    onValueChange = { onTimeText = it },
                    label = { Text(stringResource(R.string.settings_wifi_schedule_on_time)) },
                    placeholder = { Text("08:00") },
                    singleLine = true,
                    enabled = enabledInput && !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = offTimeText,
                    onValueChange = { offTimeText = it },
                    label = { Text(stringResource(R.string.settings_wifi_schedule_off_time)) },
                    placeholder = { Text("23:00") },
                    singleLine = true,
                    enabled = enabledInput && !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val shownErr = formatErr ?: errorMessage
            if (!shownErr.isNullOrBlank()) {
                Text(shownErr, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }

            SheetActionRow(
                canSave = formatErr == null && !isSaving && !isLoading,
                onDismiss = onDismiss,
                onSave = {
                    onSave(
                        WifiScheduleConfig(
                            enabled = enabledInput,
                            onHour = onMatch?.groupValues?.get(1)?.toIntOrNull() ?: config.onHour,
                            onMinute = onMatch?.groupValues?.get(2)?.toIntOrNull() ?: config.onMinute,
                            offHour = offMatch?.groupValues?.get(1)?.toIntOrNull() ?: config.offHour,
                            offMinute = offMatch?.groupValues?.get(2)?.toIntOrNull() ?: config.offMinute
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun WifiSheetHeader(
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
private fun SheetActionRow(
    canSave: Boolean,
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
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_cancel))
        }
        Button(
            onClick = onSave,
            enabled = canSave,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}
