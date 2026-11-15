package com.airtel.monitor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class AirtelSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp
)

@Immutable
data class AirtelSemanticColors(
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val signalExcellent: Color,
    val signalGood: Color,
    val signalFair: Color,
    val signalPoor: Color,
    val downloadSeries: Color,
    val uploadSeries: Color
)

val DarkAirtelSemanticColors = AirtelSemanticColors(
    success = DarkStatusSuccess,
    warning = DarkStatusWarning,
    error = DarkStatusError,
    info = DarkStatusInfo,
    signalExcellent = DarkStatusSuccess,
    signalGood = DarkStatusInfo,
    signalFair = DarkStatusWarning,
    signalPoor = DarkStatusError,
    downloadSeries = DarkPrimary,
    uploadSeries = DarkTertiary
)

val LightAirtelSemanticColors = AirtelSemanticColors(
    success = LightStatusSuccess,
    warning = LightStatusWarning,
    error = LightStatusError,
    info = LightStatusInfo,
    signalExcellent = LightStatusSuccess,
    signalGood = LightStatusInfo,
    signalFair = LightStatusWarning,
    signalPoor = LightStatusError,
    downloadSeries = LightPrimary,
    uploadSeries = LightTertiary
)

val AirtelMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Immutable
data class AirtelShapeTokens(
    val card: RoundedCornerShape = RoundedCornerShape(20.dp),
    val tile: RoundedCornerShape = RoundedCornerShape(12.dp),
    val input: RoundedCornerShape = RoundedCornerShape(12.dp),
    val chip: RoundedCornerShape = RoundedCornerShape(50),
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
)

val LocalAirtelSpacing = staticCompositionLocalOf { AirtelSpacing() }
val LocalAirtelColors = staticCompositionLocalOf { DarkAirtelSemanticColors }
val LocalAirtelShapes = staticCompositionLocalOf { AirtelShapeTokens() }
