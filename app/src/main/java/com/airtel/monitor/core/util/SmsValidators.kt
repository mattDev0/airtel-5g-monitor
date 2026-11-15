package com.airtel.monitor.core.util

/**
 * Input validation rules extracted from `js/sms/smsInbox.js` and `js/sms/smsSetting.js`.
 */
object SmsValidators {

    private val PHONE_REGEX = Regex("""^[\d+]\d{1,30}$""")

    fun validatePhoneNumber(rawPhone: String): String? {
        val clean = rawPhone.trim()
        if (clean.isEmpty()) return "Phone number cannot be blank"
        if (!PHONE_REGEX.matches(clean)) {
            return "Enter a valid recipient number (optional '+' followed by digits)"
        }
        return null
    }

    /** Counts UTF-16 code units with ZLT weighting (`< 0x007F` = 1, otherwise 2). */
    fun weightedSmsLength(content: String): Int {
        var total = 0
        for (ch in content) {
            total += if (ch.code < 0x007F) 1 else 2
        }
        return total
    }

    fun validateSmsBody(content: String, maxWeightedLength: Int = 4096): String? {
        if (content.isBlank()) return "Message body cannot be empty"
        if (content.any { Character.isSurrogate(it) }) {
            return "Emoji and 4-byte Unicode characters are not supported by the router"
        }
        val effectiveLimit = maxWeightedLength.coerceIn(1, 4096)
        val weight = weightedSmsLength(content)
        if (weight > effectiveLimit) {
            return "Message is too long ($weight / $effectiveLimit units)"
        }
        return null
    }

    fun validateSmsMaxSize(raw: String): String? {
        val value = raw.trim().toIntOrNull() ?: return "Capacity must be a whole number"
        if (value !in 50..1000) return "Storage capacity must be between 50 and 1000"
        return null
    }

    fun validateSmscNumber(raw: String, required: Boolean): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) {
            return if (required) "SMSC number cannot be blank" else null
        }
        if (!PHONE_REGEX.matches(clean)) {
            return "Enter a valid SMSC number (optional '+' followed by digits)"
        }
        return null
    }
}
