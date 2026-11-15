@file:OptIn(
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.airtel.monitor.R
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.data.model.ConnectionStatus
import com.airtel.monitor.ui.components.GlassNavBar
import com.airtel.monitor.ui.components.GlassNavBarHeight
import com.airtel.monitor.ui.components.HeaderBar
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.feature.devices.DevicesScreen
import com.airtel.monitor.ui.feature.devices.DevicesViewModel
import com.airtel.monitor.ui.feature.home.HomeScreen
import com.airtel.monitor.ui.feature.home.HomeViewModel
import com.airtel.monitor.ui.feature.messages.MessagesScreen
import com.airtel.monitor.ui.feature.messages.MessagesViewModel
import com.airtel.monitor.ui.feature.security.SecurityScreen
import com.airtel.monitor.ui.feature.security.SecurityViewModel
import com.airtel.monitor.ui.feature.settings.SettingsHubScreen
import com.airtel.monitor.ui.feature.settings.SettingsHubViewModel
import com.airtel.monitor.ui.feature.signal.AlignmentScreen
import com.airtel.monitor.ui.feature.signal.SignalScreen
import com.airtel.monitor.ui.feature.signal.SignalViewModel
import com.airtel.monitor.ui.feature.system.SystemToolsScreen
import com.airtel.monitor.ui.feature.system.SystemToolsViewModel
import com.airtel.monitor.ui.feature.usage.DataUsageScreen
import com.airtel.monitor.ui.feature.usage.DataUsageViewModel
import com.airtel.monitor.ui.theme.AirtelTheme

@Composable
fun AppShell(
    container: AppContainer,
    shellViewModel: ShellViewModel
) {
    val shellState by shellViewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = AirtelTheme.spacing

    LaunchedEffect(shellViewModel) {
        shellViewModel.statusMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isAlignmentFullScreen = currentDestination?.hasRoute(AlignmentRoute::class) == true ||
        currentDestination?.hasRoute(MessagesRoute::class) == true ||
        currentDestination?.hasRoute(DataUsageRoute::class) == true ||
        currentDestination?.hasRoute(SecurityRoute::class) == true ||
        currentDestination?.hasRoute(SystemToolsRoute::class) == true
    val selectedTab = when {
        currentDestination?.hasRoute(SignalRoute::class) == true -> TopLevelDestination.SIGNAL
        currentDestination?.hasRoute(DevicesRoute::class) == true -> TopLevelDestination.DEVICES
        currentDestination?.hasRoute(SettingsRoute::class) == true -> TopLevelDestination.SETTINGS
        else -> TopLevelDestination.HOME
    }

    val navigateToTab: (TopLevelDestination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val adaptiveLayoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
        currentWindowAdaptiveInfo()
    )
    val useFloatingBottomBar = !isAlignmentFullScreen && adaptiveLayoutType == NavigationSuiteType.NavigationBar
    val suiteLayoutType = if (isAlignmentFullScreen || useFloatingBottomBar) {
        NavigationSuiteType.None
    } else {
        adaptiveLayoutType
    }

    NavigationSuiteScaffold(
        layoutType = suiteLayoutType,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { dest ->
                item(
                    selected = selectedTab == dest,
                    onClick = { navigateToTab(dest) },
                    icon = { Icon(imageVector = dest.icon, contentDescription = stringResource(dest.labelRes)) },
                    label = { Text(stringResource(dest.labelRes)) }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = if (useFloatingBottomBar) {
                        Modifier
                            .navigationBarsPadding()
                            .padding(bottom = GlassNavBarHeight + 28.dp)
                    } else {
                        Modifier.navigationBarsPadding()
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (!isAlignmentFullScreen) {
                        HeaderBar(
                            connectionStatus = shellState.connectionStatus,
                            isPaused = shellState.isPaused,
                            pollInterval = shellState.pollIntervalSeconds,
                            routerHost = shellState.routerHost,
                            smsUnreadCount = shellState.smsUnreadCount,
                            onOpenSms = { navController.navigate(MessagesRoute) { launchSingleTop = true } },
                            onTogglePause = shellViewModel::togglePause,
                            onForceRefresh = shellViewModel::forceRefresh,
                            onSelectPollInterval = shellViewModel::setPollInterval,
                            onOpenReboot = shellViewModel::openRebootConfirm,
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(horizontal = spacing.lg, vertical = spacing.xs)
                        )

                        ConnectionStatusBanner(
                            visible = shellState.isAuthError || shellState.connectionStatus == ConnectionStatus.DISCONNECTED,
                            isAuthError = shellState.isAuthError,
                            routerHost = shellState.routerHost,
                            onRetry = shellViewModel::forceRefresh,
                            onOpenSettings = { navigateToTab(TopLevelDestination.SETTINGS) },
                            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs)
                        )
                    }

                    val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val listContentPadding = PaddingValues(
                        start = spacing.lg,
                        end = spacing.lg,
                        top = spacing.sm,
                        bottom = if (useFloatingBottomBar) {
                            GlassNavBarHeight + navBarBottomInset + 28.dp
                        } else {
                            navBarBottomInset + spacing.xl
                        }
                    )

                    SharedTransitionLayout(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = HomeRoute,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable<HomeRoute> {
                                val homeVm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
                                HomeScreen(
                                    viewModel = homeVm,
                                    contentPadding = listContentPadding,
                                    onOpenSignal = { navigateToTab(TopLevelDestination.SIGNAL) },
                                    onOpenDevices = { navigateToTab(TopLevelDestination.DEVICES) },
                                    onOpenSettings = { navigateToTab(TopLevelDestination.SETTINGS) },
                                    onOpenMessages = { navController.navigate(MessagesRoute) { launchSingleTop = true } },
                                    onOpenDataUsage = { navController.navigate(DataUsageRoute) { launchSingleTop = true } },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this
                                )
                            }
                            composable<SignalRoute> {
                                val signalVm: SignalViewModel = viewModel(factory = SignalViewModel.factory(container))
                                SignalScreen(
                                    viewModel = signalVm,
                                    contentPadding = listContentPadding,
                                    onOpenAlignment = { navController.navigate(AlignmentRoute) },
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    animatedVisibilityScope = this
                                )
                            }
                            composable<AlignmentRoute> {
                                val signalVm: SignalViewModel = viewModel(factory = SignalViewModel.factory(container))
                                AlignmentScreen(
                                    viewModel = signalVm,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable<DevicesRoute> {
                                val devicesVm: DevicesViewModel = viewModel(factory = DevicesViewModel.factory(container))
                                DevicesScreen(viewModel = devicesVm, contentPadding = listContentPadding)
                            }
                            composable<SettingsRoute> {
                                val settingsVm: SettingsHubViewModel = viewModel(factory = SettingsHubViewModel.factory(container))
                                SettingsHubScreen(
                                    viewModel = settingsVm,
                                    contentPadding = listContentPadding,
                                    onOpenMessages = { navController.navigate(MessagesRoute) { launchSingleTop = true } },
                                    onOpenDataUsage = { navController.navigate(DataUsageRoute) { launchSingleTop = true } },
                                    onOpenSecurity = { navController.navigate(SecurityRoute) { launchSingleTop = true } },
                                    onOpenSystemTools = { navController.navigate(SystemToolsRoute) { launchSingleTop = true } }
                                )
                            }
                            composable<MessagesRoute> {
                                val messagesVm: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(container))
                                MessagesScreen(
                                    viewModel = messagesVm,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable<DataUsageRoute> {
                                val usageVm: DataUsageViewModel = viewModel(factory = DataUsageViewModel.factory(container))
                                DataUsageScreen(
                                    viewModel = usageVm,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable<SecurityRoute> {
                                val securityVm: SecurityViewModel = viewModel(factory = SecurityViewModel.factory(container))
                                SecurityScreen(
                                    viewModel = securityVm,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable<SystemToolsRoute> {
                                val systemVm: SystemToolsViewModel = viewModel(
                                    factory = SystemToolsViewModel.factory(
                                        container = container,
                                        onForcePollRefresh = shellViewModel::forceRefresh
                                    )
                                )
                                SystemToolsScreen(
                                    viewModel = systemVm,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }

                if (useFloatingBottomBar) {
                    GlassNavBar(
                        selectedRoute = selectedTab.route,
                        onSelect = navigateToTab,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 12.dp)
                    )
                }
            }
        }
    }

    if (shellState.showRebootConfirm) {
        ConfirmSheet(
            title = stringResource(R.string.reboot_confirm_title),
            impactMessage = stringResource(R.string.reboot_confirm_impact),
            confirmLabel = stringResource(R.string.reboot_confirm_button),
            onConfirm = shellViewModel::confirmReboot,
            onDismiss = shellViewModel::dismissRebootConfirm,
            tier = ConfirmTier.DESTRUCTIVE,
            requiredConfirmationText = stringResource(R.string.reboot_confirm_token)
        )
    }
}

@Composable
private fun ConnectionStatusBanner(
    visible: Boolean,
    isAuthError: Boolean,
    routerHost: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = AirtelTheme.spacing
    AnimatedVisibility(visible = visible) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = AirtelTheme.shapes.tile,
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                Icon(
                    imageVector = if (isAuthError) Icons.Rounded.Lock else Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isAuthError) {
                            stringResource(R.string.auth_error_title)
                        } else {
                            stringResource(R.string.offline_banner_title, routerHost)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = if (isAuthError) {
                            stringResource(R.string.auth_error_subtitle)
                        } else {
                            stringResource(R.string.offline_banner_subtitle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                TextButton(
                    onClick = if (isAuthError) onOpenSettings else onRetry,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(
                        text = stringResource(if (isAuthError) R.string.nav_settings else R.string.action_retry),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
