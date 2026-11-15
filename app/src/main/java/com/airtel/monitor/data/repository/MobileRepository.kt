package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.data.dto.NetworkModeSnapshot
import com.airtel.monitor.data.dto.parseNetworkMode218
import org.json.JSONObject

class MobileRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter,
    private val statusRepository: StatusRepository? = null
) {
    suspend fun setMobileData(on: Boolean): RouterResult<String> =
        setNetworkSwitch(cmd = 222, key = "dialMode", on = on, label = "Mobile data")

    suspend fun setFlightMode(on: Boolean): RouterResult<String> =
        setNetworkSwitch(cmd = 226, key = "flightMode", on = on, label = "Flight mode")

    private suspend fun setNetworkSwitch(cmd: Int, key: String, on: Boolean, label: String): RouterResult<String> {
        val value = if (on) "1" else "0"
        val state = if (on) "on" else "off"
        return writer.write(
            WriteSpec(
                cmd = cmd,
                readCmd = 218,
                buildPayload = { mapOf(key to value) },
                verify = { readBack ->
                    if (readBack.has("flightMode")) {
                        statusRepository?.updateCachedNetMode218(readBack)
                    }
                    readBack.optString(key) == value
                },
                parseResult = { "$label $state saved and confirmed by router" },
                writeTimeoutSeconds = 20L,
                verifyRetries = 8,
                verifyDelayMs = 2000L
            )
        )
    }

    /**
     * Makes the modem search for the mobile network again (cmd 288).
     * Never retried on I/O failure so it can never trigger a second search.
     */
    fun rescanNetwork(): RouterResult<String> {
        val replyRes = session.executeRaw(
            cmd = 288,
            method = "GET",
            timeoutSeconds = 20,
            retryOnNetworkFailure = false
        )
        val reply = when (replyRes) {
            is RouterResult.Ok -> replyRes.data
            is RouterResult.NotLoggedIn -> return replyRes
            is RouterResult.LimitedAccess -> return replyRes
            is RouterResult.Unreachable -> return replyRes
            is RouterResult.BadResponse -> return replyRes
        }
        if (reply.isBlank()) {
            return RouterResult.BadResponse("No answer from the router", reply)
        }
        val json = runCatching { JSONObject(reply) }.getOrElse { JSONObject() }
        return if (json.optString("message") == "0" || json.optBoolean("success", false)) {
            RouterResult.Ok("Searching for the network. The connection is back in about a minute.")
        } else {
            RouterResult.BadResponse(
                json.optString("message", "").ifEmpty { "Router rejected the network search" },
                reply
            )
        }
    }

    fun readNetworkMode(): RouterResult<NetworkModeSnapshot> {
        return when (val res = session.execute(218)) {
            is RouterResult.Ok -> RouterResult.Ok(parseNetworkMode218(res.data))
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }
}
