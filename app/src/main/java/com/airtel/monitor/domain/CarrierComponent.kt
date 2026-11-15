package com.airtel.monitor.domain

enum class CarrierTech {
    LTE,
    NR
}

/** One serving component carrier parsed from cmd 205, cmd 160, or cmd 133. */
data class CarrierComponent(
    val tech: CarrierTech,
    val band: String,
    val earfcn: String,
    val pci: String,
    val bandwidthMHz: Int?,
    val isPrimary: Boolean
)
