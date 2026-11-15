package com.airtel.monitor.core.di

import android.app.Application
import com.airtel.monitor.core.network.OkHttpRouterTransport
import com.airtel.monitor.core.network.PollingCoordinator
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterTransport
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.util.OuiVendorLookup
import com.airtel.monitor.data.local.AliasRepository
import com.airtel.monitor.data.local.PreferencesRepository
import com.airtel.monitor.data.repository.DeviceRepository
import com.airtel.monitor.data.repository.LanRepository
import com.airtel.monitor.data.repository.MobileRepository
import com.airtel.monitor.data.repository.SignalRepository
import com.airtel.monitor.data.repository.SimRepository
import com.airtel.monitor.data.repository.StatusRepository
import com.airtel.monitor.data.repository.SystemRepository
import com.airtel.monitor.data.repository.WifiRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Hand-written dependency container scoped to Application lifetime. */
class AppContainer(application: Application) {
    private val _statusMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val statusMessages: SharedFlow<String> = _statusMessages.asSharedFlow()

    fun emitStatusMessage(message: String) {
        if (message.isNotBlank()) {
            _statusMessages.tryEmit(message)
        }
    }

    val preferencesRepository = PreferencesRepository(application)
    val aliasRepository = AliasRepository(application)
    val ouiVendorLookup = OuiVendorLookup.fromAsset(application)

    val transport: RouterTransport = OkHttpRouterTransport()
    val routerSession = RouterSession(preferencesRepository, transport)
    val settingsWriter = SettingsWriter(routerSession)

    val statusRepository = StatusRepository(routerSession, aliasRepository)
    val signalRepository = SignalRepository(routerSession, settingsWriter)
    val deviceRepository = DeviceRepository(statusRepository, aliasRepository, ouiVendorLookup)
    val wifiRepository = WifiRepository(routerSession, settingsWriter)
    val mobileRepository = MobileRepository(routerSession, settingsWriter, statusRepository)
    val simRepository = SimRepository(routerSession, settingsWriter)
    val lanRepository = LanRepository(routerSession, settingsWriter)
    val systemRepository = SystemRepository(routerSession)

    val pollingCoordinator = PollingCoordinator(
        pollIntervalProvider = { (preferencesRepository.pollIntervalSeconds * 1000L).toLong().coerceAtLeast(500L) },
        onNormalTick = { forceSlow -> statusRepository.pollFast(forceSlow = forceSlow) },
        onAlignmentTick = { statusRepository.pollAlignment() }
    )
}
