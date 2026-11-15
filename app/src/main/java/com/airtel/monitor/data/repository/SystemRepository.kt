package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession

class SystemRepository(
    private val session: RouterSession
) {
    fun reboot(): RouterResult<Unit> {
        val res = session.execute(6, method = "POST", extra = mapOf("rebootType" to 1))
        return when (res) {
            is RouterResult.Ok -> {
                val json = res.data
                if (json.optBoolean("success", false) || json.optString("message") == "success") {
                    RouterResult.Ok(Unit)
                } else {
                    RouterResult.BadResponse(
                        json.optString("message", "Reboot rejected by router"),
                        json.toString()
                    )
                }
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    fun startPing(target: String, pingTimes: Int = 4): RouterResult<Unit> {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) {
            return RouterResult.BadResponse("Target IP or hostname is required")
        }
        val res = session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf(
                "subcmd" to 0,
                "pingTimes" to pingTimes,
                "url" to cleanTarget,
                "wan_index" to ""
            )
        )
        return when (res) {
            is RouterResult.Ok -> {
                if (res.data.optBoolean("success", false)) RouterResult.Ok(Unit)
                else RouterResult.BadResponse(res.data.optString("message", "Failed to start ping"), res.data.toString())
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    fun getPingOutput(): String {
        val raw = session.executeRaw(
            cmd = 204,
            method = "GET",
            extra = mapOf("url" to "/tmp/tzwww/pingrt", "subcmd" to 168)
        ).getOrNull().orEmpty()
        if (raw.trim().startsWith("{") && raw.contains("\"success\"")) return ""
        return raw.trim()
    }

    fun stopPing() {
        session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf("subcmd" to 0, "pingTimes" to 0, "url" to "", "wan_index" to "")
        )
    }

    fun startTrace(target: String): RouterResult<Unit> {
        val cleanTarget = target.trim()
        if (cleanTarget.isEmpty()) {
            return RouterResult.BadResponse("Target IP or hostname is required")
        }
        val res = session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf(
                "subcmd" to 2,
                "stopped" to "0",
                "port" to -1,
                "url" to cleanTarget,
                "wan_index" to ""
            )
        )
        return when (res) {
            is RouterResult.Ok -> {
                if (res.data.optBoolean("success", false)) RouterResult.Ok(Unit)
                else RouterResult.BadResponse(res.data.optString("message", "Failed to start traceroute"), res.data.toString())
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    fun isTraceRunning(): Boolean {
        val res = session.execute(cmd = 168, method = "GET", extra = mapOf("subcmd" to 2)).getOrNull() ?: return false
        return res.optString("message", "0") == "1"
    }

    fun getTraceOutput(): String {
        val raw = session.executeRaw(
            cmd = 204,
            method = "GET",
            extra = mapOf("url" to "/tmp/tzwww/tracepathrt", "subcmd" to 168)
        ).getOrNull().orEmpty()
        if (raw.trim().startsWith("{") && raw.contains("\"success\"")) return ""
        return raw.trim()
    }

    fun stopTrace() {
        session.execute(
            cmd = 168,
            method = "POST",
            extra = mapOf("subcmd" to 2, "stopped" to "1", "port" to -1, "url" to "", "wan_index" to "")
        )
    }
}
