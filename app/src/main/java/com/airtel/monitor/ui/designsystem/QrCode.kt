package com.airtel.monitor.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.airtel.monitor.ui.theme.AirtelTheme

/**
 * Renders a pre-encoded boolean QR matrix with quiet zone and clean square/rounded modules.
 * ZXing bit-matrix generation hooks into this in Phase 3 (Wi-Fi sharing).
 */
@Composable
fun QrCode(
    matrix: List<List<Boolean>>,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    background: Color = MaterialTheme.colorScheme.surfaceContainerLowest
) {
    val spacing = AirtelTheme.spacing
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .padding(spacing.md)
            .semantics { contentDescription = contentDescriptionText }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sizeCount = matrix.size
            if (sizeCount == 0) return@Canvas
            val cell = size.minDimension / sizeCount
            val radius = CornerRadius(cell * 0.15f, cell * 0.15f)
            for (row in 0 until sizeCount) {
                val cols = matrix[row]
                for (col in cols.indices) {
                    if (cols[col]) {
                        drawRoundRect(
                            color = foreground,
                            topLeft = Offset(col * cell, row * cell),
                            size = Size(cell * 0.96f, cell * 0.96f),
                            cornerRadius = radius
                        )
                    }
                }
            }
        }
    }
}
