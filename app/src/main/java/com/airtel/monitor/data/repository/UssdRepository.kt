package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.Phase71Validators
import com.airtel.monitor.core.util.UssdDecodedMessage
import com.airtel.monitor.data.model.UssdSessionResult
import org.json.JSONObject

/**
 * Interactive carrier USSD gateway (`cmd 350`, `js/manage/ussd.js`).
 */
class UssdRepository(
    @Suppress("UNUSED_PARAMETER")
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    /** Sends a USSD dial string (`*...#`) or reply (`subcmd = "0"`). */
    suspend fun sendUssd(codeOrReply: String, isReply: Boolean = false): RouterResult<UssdSessionResult> {
        val err = Phase71Validators.validateUssdInput(codeOrReply, isReply = isReply)
        if (err != null) return RouterResult.BadResponse(err)
        val clean = codeOrReply.trim()
        return executeUssdWrite(
            subcmd = "0",
            ussdCode = clean,
            waitForReply = true
        )
    }

    /** Triggers built-in carrier shortcut `subcmd` (`"2"`, `"3"`, or `"4"`). */
    suspend fun sendPreset(subcmd: String): RouterResult<UssdSessionResult> {
        if (subcmd !in setOf("2", "3", "4")) {
            return RouterResult.BadResponse("Unsupported USSD preset ($subcmd)")
        }
        return executeUssdWrite(
            subcmd = subcmd,
            ussdCode = null,
            waitForReply = true
        )
    }

    /** Cancels an active interactive USSD menu (`subcmd = "1"`). */
    suspend fun cancelSession(): RouterResult<UssdSessionResult> =
        executeUssdWrite(
            subcmd = "1",
            ussdCode = null,
            waitForReply = false
        )

    private suspend fun executeUssdWrite(
        subcmd: String,
        ussdCode: String?,
        waitForReply: Boolean
    ): RouterResult<UssdSessionResult> {
        var decodedOutcome: RouterResult<UssdSessionResult>? = null

        val writeRes = writer.write(
            WriteSpec(
                cmd = 350,
                readCmd = 350,
                buildPayload = {
                    buildMap {
                        put("subcmd", subcmd)
                        if (ussdCode != null) put("ussd_code", ussdCode)
                    }
                },
                verifyPost = { postJson ->
                    when (val msg = postJson.optString("message", "")) {
                        "507" -> RouterResult.BadResponse("USSD request timed out")
                        "0", "" -> if (msg == "0" || postJson.optBoolean("success", false)) {
                            null
                        } else {
                            RouterResult.BadResponse("USSD request failed", postJson.toString())
                        }
                        else -> RouterResult.BadResponse("USSD request failed ($msg)", postJson.toString())
                    }
                },
                verify = { readBack ->
                    if (!waitForReply) {
                        decodedOutcome = RouterResult.Ok(UssdSessionResult(cancelled = true))
                        true
                    } else {
                        decodedOutcome = interpretReadBack(readBack)
                        true
                    }
                },
                parseResult = {
                    UssdSessionResult(cancelled = !waitForReply)
                },
                writeTimeoutSeconds = 20L,
                verifyRetries = 1,
                verifyDelayMs = if (waitForReply) 3000L else 0L
            )
        )

        return decodedOutcome ?: writeRes
    }

    private fun interpretReadBack(readBack: JSONObject): RouterResult<UssdSessionResult> {
        val rawMsg = readBack.optString("message", "")
        val needReply = readBack.optString("need_reply", "0") == "1"
        return when (val decoded = Phase71Validators.decodeUssdResponse(rawMsg)) {
            is UssdDecodedMessage.Empty ->
                RouterResult.BadResponse("No response returned from the network")
            is UssdDecodedMessage.Timeout ->
                RouterResult.BadResponse("USSD request timed out")
            is UssdDecodedMessage.CarrierError ->
                RouterResult.BadResponse("Carrier rejected the USSD request (506)")
            is UssdDecodedMessage.Text ->
                RouterResult.Ok(UssdSessionResult(text = decoded.text, needReply = needReply))
        }
    }
}
