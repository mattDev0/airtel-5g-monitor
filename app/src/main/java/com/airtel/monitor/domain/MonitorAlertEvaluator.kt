package com.airtel.monitor.domain

data class MonitorAlertConfig(
    val notifyQuotaWarning: Boolean = true,
    val notifyNewSms: Boolean = true,
    val notifyNewDevice: Boolean = false,
    val notifyUnreachable: Boolean = true
)

data class MonitorState(
    val lastQuotaAlertCycleKey: String = "",
    val lastNotifiedSmsUnreadCount: Int = -1,
    val deviceBaselineInitialized: Boolean = false,
    val knownDeviceMacs: Set<String> = emptySet(),
    val consecutiveUnreachableCount: Int = 0,
    val isUnreachableAlertActive: Boolean = false
)

sealed interface MonitorObservation {
    data class PollSuccess(
        val usageUsedPercent: Int?,
        val usageWarnPercent: Int?,
        val cycleKey: String,
        val smsUnreadCount: Int,
        val activeDeviceMacs: Set<String>
    ) : MonitorObservation

    data object PollFailure : MonitorObservation
}

sealed interface MonitorAlert {
    data class QuotaWarning(val usedPercent: Int, val thresholdPercent: Int) : MonitorAlert
    data class NewSms(val deltaCount: Int, val totalUnread: Int) : MonitorAlert
    data class NewDevices(val macs: Set<String>) : MonitorAlert
    data object RouterUnreachable : MonitorAlert
    data object RouterRecovered : MonitorAlert
}

data class MonitorEvaluation(
    val nextState: MonitorState,
    val alerts: List<MonitorAlert>
)

/**
 * Pure-Kotlin notification deduplication state machine for [RouterMonitorWorker].
 */
object MonitorAlertEvaluator {

    fun evaluate(
        previous: MonitorState,
        config: MonitorAlertConfig,
        observation: MonitorObservation
    ): MonitorEvaluation {
        return when (observation) {
            MonitorObservation.PollFailure -> evaluateFailure(previous, config)
            is MonitorObservation.PollSuccess -> evaluateSuccess(previous, config, observation)
        }
    }

    private fun evaluateFailure(
        previous: MonitorState,
        config: MonitorAlertConfig
    ): MonitorEvaluation {
        val nextCount = previous.consecutiveUnreachableCount + 1
        val alerts = mutableListOf<MonitorAlert>()
        var alertActive = previous.isUnreachableAlertActive
        if (config.notifyUnreachable && nextCount >= 2 && !alertActive) {
            alerts += MonitorAlert.RouterUnreachable
            alertActive = true
        }
        return MonitorEvaluation(
            nextState = previous.copy(
                consecutiveUnreachableCount = nextCount,
                isUnreachableAlertActive = alertActive
            ),
            alerts = alerts
        )
    }

    private fun evaluateSuccess(
        previous: MonitorState,
        config: MonitorAlertConfig,
        obs: MonitorObservation.PollSuccess
    ): MonitorEvaluation {
        val alerts = mutableListOf<MonitorAlert>()

        // 1. Recovery alert if router had crossed 2x consecutive failures
        if (previous.isUnreachableAlertActive && config.notifyUnreachable) {
            alerts += MonitorAlert.RouterRecovered
        }

        // 2. Data usage warning (at most once per billing cycle)
        var nextQuotaCycleKey = previous.lastQuotaAlertCycleKey
        val usedPct = obs.usageUsedPercent
        val warnPct = obs.usageWarnPercent
        if (
            config.notifyQuotaWarning &&
            usedPct != null &&
            warnPct != null &&
            warnPct > 0 &&
            usedPct >= warnPct &&
            obs.cycleKey.isNotBlank() &&
            previous.lastQuotaAlertCycleKey != obs.cycleKey
        ) {
            alerts += MonitorAlert.QuotaWarning(usedPercent = usedPct, thresholdPercent = warnPct)
            nextQuotaCycleKey = obs.cycleKey
        }

        // 3. New SMS detection via unread count watermark (baseline seeded silently on first observation)
        val unread = obs.smsUnreadCount.coerceAtLeast(0)
        if (
            config.notifyNewSms &&
            previous.lastNotifiedSmsUnreadCount >= 0 &&
            unread > previous.lastNotifiedSmsUnreadCount
        ) {
            alerts += MonitorAlert.NewSms(
                deltaCount = unread - previous.lastNotifiedSmsUnreadCount,
                totalUnread = unread
            )
        }
        val nextSmsCount = unread

        // 4. Opt-in new device detection by normalized MAC address
        val normalizedMacs = obs.activeDeviceMacs
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        var nextBaselineInit = previous.deviceBaselineInitialized
        var nextKnownMacs = previous.knownDeviceMacs
        if (config.notifyNewDevice) {
            if (!previous.deviceBaselineInitialized) {
                nextKnownMacs = normalizedMacs
                nextBaselineInit = true
            } else {
                val added = normalizedMacs - previous.knownDeviceMacs
                if (added.isNotEmpty()) {
                    alerts += MonitorAlert.NewDevices(added)
                }
                nextKnownMacs = previous.knownDeviceMacs + normalizedMacs
            }
        } else {
            nextBaselineInit = false
            nextKnownMacs = emptySet()
        }

        return MonitorEvaluation(
            nextState = previous.copy(
                lastQuotaAlertCycleKey = nextQuotaCycleKey,
                lastNotifiedSmsUnreadCount = nextSmsCount,
                deviceBaselineInitialized = nextBaselineInit,
                knownDeviceMacs = nextKnownMacs,
                consecutiveUnreachableCount = 0,
                isUnreachableAlertActive = false
            ),
            alerts = alerts
        )
    }
}
