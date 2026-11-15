package com.airtel.monitor.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max

data class LineChartSeries(
    val values: List<Float>,
    val color: Color,
    val fillArea: Boolean = false
)

@Composable
fun Sparkline(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    secondaryValues: List<Float> = emptyList(),
    secondaryColor: Color = MaterialTheme.colorScheme.secondary
) {
    val series = buildList {
        if (values.isNotEmpty()) add(LineChartSeries(values, lineColor, fillArea = true))
        if (secondaryValues.isNotEmpty()) add(LineChartSeries(secondaryValues, secondaryColor, fillArea = false))
    }
    LineChart(
        series = series,
        showGrid = false,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    )
}

@Composable
fun LineChart(
    series: List<LineChartSeries>,
    modifier: Modifier = Modifier,
    showGrid: Boolean = true,
    fixedMin: Float? = null,
    fixedMax: Float? = null
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    Canvas(modifier = modifier) {
        val allValues = series.flatMap { it.values }
        if (allValues.size < 2) return@Canvas

        val minVal = fixedMin ?: (allValues.minOrNull() ?: 0f)
        val rawMax = fixedMax ?: (allValues.maxOrNull() ?: 1f)
        val maxVal = max(rawMax, minVal + 0.01f)
        val range = maxVal - minVal

        if (showGrid) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            for (step in 1..3) {
                val y = size.height * (step / 4f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash
                )
            }
        }

        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val path = Path()
            val fillPath = Path()
            val stepX = size.width / (s.values.size - 1).coerceAtLeast(1)

            s.values.forEachIndexed { idx, v ->
                val x = idx * stepX
                val norm = ((v - minVal) / range).coerceIn(0f, 1f)
                val y = size.height - norm * size.height
                if (idx == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo((s.values.size - 1) * stepX, size.height)
            fillPath.close()

            if (s.fillArea) {
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(s.color.copy(alpha = 0.25f), s.color.copy(alpha = 0.01f))
                    )
                )
            }

            drawPath(
                path = path,
                color = s.color,
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}
