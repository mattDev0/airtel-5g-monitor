package com.airtel.monitor.core.di

import android.app.Application
import com.airtel.monitor.core.network.OkHttpRouterTransport
import com.airtel.monitor.core.network.PollingCoordinator
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterTransport
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.util.OuiVendorLookup
import com.airtel.monitor.data.local.AliasRepository
import com.airtel.monitor.data.local.PreferencesRepository
import com.airtel.monitor.data.local.SharedPreferencesDailyUsageStore
import com.airtel.monitor.data.local.SharedPreferencesMonitorStateStore
import com.airtel.monitor.data.remote.SpeedTestRunner
import com.airtel.monitor.data.repository.DeviceRepository
import com.airtel.monitor.data.repository.FeatureFlagRepository
import com.airtel.monitor.data.repository.LanRepository
import com.airtel.monitor.data.repository.MobileRepository
import com.airtel.monitor.data.repository.ParentalControlRepository
import com.airtel.monitor.data.repository.SecurityRepository
import com.airtel.monitor.data.repository.SignalRepository
import com.airtel.monitor.data.repository.SimRepository
import com.airtel.monitor.data.repository.SmsRepository
import com.airtel.monitor.data.repository.StatusRepository
import com.airtel.monitor.data.repository.SystemRepository
import com.airtel.monitor.data.repository.UssdRepository
import com.airtel.monitor.data.repository.WifiRepository
import com.airtel.monitor.widget.RouterWidgetStore
import com.airtel.monitor.widget.RouterWidgetUpdater
import com.airtel.monitor.widget.WidgetFormatter
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
    val dailyUsageStore = SharedPreferencesDailyUsageStore(application)
    val monitorStateStore = SharedPreferencesMonitorStateStore(application)
    val widgetStore = RouterWidgetStore(application)
    val ouiVendorLookup = OuiVendorLookup.fromAsset(application)

    val transport: RouterTransport = OkHttpRouterTransport()
    val routerSession = RouterSession(
        settings = preferencesRepository,
        transport = transport,
        onLoginSuccess = {
            val prefs = preferencesRepository.preferencesFlow.value
            if (!prefs.onboardingCompleted && prefs.hasSavedPassword) {
                preferencesRepository.updateSettings(onboardingCompleted = true)
            }
        }
    )
    val settingsWriter = SettingsWriter(routerSession)
    val featureFlagRepository = FeatureFlagRepository(routerSession)

    val statusRepository = StatusRepository(
        session = routerSession,
        aliasLookup = aliasRepository,
        dailyUsageStore = dailyUsageStore,
        featureFlagRepository = featureFlagRepository
    )
    val signalRepository = SignalRepository(routerSession, settingsWriter)
    val deviceRepository = DeviceRepository(statusRepository, aliasRepository, ouiVendorLookup)
    val wifiRepository = WifiRepository(routerSession, settingsWriter)
    val mobileRepository = MobileRepository(routerSession, settingsWriter, statusRepository)
    val simRepository = SimRepository(routerSession, settingsWriter)
    val lanRepository = LanRepository(routerSession, settingsWriter)
    val smsRepository = SmsRepository(
        session = routerSession,
        writer = settingsWriter,
        onUnreadCountSync = { count -> statusRepository.updateSmsUnreadCount(count) }
    )
    val securityRepository = SecurityRepository(routerSession, settingsWriter)
    val parentalControlRepository = ParentalControlRepository(routerSession, settingsWriter)
    val systemRepository = SystemRepository(routerSession, settingsWriter)
    val ussdRepository = UssdRepository(routerSession, settingsWriter)
    val speedTestRunner = SpeedTestRunner()

    val pollingCoordinator = PollingCoordinator(
        pollIntervalProvider = { (preferencesRepository.pollIntervalSeconds * 1000L).toLong().coerceAtLeast(500L) },
        onNormalTick = { forceSlow ->
            val result = statusRepository.pollFast(forceSlow = forceSlow)
            if (result is RouterResult.Ok) {
                widgetStore.save(
                    WidgetFormatter.buildSnapshot(
                        state = result.data,
                        dailyEntries = dailyUsageStore.history.value
                    )
                )
                RouterWidgetUpdater.pushUpdate(application)
            }
            result
        },
        onAlignmentTick = { statusRepository.pollAlignment() }
    )
}
