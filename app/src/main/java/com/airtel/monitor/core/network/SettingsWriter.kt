package com.airtel.monitor.core.network

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Specification for a single safe settings write:
 * read current object -> validate -> build SET payload -> POST with fresh token -> re-read & verify.
 */
data class WriteSpec<T>(
    val cmd: Int,
    val readCmd: Int = cmd,
    val readExtra: Map<String, Any> = emptyMap(),
    val preflight: (current: JSONObject) -> RouterResult<Unit>? = { null },
    val buildPayload: (current: JSONObject) -> Map<String, Any>,
    val verify: (readBack: JSONObject) -> Boolean,
    val parseResult: (readBack: JSONObject) -> T,
    val writeTimeoutSeconds: Long = 20L,
    val verifyRetries: Int = 1,
    val verifyDelayMs: Long = 0L,
    val allowEmptyWriteReply: Boolean = false
)

/**
 * Single write gateway enforcing read-merge-POST-verify without blind POST retries.
 */
class SettingsWriter(
    private val session: RouterSession,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun <T> write(spec: WriteSpec<T>): RouterResult<T> {
        val currentRes = session.execute(spec.readCmd, method = "GET", extra = spec.readExtra)
        val current = when (currentRes) {
            is RouterResult.Ok -> currentRes.data
            is RouterResult.NotLoggedIn -> return currentRes
            is RouterResult.LimitedAccess -> return currentRes
            is RouterResult.Unreachable -> return currentRes
            is RouterResult.BadResponse -> return currentRes
        }

        when (val preflightResult = spec.preflight(current)) {
            null, is RouterResult.Ok -> Unit
            is RouterResult.NotLoggedIn -> return preflightResult
            is RouterResult.LimitedAccess -> return preflightResult
            is RouterResult.Unreachable -> return preflightResult
            is RouterResult.BadResponse -> return preflightResult
        }

        val payload = spec.buildPayload(current).toMutableMap()
        val tokenRes = session.fetchWriteToken()
        val token = when (tokenRes) {
            is RouterResult.Ok -> tokenRes.data
            is RouterResult.NotLoggedIn -> return tokenRes
            is RouterResult.LimitedAccess -> return tokenRes
            is RouterResult.Unreachable -> return tokenRes
            is RouterResult.BadResponse -> return tokenRes
        }
        payload["token"] = token

        val postRes = session.execute(
            cmd = spec.cmd,
            method = "POST",
            extra = payload,
            timeoutSeconds = spec.writeTimeoutSeconds,
            allowEmptyReply = spec.allowEmptyWriteReply
        )
        val postJson = when (postRes) {
            is RouterResult.Ok -> postRes.data
            is RouterResult.NotLoggedIn -> return postRes
            is RouterResult.LimitedAccess -> return postRes
            is RouterResult.Unreachable -> return postRes
            is RouterResult.BadResponse -> return postRes
        }

        if (postJson.length() > 0 || !spec.allowEmptyWriteReply) {
            val msg = postJson.optString("message", "")
            val rejected = !postJson.optBoolean("success", false) && msg != "0" && msg != "success"
            if (rejected) {
                return RouterResult.BadResponse(
                    message = msg.ifEmpty { "Router rejected changes for cmd ${spec.cmd}" },
                    rawBody = postJson.toString()
                )
            }
        }

        var lastReadBack: JSONObject? = null
        var lastError: RouterResult<Nothing>? = null
        val checks = spec.verifyRetries.coerceAtLeast(1)

        for (attempt in 0 until checks) {
            if (spec.verifyDelayMs > 0L) {
                delayFn(spec.verifyDelayMs)
            }
            when (val readBackRes = session.execute(spec.readCmd, method = "GET", extra = spec.readExtra)) {
                is RouterResult.Ok -> {
                    lastReadBack = readBackRes.data
                    if (spec.verify(readBackRes.data)) {
                        return RouterResult.Ok(spec.parseResult(readBackRes.data))
                    }
                }
                is RouterResult.NotLoggedIn -> return readBackRes
                is RouterResult.LimitedAccess -> return readBackRes
                is RouterResult.Unreachable -> lastError = readBackRes
                is RouterResult.BadResponse -> lastError = readBackRes
            }
        }

        if (lastReadBack != null) {
            return RouterResult.BadResponse(
                message = "Router did not confirm the updated settings for cmd ${spec.cmd}",
                rawBody = lastReadBack.toString()
            )
        }
        return lastError ?: RouterResult.BadResponse(
            message = "Router did not answer verification read for cmd ${spec.cmd}",
            rawBody = postJson.toString()
        )
    }
}
