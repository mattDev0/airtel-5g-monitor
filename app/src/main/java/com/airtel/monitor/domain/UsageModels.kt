package com.airtel.monitor.domain

data class MonthlyUsageSummary(
    val usedMb: Double? = null,
    val dlMonthMb: Double? = null,
    val ulMonthMb: Double? = null,
    val limitEnabled: Boolean = false,
    val limitMb: Double? = null,
    val cycleStartDay: Int? = null,
    val warnPercentage: Int? = null,
    val daysLeftInCycle: Int? = null
) {
    val usedGb: Double?
        get() = usedMb?.let { it / 1024.0 }

    val limitGb: Double?
        get() = limitMb?.let { it / 1024.0 }

    val progressFraction: Float?
        get() {
            val used = usedMb ?: return null
            val limit = limitMb ?: return null
            if (limit <= 0.0) return null
            return (used / limit).toFloat().coerceIn(0f, 1f)
        }

    val isOverWarning: Boolean
        get() {
            val frac = progressFraction ?: return false
            val warn = warnPercentage ?: return false
            if (warn <= 0) return false
            return frac * 100f >= warn
        }
}
