package com.airtel.monitor.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.airtel.monitor.MainActivity
import com.airtel.monitor.R
import com.airtel.monitor.domain.MonitorAlert

object RouterNotificationHelper {

    const val CHANNEL_QUOTA = "airtel_router_quota"
    const val CHANNEL_SMS = "airtel_router_sms"
    const val CHANNEL_DEVICES = "airtel_router_devices"
    const val CHANNEL_CONNECTIVITY = "airtel_router_connectivity"

    private const val ID_QUOTA = 6001
    private const val ID_SMS = 6002
    private const val ID_DEVICES = 6003
    private const val ID_CONNECTIVITY = 6004

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_QUOTA,
                context.getString(R.string.notif_channel_quota_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_quota_desc)
            },
            NotificationChannel(
                CHANNEL_SMS,
                context.getString(R.string.notif_channel_sms_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_sms_desc)
            },
            NotificationChannel(
                CHANNEL_DEVICES,
                context.getString(R.string.notif_channel_devices_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_devices_desc)
            },
            NotificationChannel(
                CHANNEL_CONNECTIVITY,
                context.getString(R.string.notif_channel_conn_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_conn_desc)
            }
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun dispatchAlerts(context: Context, alerts: List<MonitorAlert>) {
        if (alerts.isEmpty()) return
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = NotificationManagerCompat.from(context)
        alerts.forEach { alert ->
            val (id, channelId, title, body) = when (alert) {
                is MonitorAlert.QuotaWarning -> QuotaSpec(
                    ID_QUOTA,
                    CHANNEL_QUOTA,
                    context.getString(R.string.notif_quota_title),
                    context.getString(R.string.notif_quota_body, alert.usedPercent, alert.thresholdPercent)
                )
                is MonitorAlert.NewSms -> QuotaSpec(
                    ID_SMS,
                    CHANNEL_SMS,
                    context.getString(R.string.notif_sms_title),
                    context.getString(R.string.notif_sms_body, alert.totalUnread)
                )
                is MonitorAlert.NewDevices -> QuotaSpec(
                    ID_DEVICES,
                    CHANNEL_DEVICES,
                    context.getString(R.string.notif_device_title),
                    context.getString(R.string.notif_device_body, alert.macs.size, alert.macs.first())
                )
                MonitorAlert.RouterUnreachable -> QuotaSpec(
                    ID_CONNECTIVITY,
                    CHANNEL_CONNECTIVITY,
                    context.getString(R.string.notif_unreachable_title),
                    context.getString(R.string.notif_unreachable_body)
                )
                MonitorAlert.RouterRecovered -> QuotaSpec(
                    ID_CONNECTIVITY,
                    CHANNEL_CONNECTIVITY,
                    context.getString(R.string.notif_recovered_title),
                    context.getString(R.string.notif_recovered_body)
                )
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()

            try {
                nm.notify(id, notification)
            } catch (_: SecurityException) {
                // Runtime notification permission denied between check and notify
            }
        }
    }

    private data class QuotaSpec(
        val id: Int,
        val channelId: String,
        val title: String,
        val body: String
    )
}
