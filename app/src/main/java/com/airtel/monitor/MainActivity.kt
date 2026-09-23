package com.airtel.monitor

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.airtel.monitor.ui.screens.MonitorDashboardScreen
import com.airtel.monitor.ui.theme.AirtelMonitorTheme
import com.airtel.monitor.ui.viewmodel.MonitorViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels()

    // Android 16+/17 gate LAN access (192.168.1.1) behind this runtime permission.
    private val localNetPermission = "android.permission.ACCESS_LOCAL_NETWORK"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.forceRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Request local-network permission up front on Android 16+ (SDK 36/37)
        if (ContextCompat.checkSelfPermission(this, localNetPermission) != PackageManager.PERMISSION_GRANTED) {
            try {
                permissionLauncher.launch(localNetPermission)
            } catch (e: Exception) {
                // If the permission is not defined on earlier Android versions, ignore
            }
        }

        setContent {
            AirtelMonitorTheme {
                MonitorDashboardScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Resume polling when the screen becomes visible and active
        viewModel.startPolling()
    }

    override fun onStop() {
        // Stop polling completely when the screen is off or app is in the background
        viewModel.stopPolling()
        super.onStop()
    }
}
