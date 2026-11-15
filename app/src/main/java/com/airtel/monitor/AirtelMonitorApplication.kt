package com.airtel.monitor

import android.app.Application
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.notifications.RouterNotificationHelper
import com.airtel.monitor.work.RouterMonitorScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AirtelMonitorApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        RouterNotificationHelper.ensureChannels(this)
        appScope.launch {
            container.preferencesRepository.ensureInitialized()
            val prefs = container.preferencesRepository.preferencesFlow.value
            RouterMonitorScheduler.syncPeriodicMonitor(
                this@AirtelMonitorApplication,
                enabled = prefs.backgroundMonitorEnabled
            )
        }
    }
}
