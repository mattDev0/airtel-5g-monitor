package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.WifiAndLanValidators
import com.airtel.monitor.data.model.SimPinState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Reads SIM PIN/PUK state (`cmd 134` + `cmd 113`) and executes verified PIN actions (`cmd 51`).
 */
class SimRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    private val _state = MutableStateFlow<SimPinState?>(null)
    val state: StateFlow<SimPinState?> = _state.asStateFlow()

    fun loadSimStatus(): RouterResult<SimPinState> {
        val sysRes = session.execute(113, "GET", timeoutSeconds = 5L)
        val sysJson = (sysRes as? RouterResult.Ok)?.data
        val simInsertedFromSys = sysJson?.let {
            val status = it.optString("sim_status", "")
            status.isEmpty() || status != "0"
        } ?: true
        val cardType = sysJson?.optString("current_card_type", "").orEmpty()

        if (!simInsertedFromSys) {
            val emptyState = SimPinState(
                simInserted = false,
                cardType = cardType
            )
            _state.value = emptyState
            return RouterResult.Ok(emptyState)
        }

        val pinRes = session.execute(134, "GET", timeoutSeconds = 5L)
        if (pinRes !is RouterResult.Ok) {
            return when (pinRes) {
                is RouterResult.NotLoggedIn -> pinRes
                is RouterResult.LimitedAccess -> pinRes
                is RouterResult.Unreachable -> pinRes
                is RouterResult.BadResponse -> pinRes
                is RouterResult.Ok -> error("unreachable")
            }
        }

        val parsed = parseSimPinState(pinRes.data, simInserted = true, cardType = cardType)
        _state.value = parsed
        return RouterResult.Ok(parsed)
    }

    suspend fun setPinLockEnabled(enable: Boolean, pin: String): RouterResult<SimPinState> {
        WifiAndLanValidators.validatePin(pin)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }

        val expectedSwitch = if (enable) "1" else "0"
        return executeSimWrite(
            buildPayload = {
                mapOf(
                    "subcmd" to "1",
                    "enable" to expectedSwitch,
                    "pin" to pin
                )
            },
            verify = { cur -> cur.optString("lock_pin_switch") == expectedSwitch },
            mismatchMessage = "SIM PIN check failed. Remaining attempts updated."
        )
    }

    suspend fun unlockPin(pin: String): RouterResult<SimPinState> {
        WifiAndLanValidators.validatePin(pin)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }
        return executeSimWrite(
            buildPayload = {
                mapOf(
                    "subcmd" to "2",
                    "pin" to pin
                )
            },
            verify = { cur -> cur.optString("lock_pin_flag") != "1" },
            mismatchMessage = "Incorrect SIM PIN. Remaining attempts updated."
        )
    }

    suspend fun unlockPuk(puk: String, newPin: String): RouterResult<SimPinState> {
        WifiAndLanValidators.validatePuk(puk)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }
        WifiAndLanValidators.validatePin(newPin)?.let { err ->
            return RouterResult.BadResponse(err, "")
        }
        return executeSimWrite(
            buildPayload = {
                mapOf(
                    "subcmd" to "3",
                    "puk" to puk,
                    "pin" to newPin,
                    "pukPassword1" to puk,
                    "pukPinPassword1" to newPin,
                    "pukPinPasswordConfirm1" to newPin
                )
            },
            verify = { cur -> cur.optString("lock_puk_flag") != "1" && cur.optString("lock_pin_flag") != "1" },
            mismatchMessage = "Incorrect PUK code. Remaining attempts updated."
        )
    }

    suspend fun changePin(oldPin: String, newPin: String): RouterResult<SimPinState> {
        WifiAndLanValidators.validatePin(oldPin)?.let { err ->
            return RouterResult.BadResponse("Current $err", "")
        }
        WifiAndLanValidators.validatePin(newPin)?.let { err ->
            return RouterResult.BadResponse("New $err", "")
        }
        if (oldPin == newPin) {
            return RouterResult.BadResponse("New PIN must differ from current PIN", "")
        }
        return executeSimWrite(
            buildPayload = {
                mapOf(
                    "subcmd" to "4",
                    "enable" to "2",
                    "pin" to oldPin,
                    "passwordChange" to newPin,
                    "passwordConfirm" to newPin,
                    "pinPasswordChange" to newPin
                )
            },
            verify = { _ -> true },
            mismatchMessage = "Could not change SIM PIN."
        )
    }

    private suspend fun executeSimWrite(
        buildPayload: (JSONObject) -> Map<String, Any>,
        verify: (JSONObject) -> Boolean,
        mismatchMessage: String
    ): RouterResult<SimPinState> {
        val currentState = _state.value
        val writeRes = writer.write(
            WriteSpec(
                cmd = 51,
                readCmd = 134,
                buildPayload = buildPayload,
                verifyPost = { postJson ->
                    val msg = postJson.optString("message", "")
                    if (postJson.has("message") && msg != "0") {
                        RouterResult.BadResponse(mismatchMessage, postJson.toString())
                    } else {
                        null
                    }
                },
                verify = verify,
                parseResult = { readBack ->
                    parseSimPinState(
                        readBack,
                        simInserted = currentState?.simInserted ?: true,
                        cardType = currentState?.cardType.orEmpty()
                    )
                },
                writeTimeoutSeconds = 12L,
                verifyRetries = 4,
                verifyDelayMs = 1500L
            )
        )

        return when (writeRes) {
            is RouterResult.Ok -> {
                _state.value = writeRes.data
                writeRes
            }
            is RouterResult.BadResponse -> {
                // Re-read remaining PIN/PUK attempts after any failed attempt.
                loadSimStatus()
                val raw = runCatching { JSONObject(writeRes.rawBody) }.getOrNull()
                val rawMsg = raw?.optString("message", "").orEmpty()
                val msg = if (rawMsg.isNotEmpty() && rawMsg != "0") mismatchMessage else writeRes.message
                RouterResult.BadResponse(msg, writeRes.rawBody)
            }
            is RouterResult.LimitedAccess -> RouterResult.LimitedAccess(51, writeRes.message)
            else -> writeRes
        }
    }

    companion object {
        internal fun parseSimPinState(
            json: JSONObject,
            simInserted: Boolean = true,
            cardType: String = ""
        ): SimPinState {
            return SimPinState(
                simInserted = simInserted,
                cardType = cardType,
                pinLockEnabled = json.optString("lock_pin_switch") == "1",
                pinRequired = json.optString("lock_pin_flag") == "1",
                pukRequired = json.optString("lock_puk_flag") == "1",
                pinAttemptsLeft = json.optString("pin_left_times", "3").toIntOrNull() ?: 3,
                pukAttemptsLeft = json.optString("puk_left_times", "10").toIntOrNull() ?: 10
            )
        }
    }
}
