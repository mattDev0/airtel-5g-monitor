package com.airtel.monitor.data.model

enum class SmsFolder(val subcmd: Int) {
    INBOX(0),
    OUTBOX(1)
}

/** Single SMS row decoded from `cmd 12` (`SMS_INFO`) Base64 records. */
data class SmsMessage(
    val index: String,
    val isRead: Boolean,
    val phoneNumber: String,
    val timestamp: String,
    val text: String,
    val folder: SmsFolder
)

/** One page of SMS messages from `cmd 12`. */
data class SmsPage(
    val folder: SmsFolder = SmsFolder.INBOX,
    val messages: List<SmsMessage> = emptyList(),
    val totalCount: Int = 0,
    val pageNumber: Int = 1,
    val isReceiveFull: Boolean = false,
    val isSendFull: Boolean = false
)

/** SMS storage & SMSC configuration parsed from `cmd 16` (`SMS_SETTING`). */
data class SmsSettingsConfig(
    val smsEnabled: Boolean = true,
    val smscEditable: Boolean = false,
    val smscNumber: String = "",
    val maxSize: Int = 100,
    val maxLength: Int = 640
)

/** One locally recorded daily cellular data usage snapshot/delta (`yyyy-MM-dd`). */
data class DailyUsageEntry(
    val dateIso: String,
    val dailyMb: Double,
    val endTotalMb: Double,
    val baselineMb: Double,
    val sampleCount: Int = 1
)
