package com.airtel.monitor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.airtel.monitor.AirtelMonitorApplication
import com.airtel.monitor.core.network.GatewayDetector
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.notifications.RouterNotificationHelper
import com.airtel.monitor.domain.MonitorAlertConfig
import com.airtel.monitor.domain.MonitorAlertEvaluator
import com.airtel.monitor.domain.MonitorObservation
import com.airtel.monitor.widget.RouterWidgetUpdater
import com.airtel.monitor.widget.WidgetFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class RouterMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? AirtelMonitorApplication ?: return@withContext Result.success()
        val container = app.container
        container.preferencesRepository.ensureInitialized()
        val prefs = container.preferencesRepository.preferencesFlow.value
        val isImmediateRefresh = inputData.getBoolean(KEY_IMMEDIATE_WIDGET_REFRESH, false)

        if (!prefs.backgroundMonitorEnabled && !isImmediateRefresh) {
            return@withContext Result.success()
        }

        val wifiInfo = GatewayDetector.detect(applicationContext)
        val onRouterWifi = GatewayDetector.isOnExpectedRouterWifi(
            wifiConnected = wifiInfo.isWifiConnected,
            gatewayIp = wifiInfo.gatewayIp,
            configuredHost = prefs.routerHost
        )

        // Never count unreachable failures or hit LAN router when not connected to the router's Wi-Fi.
        if (!onRouterWifi) {
            if (isImmediateRefresh) {
                container.widgetStore.markDisconnected()
                RouterWidgetUpdater.pushUpdate(applicationContext)
            }
            return@withContext Result.success()
        }

        val pollResult = container.statusRepository.pollFast(forceSlow = true)
        val observation = when (pollResult) {
            is RouterResult.Ok -> {
                val state = pollResult.data
                val snapshot = WidgetFormatter.buildSnapshot(
                    state = state,
                    dailyEntries = container.dailyUsageStore.history.value
                )
                container.widgetStore.save(snapshot)
                RouterWidgetUpdater.pushUpdate(applicationContext)

                val usedPercent = state.monthlyUsage.progressFraction?.let { (it * 100f).toInt() }
                val activeMacs = state.devices
                    .filter { !it.isRouter && it.isOnline }
                    .map { it.mac }
                    .toSet()

                MonitorObservation.PollSuccess(
                    usageUsedPercent = usedPercent,
                    usageWarnPercent = state.monthlyUsage.warnPercentage,
                    cycleKey = computeCycleKey(state.monthlyUsage.cycleStartDay),
                    smsUnreadCount = container.statusRepository.smsUnreadCount.value,
                    activeDeviceMacs = activeMacs
                )
            }
            is RouterResult.Unreachable -> {
                container.widgetStore.markDisconnected()
                RouterWidgetUpdater.pushUpdate(applicationContext)
                MonitorObservation.PollFailure
            }
            else -> {
                MonitorObservation.PollFailure
            }
        }

        if (prefs.backgroundMonitorEnabled) {
            val prevState = container.monitorStateStore.read()
            val config = MonitorAlertConfig(
                notifyQuotaWarning = prefs.notifyQuotaWarning,
                notifyNewSms = prefs.notifyNewSms,
                notifyNewDevice = prefs.notifyNewDevice,
                notifyUnreachable = prefs.notifyUnreachable
            )
            val eval = MonitorAlertEvaluator.evaluate(prevState, config, observation)
            container.monitorStateStore.write(eval.nextState)
            RouterNotificationHelper.dispatchAlerts(applicationContext, eval.alerts)
        }

        Result.success()
    }

    private fun computeCycleKey(cycleStartDay: Int?): String {
        val cal = Calendar.getInstance()
        val resetDay = (cycleStartDay ?: 1).coerceIn(1, 28)
        if (cal.get(Calendar.DAY_OF_MONTH) < resetDay) {
            cal.add(Calendar.MONTH, -1)
        }
        cal.set(Calendar.DAY_OF_MONTH, resetDay)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }

    companion object {
        const val KEY_IMMEDIATE_WIDGET_REFRESH = "immediate_widget_refresh"
    }
}
