@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.dto.formatDataSizeMb
import com.airtel.monitor.ui.designsystem.EmptyState
import com.airtel.monitor.ui.designsystem.LineChart
import com.airtel.monitor.ui.designsystem.LineChartSeries
import com.airtel.monitor.ui.designsystem.SectionCard
import com.airtel.monitor.ui.designsystem.SettingValueRow
import com.airtel.monitor.ui.designsystem.StatTile
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

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

            item(key = "usage_deferred_note") {
                Text(
                    text = stringResource(R.string.usage_deferred_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
