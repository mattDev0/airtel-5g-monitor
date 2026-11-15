package com.airtel.monitor.domain

data class LinkDetails(
    val dlMcs4g: String = "-",
    val ulMcs4g: String = "-",
    val dlMcs5g: String = "-",
    val ulMcs5g: String = "-",
    val maxDlQam4g: String = "-",
    val maxUlQam4g: String = "-",
    val maxDlQam5g: String = "-",
    val maxUlQam5g: String = "-",
    val rank4g: String = "-",
    val rank5g: String = "-",
    val mimoStatus: String = "-",
    val bler4g: String = "-",
    val bler5g: String = "-"
) {
    val has5gDetails: Boolean
        get() = listOf(dlMcs5g, ulMcs5g, maxDlQam5g, maxUlQam5g, rank5g, bler5g)
            .any { it.isNotBlank() && it != "-" }
}

data class CellIdentity(
    val plmn: String = "-",
    val enbId4g: String = "-",
    val cellId4g: String = "-",
    val tac4g: String = "-",
    val ecgi: String = "-",
    val enbId5g: String = "-",
    val cellId5g: String = "-",
    val tac5g: String = "-",
    val ncgi: String = "-"
) {
    val has5gIdentity: Boolean
        get() = listOf(enbId5g, cellId5g, tac5g, ncgi).any { it.isNotBlank() && it != "-" }
}

data class NeighborCell(
    val tech: CarrierTech,
    val earfcn: String,
    val pci: String,
    val rsrp: String,
    val rsrq: String,
    val sinr: String
)

data class SignalHistoryPoint(
    val timeLabel: String,
    val rsrp4g: Float? = null,
    val sinr4g: Float? = null,
    val rsrp5g: Float? = null,
    val sinr5g: Float? = null,
    val pci: String = ""
)

data class AlignmentSnapshot(
    val networkType: String = "-",
    val carrierSummary: String = "-",
    val rsrp5g: Float? = null,
    val sinr5g: Float? = null,
    val rsrp4g: Float? = null,
    val sinr4g: Float? = null,
    val timestampMs: Long = 0L
) {
    val activeTechLabel: String
        get() = if (rsrp5g != null || sinr5g != null) "5G NR" else "4G LTE"

    val primaryRsrp: Float?
        get() = rsrp5g ?: rsrp4g

    val primarySinr: Float?
        get() = sinr5g ?: sinr4g
}
