@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.feature.usage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.dto.formatDataSizeMb
import com.airtel.monitor.data.model.TrafficLimitSettings
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.EmptyState
import com.airtel.monitor.ui.designsystem.LineChart
import com.airtel.monitor.ui.designsystem.LineChartSeries
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.designsystem.SettingValueRow
import com.airtel.monitor.ui.designsystem.StatTile
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace
import java.util.Locale

@Composable
fun DataUsageScreen(
    viewModel: DataUsageViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val usage = uiState.monthlyUsage
    val history = uiState.dailyHistory
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors

    LaunchedEffect(Unit) {
        viewModel.ensureLoaded()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
            Text(
                text = stringResource(R.string.usage_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh_settings)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            item(key = "cycle_summary") {
                val usedText = usage.usedMb?.let(::formatDataSizeMb) ?: "--"
                val limitText = usage.limitMb?.let(::formatDataSizeMb)
                val progress = usage.progressFraction
                val hasLimit = usage.limitEnabled && progress != null

                SectionCard(
                    title = stringResource(R.string.usage_cycle_summary_title),
                    trailing = if (hasLimit) {
                        {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(44.dp),
                                    color = if (usage.isOverWarning) colors.warning else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    strokeWidth = 4.dp,
                                    strokeCap = StrokeCap.Round
                                )
                                Text(
                                    text = "${(progress * 100f).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.merge(TabularMonospace),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        null
                    }
                ) {
                    Text(
                        text = if (hasLimit && limitText != null) {
                            stringResource(R.string.home_usage_limit_fmt, usedText, limitText)
                        } else {
                            stringResource(R.string.home_usage_used_fmt, usedText)
                        },
                        style = MaterialTheme.typography.headlineSmall.merge(TabularMonospace),
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        StatTile(
                            label = stringResource(R.string.usage_dl_month_label),
                            value = usage.dlMonthMb?.let(::formatDataSizeMb) ?: "--",
                            modifier = Modifier.weight(1f)
                        )
                        StatTile(
                            label = stringResource(R.string.usage_ul_month_label),
                            value = usage.ulMonthMb?.let(::formatDataSizeMb) ?: "--",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    SettingValueRow(
                        label = stringResource(R.string.usage_limit_label),
                        value = if (hasLimit && limitText != null) {
                            limitText
                        } else {
                            stringResource(R.string.usage_limit_disabled)
                        }
                    )
                    if (usage.warnPercentage != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SettingValueRow(
                            label = stringResource(R.string.usage_warn_threshold_label),
                            value = "${usage.warnPercentage}%"
                        )
                    }
                    if (usage.cycleStartDay != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SettingValueRow(
                            label = stringResource(R.string.usage_cycle_day_label),
                            value = stringResource(R.string.home_usage_cycle_day_fmt, usage.cycleStartDay)
                        )
                    }
                    if (usage.daysLeftInCycle != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        SettingValueRow(
                            label = stringResource(R.string.usage_days_left_label),
                            value = pluralStringResource(R.plurals.home_usage_days_left_fmt, usage.daysLeftInCycle, usage.daysLeftInCycle)
                        )
                    }

                    if (uiState.quotaEditable) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            FilledTonalButton(
                                onClick = viewModel::openQuotaSheet,
                                enabled = !uiState.isSaving,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(
                                    text = stringResource(R.string.usage_edit_limit_btn),
                                    modifier = Modifier.padding(start = spacing.xs)
                                )
                            }
                            OutlinedButton(
                                onClick = viewModel::openCalibrateSheet,
                                enabled = !uiState.isSaving,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(
                                    text = stringResource(R.string.usage_calibrate_btn),
                                    modifier = Modifier.padding(start = spacing.xs)
                                )
                            }
                        }
                    }

                    if (!uiState.statusMessage.isNullOrBlank()) {
                        val isErr = uiState.statusMessage!!.startsWith("Error:")
                        Text(
                            text = uiState.statusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isErr) colors.error else colors.success
                        )
                    }
                }
            }

            item(key = "daily_history") {
                SectionCard(
                    title = stringResource(R.string.usage_daily_history_title),
                    subtitle = stringResource(R.string.usage_daily_history_subtitle),
                    trailing = if (history.isNotEmpty()) {
                        {
                            TextButton(
                                onClick = viewModel::clearLocalHistory,
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Text(stringResource(R.string.usage_clear_history))
                            }
                        }
                    } else {
                        null
                    }
                ) {
                    if (history.isEmpty()) {
                        EmptyState(
                            icon = Icons.Rounded.DataUsage,
                            title = stringResource(R.string.usage_daily_empty_title),
                            message = stringResource(R.string.usage_daily_empty_desc)
                        )
                    } else {
                        if (history.size >= 2) {
                            LineChart(
                                series = listOf(
                                    LineChartSeries(
                                        values = history.map { it.dailyMb.toFloat() },
                                        color = MaterialTheme.colorScheme.primary,
                                        fillArea = true
                                    )
                                ),
                                fixedMin = 0f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.usage_daily_chart_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val recentFirst = history.asReversed()
                        recentFirst.forEachIndexed { idx, entry ->
                            if (idx > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                            SettingValueRow(
                                label = entry.dateIso,
                                value = formatDataSizeMb(entry.dailyMb)
                            )
                        }
                    }
                }
            }

            item(key = "usage_local_note") {
                Text(
                    text = stringResource(R.string.usage_local_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (uiState.showQuotaSheet) {
        val currentCfg = uiState.limitSettings ?: TrafficLimitSettings(
            limitEnabled = usage.limitEnabled,
            limitSize = usage.limitMb?.let { (it / 1024.0).toInt().toString() } ?: "0",
            unitCode = "1",
            startDate = (usage.cycleStartDay ?: 1).coerceIn(1, 28)
        )
        QuotaEditorSheet(
            initial = currentCfg,
            isSaving = uiState.isSaving,
            onDismiss = viewModel::dismissSheets,
            onSave = viewModel::saveTrafficLimit
        )
    }

    if (uiState.showCalibrateSheet) {
        val initialUsedMb = usage.usedMb ?: uiState.limitSettings?.totalUsedMb ?: 0.0
        CalibrateUsageSheet(
            currentUsedMb = initialUsedMb,
            isSaving = uiState.isSaving,
            onDismiss = viewModel::dismissSheets,
            onConfirmCalibrate = viewModel::resetTrafficStatistics
        )
    }
}

@Composable
private fun QuotaEditorSheet(
    initial: TrafficLimitSettings,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, size: String, unitCode: String, startDate: Int) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    var limitEnabled by remember(initial) { mutableStateOf(initial.limitEnabled) }
    var limitSize by remember(initial) { mutableStateOf(initial.limitSize.ifEmpty { "500" }) }
    var unitCode by remember(initial) { mutableStateOf(initial.unitCode) }
    var startDateText by remember(initial) { mutableStateOf(initial.startDate.toString()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pendingStartConfirm by remember { mutableStateOf<Triple<Boolean, String, Int>?>(null) }

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
                text = stringResource(R.string.usage_quota_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            SettingSwitchRow(
                title = stringResource(R.string.usage_limit_switch_label),
                checked = limitEnabled,
                enabled = !isSaving,
                onCheckedChange = { limitEnabled = it }
            )

            OutlinedTextField(
                value = limitSize,
                onValueChange = { limitSize = it.filter { ch -> ch in '0'..'9' }; errorText = null },
                label = { Text(stringResource(R.string.usage_limit_size_label)) },
                enabled = limitEnabled && !isSaving,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            val units = listOf("0" to "MB", "1" to "GB", "2" to "TB")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                units.forEachIndexed { idx, (code, label) ->
                    SegmentedButton(
                        selected = unitCode == code,
                        onClick = { unitCode = code },
                        enabled = limitEnabled && !isSaving,
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = units.size)
                    ) {
                        Text(label)
                    }
                }
            }

            OutlinedTextField(
                value = startDateText,
                onValueChange = { startDateText = it.filter { ch -> ch in '0'..'9' }; errorText = null },
                label = { Text(stringResource(R.string.usage_start_day_label)) },
                enabled = !isSaving,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            val parsedDay = startDateText.toIntOrNull()
            if (parsedDay != null && parsedDay in 1..28 && parsedDay != initial.startDate) {
                Surface(
                    shape = AirtelTheme.shapes.tile,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.usage_start_day_reset_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(spacing.sm)
                    )
                }
            }

            if (errorText != null) {
                Text(
                    text = errorText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
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
                        val sizeNum = limitSize.toIntOrNull()
                        if (limitEnabled && (sizeNum == null || sizeNum !in 0..99999)) {
                            errorText = "Quota size must be 0–99999"
                            return@Button
                        }
                        val day = startDateText.toIntOrNull()
                        if (day == null || day !in 1..28) {
                            errorText = "Cycle start day must be 1–28"
                            return@Button
                        }
                        if (day != initial.startDate) {
                            pendingStartConfirm = Triple(limitEnabled, limitSize.ifEmpty { "0" }, day)
                        } else {
                            onSave(limitEnabled, limitSize.ifEmpty { "0" }, unitCode, day)
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    pendingStartConfirm?.let { (en, sz, day) ->
        ConfirmSheet(
            tier = ConfirmTier.CONNECTIVITY,
            title = stringResource(R.string.usage_start_day_confirm_title),
            impactMessage = stringResource(R.string.usage_start_day_confirm_impact, initial.startDate, day),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = {
                pendingStartConfirm = null
                onSave(en, sz, unitCode, day)
            },
            onDismiss = { pendingStartConfirm = null }
        )
    }
}

@Composable
private fun CalibrateUsageSheet(
    currentUsedMb: Double,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirmCalibrate: (targetUsedMb: Double) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    var unitCode by remember { mutableStateOf("1") }
    var valueText by remember { mutableStateOf("0") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pendingMb by remember { mutableStateOf<Pair<Double, String>?>(null) }

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
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = stringResource(R.string.usage_calibrate_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Current router counter: ${formatDataSizeMb(currentUsedMb)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = valueText,
                onValueChange = { valueText = it; errorText = null },
                label = { Text(stringResource(R.string.usage_calibrate_value_label)) },
                enabled = !isSaving,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            val units = listOf("0" to "MB", "1" to "GB", "2" to "TB")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                units.forEachIndexed { idx, (code, label) ->
                    SegmentedButton(
                        selected = unitCode == code,
                        onClick = { unitCode = code },
                        enabled = !isSaving,
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = units.size)
                    ) {
                        Text(label)
                    }
                }
            }

            if (errorText != null) {
                Text(
                    text = errorText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
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
                        val raw = valueText.trim().toDoubleOrNull()
                        if (raw == null || raw < 0.0 || raw > 99999.0) {
                            errorText = "Enter a value between 0 and 99999"
                            return@Button
                        }
                        val factor = when (unitCode) {
                            "0" -> 1.0
                            "2" -> 1048576.0
                            else -> 1024.0
                        }
                        val mb = raw * factor
                        val unitLabel = when (unitCode) {
                            "0" -> "MB"
                            "2" -> "TB"
                            else -> "GB"
                        }
                        val pretty = String.format(Locale.US, "%s %s", valueText.trim(), unitLabel)
                        pendingMb = mb to pretty
                    },
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    pendingMb?.let { (mb, pretty) ->
        ConfirmSheet(
            tier = ConfirmTier.CONNECTIVITY,
            title = stringResource(R.string.usage_calibrate_confirm_title),
            impactMessage = stringResource(R.string.usage_calibrate_confirm_impact, pretty),
            confirmLabel = stringResource(R.string.action_continue),
            onConfirm = {
                pendingMb = null
                onConfirmCalibrate(mb)
            },
            onDismiss = { pendingMb = null }
        )
    }
}
