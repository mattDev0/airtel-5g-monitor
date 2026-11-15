package com.airtel.monitor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.airtel.monitor.MainActivity
import com.airtel.monitor.R
import com.airtel.monitor.work.RouterMonitorScheduler

abstract class BaseRouterWidgetProvider(
    private val layoutResId: Int,
    private val expanded: Boolean
) : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) {
            RouterMonitorScheduler.enqueueImmediateWidgetRefresh(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val snapshot = RouterWidgetStore(context).read()
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, snapshot))
        }
    }

    fun buildRemoteViews(context: Context, snapshot: WidgetSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, layoutResId)

        views.setTextViewText(R.id.widget_network_type, snapshot.networkType)
        views.setTextViewText(R.id.widget_device_count, snapshot.deviceCount.toString())
        views.setTextViewText(
            R.id.widget_data_primary,
            if (snapshot.todayDataText != "--") snapshot.todayDataText else snapshot.monthlyDataText
        )
        views.setTextViewText(
            R.id.widget_status_line,
            if (snapshot.connected) {
                context.getString(R.string.widget_updated_fmt, snapshot.updatedLabel)
            } else {
                context.getString(R.string.widget_offline)
            }
        )

        if (expanded) {
            views.setTextViewText(R.id.widget_rsrp, snapshot.rsrpText)
            views.setTextViewText(R.id.widget_month_data, snapshot.monthlyDataText)
        }

        val barIds = intArrayOf(
            R.id.widget_bar_1,
            R.id.widget_bar_2,
            R.id.widget_bar_3,
            R.id.widget_bar_4,
            R.id.widget_bar_5
        )
        barIds.forEachIndexed { idx, viewId ->
            val active = snapshot.connected && (idx < snapshot.signalBars)
            views.setImageViewResource(
                viewId,
                if (active) R.drawable.widget_bar_active else R.drawable.widget_bar_inactive
            )
        }

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)

        val refreshIntent = PendingIntent.getBroadcast(
            context,
            if (expanded) 102 else 101,
            Intent(context, this::class.java).apply { action = ACTION_REFRESH_WIDGET },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshIntent)
        return views
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.airtel.monitor.action.REFRESH_WIDGET"
    }
}

class RouterWidgetCompactProvider : BaseRouterWidgetProvider(
    layoutResId = R.layout.widget_router_compact,
    expanded = false
)

class RouterWidgetExpandedProvider : BaseRouterWidgetProvider(
    layoutResId = R.layout.widget_router_expanded,
    expanded = true
)

object RouterWidgetUpdater {
    fun pushUpdate(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val snapshot = RouterWidgetStore(context).read()

        val compactProvider = RouterWidgetCompactProvider()
        val compactIds = manager.getAppWidgetIds(ComponentName(context, RouterWidgetCompactProvider::class.java))
        compactIds.forEach { id ->
            manager.updateAppWidget(id, compactProvider.buildRemoteViews(context, snapshot))
        }

        val expandedProvider = RouterWidgetExpandedProvider()
        val expandedIds = manager.getAppWidgetIds(ComponentName(context, RouterWidgetExpandedProvider::class.java))
        expandedIds.forEach { id ->
            manager.updateAppWidget(id, expandedProvider.buildRemoteViews(context, snapshot))
        }
    }
}
