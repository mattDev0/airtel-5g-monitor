import Foundation

/// Pure notification deduplication state machine.
public enum AlertEvaluator {
    public static func evaluate(
        previous: MonitorState,
        config: MonitorAlertConfig,
        observation: MonitorObservation
    ) -> MonitorEvaluation {
        switch observation {
        case .pollFailure:
            return evaluateFailure(previous: previous, config: config)
        case .pollSuccess(let usedPct, let warnPct, let cycleKey, let smsUnreadCount, let activeDeviceMacs):
            return evaluateSuccess(
                previous: previous,
                config: config,
                usageUsedPercent: usedPct,
                usageWarnPercent: warnPct,
                cycleKey: cycleKey,
                smsUnreadCount: smsUnreadCount,
                activeDeviceMacs: activeDeviceMacs
            )
        }
    }

    private static func evaluateFailure(
        previous: MonitorState,
        config: MonitorAlertConfig
    ) -> MonitorEvaluation {
        let nextCount = previous.consecutiveUnreachableCount + 1
        var alerts: [MonitorAlert] = []
        var alertActive = previous.isUnreachableAlertActive
        if config.notifyUnreachable && nextCount >= 2 && !alertActive {
            alerts.append(.routerUnreachable)
            alertActive = true
        }
        let nextState = MonitorState(
            lastQuotaAlertCycleKey: previous.lastQuotaAlertCycleKey,
            lastNotifiedSmsUnreadCount: previous.lastNotifiedSmsUnreadCount,
            deviceBaselineInitialized: previous.deviceBaselineInitialized,
            knownDeviceMacs: previous.knownDeviceMacs,
            consecutiveUnreachableCount: nextCount,
            isUnreachableAlertActive: alertActive
        )
        return MonitorEvaluation(nextState: nextState, alerts: alerts)
    }

    private static func evaluateSuccess(
        previous: MonitorState,
        config: MonitorAlertConfig,
        usageUsedPercent: Int?,
        usageWarnPercent: Int?,
        cycleKey: String,
        smsUnreadCount: Int,
        activeDeviceMacs: Set<String>
    ) -> MonitorEvaluation {
        var alerts: [MonitorAlert] = []

        if previous.isUnreachableAlertActive && config.notifyUnreachable {
            alerts.append(.routerRecovered)
        }

        var nextQuotaCycleKey = previous.lastQuotaAlertCycleKey
        if config.notifyQuotaWarning,
           let usedPct = usageUsedPercent,
           let warnPct = usageWarnPercent,
           warnPct > 0,
           usedPct >= warnPct,
           !cycleKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           previous.lastQuotaAlertCycleKey != cycleKey {
            alerts.append(.quotaWarning(usedPercent: usedPct, thresholdPercent: warnPct))
            nextQuotaCycleKey = cycleKey
        }

        let unread = max(0, smsUnreadCount)
        if config.notifyNewSms &&
            previous.lastNotifiedSmsUnreadCount >= 0 &&
            unread > previous.lastNotifiedSmsUnreadCount {
            alerts.append(.newSms(
                deltaCount: unread - previous.lastNotifiedSmsUnreadCount,
                totalUnread: unread
            ))
        }

        let normalizedMacs = Set(
            activeDeviceMacs
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() }
                .filter { !$0.isEmpty }
        )

        var nextBaselineInit = previous.deviceBaselineInitialized
        var nextKnownMacs = previous.knownDeviceMacs
        if config.notifyNewDevice {
            if !previous.deviceBaselineInitialized {
                nextKnownMacs = normalizedMacs
                nextBaselineInit = true
            } else {
                let added = normalizedMacs.subtracting(previous.knownDeviceMacs)
                if !added.isEmpty {
                    alerts.append(.newDevices(macs: added))
                }
                nextKnownMacs = previous.knownDeviceMacs.union(normalizedMacs)
            }
        } else {
            nextBaselineInit = false
            nextKnownMacs = []
        }

        let nextState = MonitorState(
            lastQuotaAlertCycleKey: nextQuotaCycleKey,
            lastNotifiedSmsUnreadCount: unread,
            deviceBaselineInitialized: nextBaselineInit,
            knownDeviceMacs: nextKnownMacs,
            consecutiveUnreachableCount: 0,
            isUnreachableAlertActive: false
        )
        return MonitorEvaluation(nextState: nextState, alerts: alerts)
    }
}
