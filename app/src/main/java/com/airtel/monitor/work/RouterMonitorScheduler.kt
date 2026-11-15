package com.airtel.monitor.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object RouterMonitorScheduler {
    const val PERIODIC_WORK_NAME = "airtel_router_periodic_monitor"
    const val WIDGET_REFRESH_WORK_NAME = "airtel_router_widget_refresh"

    fun syncPeriodicMonitor(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RouterMonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueImmediateWidgetRefresh(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        val request = OneTimeWorkRequestBuilder<RouterMonitorWorker>()
            .setInputData(workDataOf(RouterMonitorWorker.KEY_IMMEDIATE_WIDGET_REFRESH to true))
            .build()
        wm.enqueueUniqueWork(
            WIDGET_REFRESH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
