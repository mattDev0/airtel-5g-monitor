package com.airtel.monitor.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

enum class SignalQualityBand { EXCELLENT, GOOD, FAIR, POOR, UNKNOWN }

@Composable
fun SignalQualityBand.color(): Color {
    val colors = AirtelTheme.colors
    return when (this) {
        SignalQualityBand.EXCELLENT -> colors.signalExcellent
        SignalQualityBand.GOOD -> colors.signalGood
        SignalQualityBand.FAIR -> colors.signalFair
        SignalQualityBand.POOR -> colors.signalPoor
        SignalQualityBand.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
}

@Composable
fun SignalQualityBand.label(): String = when (this) {
    SignalQualityBand.EXCELLENT -> stringResource(R.string.signal_quality_excellent)
    SignalQualityBand.GOOD -> stringResource(R.string.signal_quality_good)
    SignalQualityBand.FAIR -> stringResource(R.string.signal_quality_fair)
    SignalQualityBand.POOR -> stringResource(R.string.signal_quality_poor)
    SignalQualityBand.UNKNOWN -> stringResource(R.string.signal_quality_unknown)
}

@Composable
fun SignalBars(
    level: Int,
    maxBars: Int = 5,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary
) {
    val desc = stringResource(R.string.cd_signal_bars, level.coerceIn(0, maxBars), maxBars)
    Row(
        modifier = modifier
            .height(15.dp)
            .semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val heights = if (maxBars == 5) {
            listOf(4.dp, 6.dp, 9.dp, 12.dp, 15.dp)
        } else {
            listOf(4.dp, 7.dp, 10.dp, 14.dp)
        }
        for (i in 0 until maxBars) {
            val h = heights.getOrElse(i) { 15.dp }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (i < level) activeColor else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
            )
        }
    }
}

@Composable
fun SignalMeter(
    label: String,
    valueText: String,
    progress: Float,
    quality: SignalQualityBand,
    modifier: Modifier = Modifier
) {
    val spacing = AirtelTheme.spacing
    val bandColor = quality.color()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AirtelTheme.shapes.tile,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.titleSmall.merge(TabularMonospace),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = AirtelTheme.shapes.chip,
                        color = bandColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = quality.label(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = bandColor,
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = 2.dp)
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(AirtelTheme.shapes.chip),
                color = bandColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )
        }
    }
}
