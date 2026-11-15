package com.airtel.monitor.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.airtel.monitor.R
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object SignalRoute
@Serializable data object DevicesRoute
@Serializable data object SettingsRoute
@Serializable data object AlignmentRoute
@Serializable data object MessagesRoute
@Serializable data object DataUsageRoute
@Serializable data object SecurityRoute
@Serializable data object SystemToolsRoute
@Serializable data object OnboardingRoute

enum class TopLevelDestination(
    val route: Any,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
) {
    HOME(HomeRoute, R.string.nav_home, Icons.Rounded.Home),
    SIGNAL(SignalRoute, R.string.nav_signal, Icons.Rounded.CellTower),
    DEVICES(DevicesRoute, R.string.nav_devices, Icons.Rounded.Devices),
    SETTINGS(SettingsRoute, R.string.nav_settings, Icons.Rounded.Settings)
}
