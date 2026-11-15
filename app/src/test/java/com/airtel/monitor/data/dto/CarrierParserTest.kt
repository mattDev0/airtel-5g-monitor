package com.airtel.monitor.data.dto

import com.airtel.monitor.domain.CarrierComponent
import com.airtel.monitor.domain.CarrierTech
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierParserTest {

    private fun loadFixture(name: String): JSONObject {
        val text = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().readText()
        return JSONObject(text)
    }

    @Test
    fun `parses 4G plus CA carriers from live-shaped cmd 205 fixture`() {
        val json = loadFixture("205_ca.json")
        val carriers = parseCarriers(json)

        assertEquals(2, carriers.size)
        assertEquals(
            CarrierComponent(CarrierTech.LTE, band = "B7", earfcn = "3400", pci = "393", bandwidthMHz = 10, isPrimary = true),
            carriers[0]
        )
        assertEquals(
            CarrierComponent(CarrierTech.LTE, band = "B1", earfcn = "225", pci = "393", bandwidthMHz = 15, isPrimary = false),
            carriers[1]
        )
        assertEquals("B7 + B1 · 25 MHz", formatCaSummary(carriers))
        assertEquals("B7+B1", formatBandPill(carriers))
        assertEquals("PCI 393 · 2CC", formatCellPill(carriers))
    }

    @Test
    fun `parses 5G NSA carriers alongside 4G anchor from cmd 205 fixture`() {
        val json = loadFixture("205_nsa.json")
        val carriers = parseCarriers(json)

        assertEquals(3, carriers.size)
        val lte = carriers.filter { it.tech == CarrierTech.LTE }
        val nr = carriers.filter { it.tech == CarrierTech.NR }
        assertEquals(2, lte.size)
        assertTrue(lte[0].isPrimary)
        assertFalse(lte[1].isPrimary)
        assertEquals(1, nr.size)
        assertEquals(CarrierComponent(CarrierTech.NR, band = "n78", earfcn = "627264", pci = "916", bandwidthMHz = 100, isPrimary = true), nr[0])

        assertEquals("B3 + B1 + n78 · 125 MHz", formatCaSummary(carriers))
        assertEquals("n78", formatBandPill(carriers))
        assertEquals("PCI 916", formatCellPill(carriers))
    }

    @Test
    fun `parses CA carriers from cmd 160 even when bandwidth is absent`() {
        val json = loadFixture("160_ca.json")
        val carriers = parseCarriers(json)

        assertEquals(2, carriers.size)
        assertEquals("B7", carriers[0].band)
        assertEquals("3400", carriers[0].earfcn)
        assertEquals("393", carriers[0].pci)
        assertEquals(null, carriers[0].bandwidthMHz)
        assertEquals("B1", carriers[1].band)
        assertEquals("225", carriers[1].earfcn)
        assertEquals("B7 + B1", formatCaSummary(carriers))
        assertEquals("PCI 393 · 2CC", formatCellPill(carriers))
    }

    @Test
    fun `ignores empty or dash carrier fields`() {
        val json = JSONObject().put("currentband", "-").put("PCI", "null").put("FREQ", "")
        assertTrue(parseCarriers(json).isEmpty())
        assertEquals("-", formatCaSummary(emptyList()))
        assertEquals("-", formatBandPill(emptyList()))
        assertEquals("-", formatCellPill(emptyList()))
    }
}
