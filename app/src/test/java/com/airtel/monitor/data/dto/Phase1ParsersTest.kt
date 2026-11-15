package com.airtel.monitor.data.dto

import com.airtel.monitor.domain.CarrierTech
import com.airtel.monitor.ui.feature.signal.AlignmentTonePlayer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1ParsersTest {

    private fun fixture(name: String): JSONObject {
        val text = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().readText()
        return JSONObject(text)
    }

    @Test
    fun `parses link details and cell identity from 205 and 133 fixtures`() {
        val net205 = fixture("205_ca.json")
        val info133 = fixture("133.json")

        val link = parseLinkDetails(net205, info133)
        assertEquals("22", link.dlMcs4g)
        assertEquals("18", link.ulMcs4g)
        assertEquals("256QAM", link.maxDlQam4g)
        assertEquals("64QAM", link.maxUlQam4g)
        assertEquals("2", link.rank4g)
        assertEquals("0.4", link.bler4g)
        assertFalse(link.has5gDetails)

        val cellId = parseCellIdentity(net205, info133)
        assertEquals("62120", cellId.plmn)
        assertEquals("10456", cellId.enbId4g)
        assertEquals("2676737", cellId.cellId4g)
        assertEquals("4102", cellId.tac4g)
        assertEquals("62120-2676737", cellId.ecgi)
        assertFalse(cellId.has5gIdentity)
    }

    @Test
    fun `parses neighbor cells from tuple string fixture and JSON arrays`() {
        val neighbors = parseNeighborCells(fixture("282.json"))
        assertEquals(3, neighbors.size)
        assertEquals(CarrierTech.LTE, neighbors[0].tech)
        assertEquals("1850", neighbors[0].earfcn)
        assertEquals("372", neighbors[0].pci)
        assertEquals("-74", neighbors[0].rsrp)
        assertEquals("-13.5", neighbors[0].rsrq)
        assertEquals("13", neighbors[0].sinr)

        val nrJson = JSONObject().put(
            "nr_info",
            JSONArray().put(
                JSONObject()
                    .put("arfcn", "627264")
                    .put("pci", "916")
                    .put("rsrp", "-82")
                    .put("rsrq", "-11")
                    .put("sinr", "21")
            )
        )
        val nrParsed = parseNeighborCells(nrJson)
        assertEquals(1, nrParsed.size)
        assertEquals(CarrierTech.NR, nrParsed[0].tech)
        assertEquals("627264", nrParsed[0].earfcn)
        assertEquals("916", nrParsed[0].pci)

        assertTrue(parseNeighborCells(JSONObject().put("lte_info", "").put("nr_info", "")).isEmpty())
    }

    @Test
    fun `parses signal history from 1018 device_info`() {
        val raw = fixture("1018.json").getJSONArray("device_info")
        val rows = List(raw.length()) { i -> raw.getJSONObject(i) }
        val pts = parseSignalHistory1018(rows)
        assertEquals(2, pts.size)
        assertEquals("14:20:00", pts[0].timeLabel)
        assertEquals(-85f, pts[0].rsrp4g)
        assertEquals(14f, pts[0].sinr4g)
        assertNull(pts[0].rsrp5g)
        assertEquals("393", pts[1].pci)
    }

    @Test
    fun `parses alignment snapshot preferring 5G NSA metrics when present`() {
        val nsa = fixture("205_nsa.json")
        val snap = parseAlignmentSnapshot(nsa, parseCarriers(nsa), 12345L)
        assertEquals("5G NR", snap.activeTechLabel)
        assertEquals(-82f, snap.primaryRsrp)
        assertEquals(21f, snap.primarySinr)
        assertEquals(-86f, snap.rsrp4g)
        assertEquals(15f, snap.sinr4g)
        assertTrue(snap.carrierSummary.contains("125 MHz"))
    }

    @Test
    fun `parses monthly data usage and cycle countdown`() {
        val net205 = fixture("205_ca.json")
        val traffic337 = fixture("337.json")
        val summary = parseMonthlyUsage(
            net205 = net205,
            dash = JSONObject(),
            traffic337 = traffic337,
            todayDayOfMonth = 5,
            daysInMonth = 30
        )
        assertTrue(summary.limitEnabled)
        assertEquals(264366.45, summary.usedMb!!, 0.01)
        assertEquals(500.0 * 1024.0, summary.limitMb!!, 0.01)
        assertEquals(10, summary.cycleStartDay)
        assertEquals(80, summary.warnPercentage)
        assertEquals(5, summary.daysLeftInCycle)
        assertFalse(summary.isOverWarning)

        val mbUnit = JSONObject(traffic337.toString())
            .put("flow_limit_unit", "MB")
            .put("limitSize", "300000")
            .put("warn_percentage", "80")
        val overWarn = parseMonthlyUsage(net205, JSONObject(), mbUnit, todayDayOfMonth = 15, daysInMonth = 30)
        assertEquals(300000.0, overWarn.limitMb!!, 0.01)
        assertEquals(25, overWarn.daysLeftInCycle)
        assertTrue(overWarn.isOverWarning)
    }

    @Test
    fun `formats router durations and alignment pitch rises with SINR`() {
        assertEquals("3h 57m", formatDurationText("14230"))
        assertEquals("2d 0h 0m", formatDurationText("172800"))
        assertEquals("45s", formatDurationText("45"))
        assertEquals("1d 1h 5m", formatDurationText("25:05:10"))

        val lowFreq = AlignmentTonePlayer.sinrToFrequencyHz(-2f)
        val highFreq = AlignmentTonePlayer.sinrToFrequencyHz(24f)
        assertTrue(highFreq > lowFreq)
    }
}
