package com.airtel.monitor.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

data class WifiGatewayInfo(
    val isWifiConnected: Boolean,
    val gatewayIp: String?
)

/**
 * Inspects Android ConnectivityManager state to locate the active Wi-Fi default gateway
 * and verify whether background checks are running on the router's Wi-Fi network.
 */
object GatewayDetector {

    fun detect(context: Context): WifiGatewayInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiGatewayInfo(isWifiConnected = false, gatewayIp = null)
        val activeNetwork = cm.activeNetwork ?: return WifiGatewayInfo(isWifiConnected = false, gatewayIp = null)
        val caps = cm.getNetworkCapabilities(activeNetwork)
            ?: return WifiGatewayInfo(isWifiConnected = false, gatewayIp = null)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (!isWifi) {
            return WifiGatewayInfo(isWifiConnected = false, gatewayIp = null)
        }
        val linkProps = cm.getLinkProperties(activeNetwork)
            ?: return WifiGatewayInfo(isWifiConnected = true, gatewayIp = null)
        val routeGateways = linkProps.routes
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway }
        val dhcpServer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            linkProps.dhcpServerAddress
        } else {
            null
        }
        return WifiGatewayInfo(
            isWifiConnected = true,
            gatewayIp = extractGatewayIpv4(routeGateways, dhcpServer)
        )
    }

    fun extractGatewayIpv4(
        routeGateways: List<InetAddress>,
        dhcpServer: InetAddress?
    ): String? {
        val candidates = routeGateways + listOfNotNull(dhcpServer)
        return candidates
            .filterIsInstance<Inet4Address>()
            .mapNotNull { addr -> addr.hostAddress?.trim() }
            .firstOrNull { ip ->
                ip.isNotEmpty() && ip != "0.0.0.0" && !ip.startsWith("127.")
            }
    }

    fun isOnExpectedRouterWifi(
        wifiConnected: Boolean,
        gatewayIp: String?,
        configuredHost: String
    ): Boolean {
        if (!wifiConnected) return false
        val cleanHost = configuredHost.trim()
        if (gatewayIp.isNullOrBlank() || cleanHost.isEmpty()) return true
        if (gatewayIp == cleanHost) return true
        val gwPrefix = ipv4Prefix24(gatewayIp)
        val hostPrefix = ipv4Prefix24(cleanHost)
        if (gwPrefix != null && hostPrefix != null) {
            return gwPrefix == hostPrefix
        }
        return true
    }

    private fun ipv4Prefix24(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() !in 0..255 }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }
}
