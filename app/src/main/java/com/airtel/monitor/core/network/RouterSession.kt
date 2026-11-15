package com.airtel.monitor.core.network

import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Owns authentication, session lifecycle, token refresh and retry policy for the router CGI.
 * Reads retry once on transport failure or expired session; writes never retry blindly on I/O failure.
 */
class RouterSession(
    private val settings: RouterSettings,
    private val transport: RouterTransport,
    private val onLoginSuccess: () -> Unit = {}
) {
    private val lock = Any()
    private var sessionId: String? = null
    private var accountLevel: String? = null

    private val url: String
        get() = "http://${settings.routerHost}/cgi-bin/http.cgi"

    fun resetSession() {
        synchronized(lock) {
            sessionId = null
            accountLevel = null
        }
    }

    fun currentSessionId(): String? = synchronized(lock) { sessionId }

    fun currentAccountLevel(): String? = synchronized(lock) { accountLevel }

    fun login(): RouterResult<Unit> {
        synchronized(lock) {
            return loginLocked()
        }
    }

    fun fetchWriteToken(): RouterResult<String> {
        return when (val res = execute(cmd = 233, method = "GET")) {
            is RouterResult.Ok -> {
                val token = res.data.optString("token", "")
                if (token.isBlank()) {
                    RouterResult.BadResponse("Router did not return a write token", res.data.toString())
                } else {
                    RouterResult.Ok(token)
                }
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }

    fun execute(
        cmd: Int,
        method: String = "GET",
        extra: Map<String, Any> = emptyMap(),
        timeoutSeconds: Long = 4,
        allowEmptyReply: Boolean = false
    ): RouterResult<JSONObject> {
        synchronized(lock) {
            if (sessionId == null) {
                when (val loginRes = loginLocked()) {
                    is RouterResult.Ok -> Unit
                    is RouterResult.NotLoggedIn -> return loginRes
                    is RouterResult.LimitedAccess -> return loginRes
                    is RouterResult.Unreachable -> return loginRes
                    is RouterResult.BadResponse -> return loginRes
                }
            }
            val payload = buildPayload(cmd, method, extra)
            val isWrite = method == "POST"

            var reply = try {
                transport.post(url, payload.toString(), timeoutSeconds)
            } catch (e: IOException) {
                if (isWrite) {
                    return RouterResult.Unreachable("Write request failed before confirmation", e)
                }
                try {
                    transport.post(url, payload.toString(), timeoutSeconds)
                } catch (e2: IOException) {
                    return RouterResult.Unreachable("Cannot reach router at ${settings.routerHost}", e2)
                }
            } catch (e: Exception) {
                return RouterResult.Unreachable("Router communication error: ${e.message}", e)
            }

            if (reply.isBlank() && allowEmptyReply) {
                return RouterResult.Ok(JSONObject())
            }

            var json = parseJsonOrNull(reply)
                ?: return RouterResult.BadResponse("Invalid JSON response for cmd $cmd", reply)

            val msg = json.optString("message", "")
            if (msg == "LIMITED_ACCESS") {
                return RouterResult.LimitedAccess(cmd, msg)
            }

            val sessionExpired = msg in SESSION_EXPIRED_MESSAGES
            if (sessionExpired || (!isWrite && !json.optBoolean("success", true))) {
                when (val relogin = loginLocked()) {
                    is RouterResult.Ok -> Unit
                    is RouterResult.NotLoggedIn -> return relogin
                    is RouterResult.LimitedAccess -> return relogin
                    is RouterResult.Unreachable -> return relogin
                    is RouterResult.BadResponse -> return relogin
                }

                payload.put("sessionId", sessionId ?: "")
                if (payload.has("token")) {
                    val freshToken = fetchTokenLocked()
                    if (freshToken != null) {
                        payload.put("token", freshToken)
                    }
                }
                reply = try {
                    transport.post(url, payload.toString(), timeoutSeconds)
                } catch (e: IOException) {
                    return RouterResult.Unreachable("Router unreachable after re-login", e)
                } catch (e: Exception) {
                    return RouterResult.Unreachable("Router communication error: ${e.message}", e)
                }
                if (reply.isBlank() && allowEmptyReply) {
                    return RouterResult.Ok(JSONObject())
                }
                json = parseJsonOrNull(reply)
                    ?: return RouterResult.BadResponse("Invalid JSON response for cmd $cmd", reply)

                val retryMsg = json.optString("message", "")
                if (retryMsg == "LIMITED_ACCESS") {
                    return RouterResult.LimitedAccess(cmd, retryMsg)
                }
                if (retryMsg in SESSION_EXPIRED_MESSAGES) {
                    return RouterResult.NotLoggedIn(retryMsg)
                }
            }

            if (json.has("error") && !json.optBoolean("success", false)) {
                return RouterResult.BadResponse(json.optString("error", "Router HTTP error"), reply)
            }

            return RouterResult.Ok(json)
        }
    }

    fun executeRaw(
        cmd: Int,
        method: String = "GET",
        extra: Map<String, Any> = emptyMap(),
        timeoutSeconds: Long = 5,
        retryOnNetworkFailure: Boolean = false
    ): RouterResult<String> {
        synchronized(lock) {
            if (sessionId == null) {
                when (val loginRes = loginLocked()) {
                    is RouterResult.Ok -> Unit
                    is RouterResult.NotLoggedIn -> return loginRes
                    is RouterResult.LimitedAccess -> return loginRes
                    is RouterResult.Unreachable -> return loginRes
                    is RouterResult.BadResponse -> return loginRes
                }
            }
            val payload = buildPayload(cmd, method, extra)
            var reply = try {
                transport.post(url, payload.toString(), timeoutSeconds)
            } catch (e: IOException) {
                if (!retryOnNetworkFailure || method == "POST") {
                    return RouterResult.Unreachable("Cannot reach router at ${settings.routerHost}", e)
                }
                try {
                    transport.post(url, payload.toString(), timeoutSeconds)
                } catch (e2: IOException) {
                    return RouterResult.Unreachable("Cannot reach router at ${settings.routerHost}", e2)
                }
            } catch (e: Exception) {
                return RouterResult.Unreachable("Router communication error: ${e.message}", e)
            }

            if (reply.contains("\"message\":\"LIMITED_ACCESS\"")) {
                return RouterResult.LimitedAccess(cmd, "LIMITED_ACCESS")
            }

            if (reply.contains("\"message\":\"NO_AUTH\"") || reply.contains("\"message\":\"LOGIN_TIMEOUT\"")) {
                when (val relogin = loginLocked()) {
                    is RouterResult.Ok -> Unit
                    is RouterResult.NotLoggedIn -> return relogin
                    is RouterResult.LimitedAccess -> return relogin
                    is RouterResult.Unreachable -> return relogin
                    is RouterResult.BadResponse -> return relogin
                }
                payload.put("sessionId", sessionId ?: "")
                if (payload.has("token")) {
                    fetchTokenLocked()?.let { payload.put("token", it) }
                }
                reply = try {
                    transport.post(url, payload.toString(), timeoutSeconds)
                } catch (e: IOException) {
                    return RouterResult.Unreachable("Router unreachable after re-login", e)
                } catch (e: Exception) {
                    return RouterResult.Unreachable("Router communication error: ${e.message}", e)
                }
            }
            return RouterResult.Ok(reply)
        }
    }

    private fun loginLocked(): RouterResult<Unit> {
        val tokenPayload = JSONObject().apply {
            put("cmd", 232)
            put("method", "GET")
            put("sessionId", "")
        }
        val tokenReply = try {
            transport.post(url, tokenPayload.toString(), 4)
        } catch (e: IOException) {
            return RouterResult.Unreachable("Cannot reach router at ${settings.routerHost}", e)
        } catch (e: Exception) {
            return RouterResult.Unreachable("Cannot reach router at ${settings.routerHost}: ${e.message}", e)
        }

        val tokenJson = parseJsonOrNull(tokenReply)
            ?: return RouterResult.BadResponse("Invalid login challenge response", tokenReply)
        if (tokenJson.has("error") && !tokenJson.optBoolean("success", false)) {
            return RouterResult.BadResponse(tokenJson.optString("error", "HTTP error during login"), tokenReply)
        }
        val challenge = tokenJson.optString("token", "")
        if (challenge.isEmpty()) {
            return RouterResult.BadResponse("Missing challenge token in cmd 232 response", tokenReply)
        }

        val pwdHash = sha256(challenge + settings.password)
        val newSessionId = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")

        val loginPayload = JSONObject().apply {
            put("cmd", 100)
            put("method", "POST")
            put("sessionId", newSessionId)
            put("username", settings.username)
            put("passwd", pwdHash)
            put("isAutoUpgrade", "0")
            put("subcmd", 0)
        }
        val loginReply = try {
            transport.post(url, loginPayload.toString(), 4)
        } catch (e: IOException) {
            return RouterResult.Unreachable("Connection dropped during login", e)
        } catch (e: Exception) {
            return RouterResult.Unreachable("Login failed: ${e.message}", e)
        }

        val loginJson = parseJsonOrNull(loginReply)
            ?: return RouterResult.BadResponse("Invalid login response", loginReply)

        val msg = loginJson.optString("message", "")
        if (msg == "LIMITED_ACCESS") {
            return RouterResult.LimitedAccess(100, msg)
        }

        val success = loginJson.optBoolean("success") ||
            msg == "success" ||
            loginJson.has("sessionId")

        return if (success) {
            val sid = loginJson.optString("sessionId")
            sessionId = sid.ifEmpty { newSessionId }
            accountLevel = loginJson.optString("level", "").ifBlank { null }
            onLoginSuccess()
            RouterResult.Ok(Unit)
        } else {
            sessionId = null
            accountLevel = null
            RouterResult.NotLoggedIn(msg.ifEmpty { "Incorrect router username or password" })
        }
    }

    private fun fetchTokenLocked(): String? {
        val payload = buildPayload(233, "GET", emptyMap())
        val reply = try {
            transport.post(url, payload.toString(), 4)
        } catch (_: Exception) {
            return null
        }
        return parseJsonOrNull(reply)?.optString("token", "")?.takeIf { it.isNotEmpty() }
    }

    private fun buildPayload(cmd: Int, method: String, extra: Map<String, Any>): JSONObject =
        JSONObject().apply {
            put("cmd", cmd)
            put("method", method)
            put("sessionId", sessionId ?: "")
            extra.forEach { (k, v) -> put(k, v) }
        }

    private fun parseJsonOrNull(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (_: Exception) {
        null
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val SESSION_EXPIRED_MESSAGES = setOf("NO_AUTH", "LOGIN_TIMEOUT")
    }
}
