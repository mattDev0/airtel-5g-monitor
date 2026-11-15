package com.airtel.monitor.data.model

import org.json.JSONObject

/**
 * Web UI capability & visibility flags parsed from `cmd 80` (`INIT_INFO` / `js/main.js` + `js/index.js`).
 */
data class RouterFeatureFlags(
    val isLoaded: Boolean = false,
    val webPageHideHex: String = "",
    val pageFlags: List<Char> = DEFAULT_PAGE_FLAGS,
    val accountLevel: String = "3",
    val aeraId: String = "",
    val networkMode: String = "",
    val fwSwitch: Boolean = false,
    val parentControlSwitch: Boolean = false,
    val lockPlmnFlag: Boolean = false,
    val ipPassthrough: Boolean = false,
    val smsEnabled: Boolean = true,
    val iadReady: Boolean = true,
    val wifiSsidForbid: List<String> = emptyList(),
    val wifiPwdForbid: List<String> = emptyList()
) {
    /** Standard `Page.pageHideCheck(index)` from `js/main.js:1534`. */
    fun pageHideCheck(index: Int): Boolean {
        val v = pageFlags.getOrNull(index)
        return when (v) {
            '1' -> accountLevel == "1" || accountLevel == "2"
            '2' -> accountLevel == "1" || accountLevel == "2" || accountLevel == "3"
            '3' -> true
            else -> accountLevel == "1"
        }
    }

    /** Inverted `Page.pageHideCheck2(index)` from `js/main.js:1559`. */
    fun pageHideCheck2(index: Int): Boolean {
        val v = pageFlags.getOrNull(index)
        return when (v) {
            '1' -> accountLevel == "1" || accountLevel == "2"
            '2' -> accountLevel == "1" || accountLevel == "2" || accountLevel == "3"
            '3' -> accountLevel == "1"
            else -> true
        }
    }

    /** Admin-default `Page.pageHideCheck3(index)` from `js/main.js:1589`. */
    fun pageHideCheck3(index: Int): Boolean {
        val v = pageFlags.getOrNull(index)
        return when (v) {
            '1' -> true
            '2' -> accountLevel == "1" || accountLevel == "2" || accountLevel == "3"
            '3' -> accountLevel == "1"
            else -> accountLevel == "1" || accountLevel == "2"
        }
    }

    val ussdAvailable: Boolean get() = pageHideCheck(131)
    val networkSetAvailable: Boolean get() = pageHideCheck2(84)
    val apnAvailable: Boolean get() = pageHideCheck2(366)
    val trafficLimitAvailable: Boolean get() = pageHideCheck2(94)

    val wifiPowerCmd411Available: Boolean get() = aeraId == "NG0131"
    val guestWifi24gAvailable: Boolean get() = pageHideCheck(19)
    val guestWifi5gAvailable: Boolean get() = pageHideCheck(23)
    val wpsAvailable: Boolean get() = pageHideCheck2(211)
    val wifiScheduleAvailable: Boolean get() = pageHideCheck(255)

    val parentalControlAvailable: Boolean get() = pageHideCheck(153)
    val parentSpeedVisible: Boolean get() = pageHideCheck2(238)
    val parentUrlVisible: Boolean get() = pageHideCheck2(239)
    val parentTimeVisible: Boolean get() = pageHideCheck2(240)

    val dmzAvailable: Boolean get() = pageHideCheck2(61) && !ipPassthrough && networkMode != "7"
    val portForwardAvailable: Boolean get() = pageHideCheck2(171) && !ipPassthrough && networkMode != "7"
    val firewallRuleAvailable: Boolean get() = pageHideCheck2(62)
    val urlFilterAvailable: Boolean get() = pageHideCheck2(63)
    val ipMacBindingAvailable: Boolean get() = pageHideCheck2(64)
    val ddosAvailable: Boolean get() = pageHideCheck2(67)
    val upnpAvailable: Boolean get() = pageHideCheck(39)
    val trustedIpAvailable: Boolean get() = pageHideCheck2(118) && !ipPassthrough && networkMode != "7"
    val dhcpIpMacBindingSync: Boolean get() = pageHideCheck2(11)

    val wanDetectRescanAvailable: Boolean
        get() = pageHideCheck(186) && networkMode in setOf("1", "5", "7", "9")

    companion object {
        private val DEFAULT_PAGE_FLAGS = listOf('0', '0')
        private val NIBBLE_TO_PAIR = arrayOf(
            "00", "01", "02", "03",
            "10", "11", "12", "13",
            "20", "21", "22", "23",
            "30", "31", "32", "33"
        )

        /** Exact implementation of `Page.parseHexPageHide(hex)` (`js/main.js:1520-1533`). */
        fun parseHexPageHide(hex: String?): List<Char> {
            val clean = hex?.trim().orEmpty()
            if (clean.isEmpty()) return DEFAULT_PAGE_FLAGS
            val out = StringBuilder(clean.length * 2)
            for (ch in clean) {
                val nibble = ch.digitToIntOrNull(16) ?: continue
                out.append(NIBBLE_TO_PAIR[nibble])
            }
            if (out.isEmpty()) return DEFAULT_PAGE_FLAGS
            return out.toString().reversed().toList()
        }

        fun fromJson80(json: JSONObject): RouterFeatureFlags {
            val rawHide = json.optString("web_page_hide", "").trim()
            val ssidForbid = json.optString("wifi_ssid_forbid", "")
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val pwdForbid = json.optString("wifi_pwd_forbid", "")
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return RouterFeatureFlags(
                isLoaded = true,
                webPageHideHex = rawHide,
                pageFlags = parseHexPageHide(rawHide),
                accountLevel = json.optString("account_level", "").trim().ifEmpty { "3" },
                aeraId = json.optString("aeraId", "").trim(),
                networkMode = json.optString("network_mode", "").trim(),
                fwSwitch = json.optString("fw_switch", "").trim() == "1",
                parentControlSwitch = json.optString("parentControlSwitch", "").trim() == "1",
                lockPlmnFlag = json.optString("lock_plmn_flag", "").trim() == "1",
                ipPassthrough = json.optString("ip_passthrough_sw", "").trim() == "1",
                smsEnabled = json.optString("smsSw", "1").trim() == "1",
                iadReady = json.optString("iad_ready_status", "1").trim() != "0",
                wifiSsidForbid = ssidForbid,
                wifiPwdForbid = pwdForbid
            )
        }
    }
}
