package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.network.map
import com.airtel.monitor.data.model.AlgSettings
import com.airtel.monitor.data.model.FirewallConfig
import com.airtel.monitor.data.model.FirewallLevel
import org.json.JSONObject

/**
 * Verified Phase 5 security settings:
 * - `cmd 367` (`FW_SWITCH_SETTING`): firewall on/off + level (`low`/`medium`/`high`)
 * - `cmd 504` (`ALG_SETTING`): 7 ALG protocol toggles
 */
class SecurityRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    fun loadFirewall(): RouterResult<FirewallConfig> =
        session.execute(cmd = 367, method = "GET").map(::parseFirewall)

    suspend fun saveFirewall(
        enabled: Boolean,
        level: FirewallLevel
    ): RouterResult<FirewallConfig> {
        val targetSwitch = if (enabled) "1" else "0"
        return writer.write(
            WriteSpec(
                cmd = 367,
                buildPayload = {
                    mapOf(
                        "fw_switch" to targetSwitch,
                        "level" to level.wireValue,
                        "success" to true
                    )
                },
                verify = { readBack ->
                    val switchOk = readBack.optString("fw_switch", "") == targetSwitch
                    val levelOk = !enabled || readBack.optString("level", "").equals(level.wireValue, ignoreCase = true)
                    switchOk && levelOk
                },
                parseResult = ::parseFirewall,
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    fun loadAlgSettings(): RouterResult<AlgSettings> =
        session.execute(cmd = 504, method = "GET").map(::parseAlg)

    suspend fun saveAlgSettings(settings: AlgSettings): RouterResult<AlgSettings> {
        val flagMap = mapOf(
            "L2TPSW_Flag" to settings.l2tp.toFlag(),
            "IPSecSW_Flag" to settings.ipsec.toFlag(),
            "H323SW_Flag" to settings.h323.toFlag(),
            "RTSPSW_Flag" to settings.rtsp.toFlag(),
            "SIPSW_Flag" to settings.sip.toFlag(),
            "FTPSW_Flag" to settings.ftp.toFlag(),
            "PPTPSW_Flag" to settings.pptp.toFlag()
        )
        return writer.write(
            WriteSpec(
                cmd = 504,
                buildPayload = { flagMap },
                verify = { readBack ->
                    flagMap.all { (key, expected) -> readBack.optString(key, "") == expected }
                },
                parseResult = ::parseAlg,
                verifyRetries = 2,
                verifyDelayMs = 250L
            )
        )
    }

    private fun parseFirewall(json: JSONObject): FirewallConfig = FirewallConfig(
        enabled = json.optString("fw_switch", "0") == "1",
        level = FirewallLevel.fromWire(json.optString("level", "low"))
    )

    private fun parseAlg(json: JSONObject): AlgSettings = AlgSettings(
        l2tp = json.optString("L2TPSW_Flag", "0") == "1",
        ipsec = json.optString("IPSecSW_Flag", "0") == "1",
        h323 = json.optString("H323SW_Flag", "0") == "1",
        rtsp = json.optString("RTSPSW_Flag", "0") == "1",
        sip = json.optString("SIPSW_Flag", "0") == "1",
        ftp = json.optString("FTPSW_Flag", "0") == "1",
        pptp = json.optString("PPTPSW_Flag", "0") == "1"
    )

    private fun Boolean.toFlag(): String = if (this) "1" else "0"
}
