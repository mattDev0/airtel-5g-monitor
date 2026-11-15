package com.airtel.monitor.core.util

import java.util.Locale

/**
 * Formatting and validation utilities for Phase 5 System & Security screens.
 * Password rules match `js/index.js:validatePass`.
 */
object SensitiveFormatters {

    fun maskIdentifier(raw: String, visibleSuffix: Int = 4): String {
        val clean = raw.trim()
        if (clean.isEmpty() || clean == "-" || clean.equals("REDACTED", ignoreCase = true)) {
            return clean
        }
        val suffixCount = visibleSuffix.coerceAtLeast(0)
        if (clean.length <= suffixCount) {
            return "•".repeat(clean.length.coerceAtLeast(4))
        }
        val maskedPrefix = "•".repeat((clean.length - suffixCount).coerceAtLeast(4))
        return maskedPrefix + clean.takeLast(suffixCount)
    }

    fun validateRouterPassword(
        oldPassword: String,
        newPassword: String,
        confirmPassword: String
    ): String? {
        if (oldPassword.isBlank()) {
            return "Current password is required"
        }
        if (newPassword.isEmpty()) {
            return "New password cannot be blank"
        }
        if (newPassword.any { it.isWhitespace() }) {
            return "Password cannot contain spaces"
        }
        if (newPassword.any { it.code < 0x20 || it.code > 0x7E }) {
            return "Password must use printable ASCII characters only"
        }
        if (newPassword.length !in 8..63) {
            return "Password must be 8 to 63 characters"
        }
        if (newPassword == oldPassword) {
            return "New password must differ from current password"
        }
        val hasUpper = newPassword.any { it in 'A'..'Z' }
        val hasLower = newPassword.any { it in 'a'..'z' }
        val hasDigit = newPassword.any { it in '0'..'9' }
        val hasSymbol = newPassword.any { !it.isLetterOrDigit() }
        if (!hasUpper || !hasLower || !hasDigit || !hasSymbol) {
            return "Include uppercase, lowercase, number, and special character"
        }
        if (newPassword != confirmPassword) {
            return "New password confirmation does not match"
        }
        return null
    }

    fun validateLogSizeKb(raw: String): String? {
        val value = raw.trim().toIntOrNull() ?: return "Log size must be a whole number (KB)"
        if (value !in 1..2000) return "Log size must be between 1 and 2000 KB"
        return null
    }

    fun formatUptimeSeconds(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return "--"
        val totalSeconds = clean.toLongOrNull() ?: return clean
        if (totalSeconds < 0) return "--"
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        return when {
            days > 0 -> String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes)
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            else -> String.format(Locale.US, "%dm %02ds", minutes, totalSeconds % 60)
        }
    }
}
