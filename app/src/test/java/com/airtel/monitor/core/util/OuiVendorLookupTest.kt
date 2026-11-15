package com.airtel.monitor.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OuiVendorLookupTest {

    private fun loadBundledLookup(): OuiVendorLookup {
        val candidates = listOf(
            File("src/main/assets/oui.csv"),
            File("app/src/main/assets/oui.csv")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate bundled assets/oui.csv from ${File(".").absolutePath}")
        return file.bufferedReader(Charsets.UTF_8).use { reader ->
            OuiVendorLookup.fromLines(reader.lineSequence())
        }
    }

    @Test
    fun `bundled oui csv loads well over 300 real vendor entries`() {
        val lookup = loadBundledLookup()
        assertTrue("Expected > 300 OUI entries, found ${lookup.size}", lookup.size > 300)

        assertEquals("Apple", lookup.lookup("00:17:F2:AA:BB:CC").vendor)
        assertEquals("Google", lookup.lookup("00-1a-11-22-33-44").vendor)
        assertEquals("ZTE", lookup.lookup("0015EB112233").vendor)
    }

    @Test
    fun `locally administered unicast MACs are marked private when unknown`() {
        val lookup = OuiVendorLookup.fromLines(sequenceOf("001A11,Google"))

        val randomized = lookup.lookup("02:11:22:33:44:01")
        assertNull(randomized.vendor)
        assertTrue(randomized.isPrivateMac)

        val globalUnknown = lookup.lookup("00:99:88:77:66:55")
        assertNull(globalUnknown.vendor)
        assertFalse(globalUnknown.isPrivateMac)

        val multicast = lookup.lookup("01:00:5E:00:00:01")
        assertNull(multicast.vendor)
        assertFalse(multicast.isPrivateMac)
    }

    @Test
    fun `formatMac standardizes separators and case`() {
        assertEquals("AA:BB:CC:DD:EE:FF", OuiVendorLookup.formatMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("00:1A:11:22:33:44", OuiVendorLookup.formatMac("001a11223344"))
    }
}
