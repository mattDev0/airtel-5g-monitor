package com.airtel.monitor

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.ui.navigation.AppShell
import com.airtel.monitor.ui.navigation.ShellViewModel
import com.airtel.monitor.ui.theme.AirtelMonitorTheme

class MainActivity : ComponentActivity() {

    private val appContainer by lazy { (application as AirtelMonitorApplication).container }
    private val shellViewModel: ShellViewModel by viewModels { ShellViewModel.factory(appContainer) }

    // Android 16+/17 gate LAN access (192.168.1.1) behind this runtime permission.
    private val localNetPermission = "android.permission.ACCESS_LOCAL_NETWORK"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            shellViewModel.forceRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Request local-network permission up front on Android 16+ (SDK 36/37)
        if (ContextCompat.checkSelfPermission(this, localNetPermission) != PackageManager.PERMISSION_GRANTED) {
            try {
                permissionLauncher.launch(localNetPermission)
            } catch (_: Exception) {
                // Permission is not defined on earlier Android versions
            }
        }

        setContent {
            val shellState by shellViewModel.uiState.collectAsStateWithLifecycle()
            AirtelMonitorTheme(themePreference = shellState.themePreference) {
                AppShell(
                    container = appContainer,
                    shellViewModel = shellViewModel
                )
            }
        }
    }
}
