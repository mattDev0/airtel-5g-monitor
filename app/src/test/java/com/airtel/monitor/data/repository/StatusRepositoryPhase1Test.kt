package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.AliasLookup
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.RouterSettings
import com.airtel.monitor.core.network.RouterTransport
import com.airtel.monitor.core.network.SettingsWriter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRepositoryPhase1Test {

    private fun fixtureText(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().readText()

    private class FakeSettings : RouterSettings {
        override val routerHost: String = "192.168.1.1"
        override val username: String = "root"
        override val password: String = "secret"
    }

    private class FakeAliases : AliasLookup {
        override fun getAlias(identifier: String, fallback: String): String = fallback
    }

    @Test
    fun `pollFast populates wanIp, uptime, usage, signal history and neighbor load`() {
        val cmdsCalled = mutableListOf<Int>()
        var rxCounter = 1_000_000L
        var clockMs = 1_000_000L

        val transport = RouterTransport { _, body, _ ->
            val req = JSONObject(body)
            val cmd = req.getInt("cmd")
            cmdsCalled.add(cmd)
            when (cmd) {
                232 -> """{"success":true,"token":"tok"}"""
                100 -> """{"success":true,"sessionId":"sess1"}"""
                18 -> {
                    rxCounter += 500_000L
                    """{"success":true,"rxBytes":"$rxCounter","txBytes":"250000"}"""
                }
                401 -> """{"success":true,"network_type_str":"4G+","signal_lvl":4,"mon_total_flow":"264366.45"}"""
                113 -> """{"success":true,"network_type_str":"4G+","signal_lvl":4}"""
                205 -> fixtureText("205_ca.json")
                223 -> fixtureText("223.json")
                224 -> """{"success":true,"wlan24g_wifi_info":[]}"""
                225 -> fixtureText("225.json")
                1018 -> fixtureText("1018.json")
                160 -> fixtureText("160_ca.json")
                161 -> fixtureText("161.json")
                218 -> """{"success":true,"dialMode":"1","flightMode":"0","networkMode":"4G/5G","lteCA":"1","nrCA":"1"}"""
                394 -> fixtureText("394.json")
                133 -> fixtureText("133.json")
                207 -> fixtureText("207.json")
                337 -> fixtureText("337.json")
                282 -> fixtureText("282.json")
                else -> """{"success":true}"""
            }
        }

        val session = RouterSession(FakeSettings(), transport)
        val statusRepo = StatusRepository(session, FakeAliases(), clock = { clockMs })
        val signalRepo = SignalRepository(session, SettingsWriter(session))

        val res = statusRepo.pollFast(forceSlow = true)
        assertTrue(res is RouterResult.Ok)
        val state = (res as RouterResult.Ok).data

        assertEquals("10.8.42.19", state.wanIp)
        assertEquals("3 h 57 m", state.onlineDurationText)
        assertEquals("2 d 0 h", state.uptimeText)
        assertEquals(2, state.carriers.size)
        assertEquals("62120", state.cellIdentity.plmn)
        assertEquals("22", state.linkDetails.dlMcs4g)
        assertTrue(state.monthlyUsage.limitEnabled)
        assertEquals(264366.45, state.monthlyUsage.usedMb!!, 0.01)
        assertEquals(3, statusRepo.signalHistory.value.size)

        cmdsCalled.clear()
        clockMs += 1000L
        val alignRes = statusRepo.pollAlignment()
        assertTrue(alignRes is RouterResult.Ok)
        assertEquals(listOf(205), cmdsCalled)
        assertNotNull(statusRepo.alignmentSnapshot.value)
        assertEquals(-84f, statusRepo.alignmentSnapshot.value!!.primaryRsrp)

        val neighborRes = signalRepo.loadNeighborCells()
        assertTrue(neighborRes is RouterResult.Ok)
        assertEquals(3, signalRepo.neighborCells.value.size)
    }
}
