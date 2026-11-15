package com.airtel.monitor

import android.app.Application
import com.airtel.monitor.core.di.AppContainer

class AirtelMonitorApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
