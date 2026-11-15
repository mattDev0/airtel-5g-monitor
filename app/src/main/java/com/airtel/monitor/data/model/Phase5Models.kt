package com.airtel.monitor.data.model

/** Firewall protection level values verified in `js/firewall/fw_switch_setting.js`. */
enum class FirewallLevel(val wireValue: String, val label: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High");

    companion object {
        fun fromWire(raw: String): FirewallLevel =
            entries.firstOrNull { it.wireValue.equals(raw.trim(), ignoreCase = true) } ?: LOW
    }
}

/** State for `cmd 367` (`FW_SWITCH_SETTING`). */
data class FirewallConfig(
    val enabled: Boolean,
    val level: FirewallLevel
)

/** Application-layer gateway (ALG) switch state for `cmd 504` (`ALG_SETTING`). */
data class AlgSettings(
    val l2tp: Boolean,
    val ipsec: Boolean,
    val h323: Boolean,
    val rtsp: Boolean,
    val sip: Boolean,
    val ftp: Boolean,
    val pptp: Boolean
)

/** Scheduled auto-reboot state for `cmd 263` (`TIME_TO_RESTART`). */
data class ScheduledRebootConfig(
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    /** Weekday codes used by ZLT firmware (`1` = Mon .. `6` = Sat, `0` = Sun). */
    val weekDays: Set<Int>
)

/** Log severity grade for `cmd 322` (`LOG_INFO`). */
enum class LogGrade(val wireValue: String, val label: String) {
    DEFAULT("0", "Default"),
    ERROR("1", "Error"),
    WARNING("2", "Warning"),
    DEBUG("3", "Debug"),
    INFO("4", "Info");

    companion object {
        fun fromWire(raw: String): LogGrade =
            entries.firstOrNull { it.wireValue == raw.trim() } ?: DEFAULT
    }
}

/** Log settings state for `cmd 322` (`LOG_INFO`). */
data class LogSettingsConfig(
    val grade: LogGrade,
    val sizeKb: Int
)

/** Hardware, firmware, and cellular identity fields parsed from `cmd 207` / `cmd 133` / `cmd 113`. */
data class AboutRouterInfo(
    val oduModel: String,
    val iduModel: String,
    val hardwareVersion: String,
    val firmwareVersion: String,
    val fakeVersion: String,
    val configVersion: String,
    val buildDate: String,
    val imei: String,
    val imsi: String,
    val iccid: String,
    val serialNumber: String,
    val msisdn: String,
    val eid: String,
    val moduleType: String,
    val moduleFirmware: String,
    val uptimeText: String,
    val cpuUsage: String,
    val temperature: String,
    val memoryText: String,
    val iduMac: String,
    val lanMac: String,
    val linkRates: List<String>
)

/** On-demand diagnostic check snapshot from `cmd 264` (`DEVICE_SELF_CHECK`). */
data class SelfCheckSnapshot(
    val boardType: String,
    val simStatus: String,
    val antennaStatus: String,
    val flashLevel: String,
    val memoryLevel: String,
    val temperatureLevel: String,
    val dhcpServer: String,
    val networkType: String,
    val signalLevel: String,
    val pdpType: String,
    val wanIpv4: String,
    val wanIpv6: String,
    val wifi2gEnabled: Boolean,
    val wifi5gEnabled: Boolean
)

enum class SpeedTestPhase {
    IDLE,
    PING,
    DOWNLOAD,
    UPLOAD,
    COMPLETE,
    ERROR,
    CANCELLED
}

data class SpeedTestProgress(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val progressFraction: Float = 0f,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val statusLabel: String = "",
    val errorMessage: String? = null
)
