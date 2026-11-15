package com.airtel.monitor.ui.feature.devices

import com.airtel.monitor.core.util.OuiVendorLookup
import com.airtel.monitor.data.local.AliasStore
import com.airtel.monitor.data.model.DeviceFilter
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DevicePresenceFilter
import com.airtel.monitor.data.model.DeviceSort
import com.airtel.monitor.data.model.DeviceType
import com.airtel.monitor.data.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRepositoryAndViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeAliasStore : AliasStore {
        private val map = mutableMapOf<String, String>()
        override fun hasAlias(identifier: String): Boolean = !map[identifier.lowercase().trim()].isNullOrBlank()
        override fun saveAlias(identifier: String, alias: String) {
            val k = identifier.lowercase().trim()
            if (alias.isBlank()) map.remove(k) else map[k] = alias.trim()
        }

        override fun getAlias(identifier: String, fallback: String): String =
            map[identifier.lowercase().trim()] ?: fallback
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleRawDevices(): List<DeviceItem> = listOf(
        DeviceItem(
            mac = "00:17:F2:11:22:33",
            ip = "192.168.1.20",
            hostname = "iPhone",
            alias = "iPhone",
            type = DeviceType.PHONE,
            band = "5 GHz (Wi-Fi 6/ac)",
            ssid = "Airtel_5G",
            rssi = "-55",
            signalPercent = 63,
            txRateMbps = "1201",
            rxRateMbps = "960",
            interfaceName = "wlan",
            expires = "80000"
        ),
        DeviceItem(
            mac = "02:11:22:33:44:05",
            ip = "192.168.1.35",
            hostname = "Pixel-6",
            alias = "Pixel-6",
            type = DeviceType.PHONE,
            band = "2.4 GHz (Wi-Fi)",
            ssid = "Airtel_2G",
            rssi = "-68",
            signalPercent = 45,
            txRateMbps = "144",
            rxRateMbps = "144",
            interfaceName = "wlan",
            expires = "72000"
        ),
        DeviceItem(
            mac = "00:1A:11:AA:BB:CC",
            ip = "192.168.1.40",
            hostname = "Nest-Hub",
            alias = "Nest-Hub",
            type = DeviceType.DEVICE,
            band = "Wi-Fi",
            interfaceName = "wlan",
            expires = "3600" // Not in cmd 224/225 -> offline lease
        ),
        DeviceItem(
            mac = "00:1B:21:44:55:66",
            ip = "192.168.1.10",
            hostname = "workstation",
            alias = "workstation",
            type = DeviceType.DEVICE,
            band = "Ethernet (LAN)",
            signalPercent = 100,
            interfaceName = "lan",
            expires = "86000"
        )
    )

    private fun ouiLookup(): OuiVendorLookup = OuiVendorLookup.fromLines(
        sequenceOf(
            "0017F2,Apple",
            "001A11,Google",
            "001B21,Intel"
        )
    )

    @Test
    fun `repository enriches OUI vendor, offline leases, and reacts immediately to alias changes`() = runTest {
        val rawFlow = MutableStateFlow(sampleRawDevices())
        val aliases = FakeAliasStore()
        val repo = DeviceRepository(rawFlow, aliases, ouiLookup())

        val initial = repo.devices.first().associateBy { it.ip }

        val iphone = initial.getValue("192.168.1.20")
        assertEquals("Apple", iphone.vendor)
        assertFalse(iphone.isPrivateMac)
        assertTrue(iphone.isOnline)
        assertFalse(iphone.hasCustomAlias)

        val pixel = initial.getValue("192.168.1.35")
        assertEquals("", pixel.vendor)
        assertTrue(pixel.isPrivateMac)
        assertTrue(pixel.isOnline)

        val offline = initial.getValue("192.168.1.40")
        assertEquals("Google", offline.vendor)
        assertFalse(offline.isOnline)

        val wired = initial.getValue("192.168.1.10")
        assertEquals("Intel", wired.vendor)
        assertEquals(DeviceType.PC, wired.type)
        assertTrue(wired.isOnline)

        repo.saveAlias(iphone, "Matt's iPhone")
        val updated = repo.devices.first().associateBy { it.ip }.getValue("192.168.1.20")
        assertEquals("Matt's iPhone", updated.alias)
        assertTrue(updated.hasCustomAlias)

        repo.clearAlias(updated)
        val restored = repo.devices.first().associateBy { it.ip }.getValue("192.168.1.20")
        assertEquals("iPhone", restored.alias)
        assertFalse(restored.hasCustomAlias)
    }

    @Test
    fun `viewModel filters by presence, band, vendor search, and sorts accurately`() = runTest(testDispatcher) {
        val rawFlow = MutableStateFlow(sampleRawDevices())
        val repo = DeviceRepository(rawFlow, FakeAliasStore(), ouiLookup())
        val messages = mutableListOf<String>()
        val vm = DevicesViewModel(
            deviceRepository = repo,
            ioDispatcher = testDispatcher,
            onRefreshRequest = {},
            onStatusMessage = { messages.add(it) }
        )

        val collectJob = launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(4, vm.uiState.value.totalCount)
        assertEquals(3, vm.uiState.value.onlineCount)
        assertEquals(1, vm.uiState.value.offlineCount)

        // Default speed sort keeps highest Mbps online first
        assertEquals("192.168.1.20", vm.uiState.value.filteredDevices.first().ip)

        // Offline presence filter
        vm.setPresenceFilter(DevicePresenceFilter.OFFLINE)
        advanceUntilIdle()
        assertEquals(listOf("192.168.1.40"), vm.uiState.value.filteredDevices.map { it.ip })

        // Reset + vendor search
        vm.clearFilters()
        vm.setSearchQuery("Intel")
        advanceUntilIdle()
        assertEquals(listOf("192.168.1.10"), vm.uiState.value.filteredDevices.map { it.ip })

        // Band + IP sort
        vm.clearFilters()
        vm.setFilter(DeviceFilter.ALL)
        vm.setSort(DeviceSort.IP)
        advanceUntilIdle()
        assertEquals(
            listOf("192.168.1.10", "192.168.1.20", "192.168.1.35", "192.168.1.40"),
            vm.uiState.value.filteredDevices.map { it.ip }
        )

        // Live selected device updates on rename
        val target = vm.uiState.value.filteredDevices.first { it.ip == "192.168.1.35" }
        vm.selectDevice(target)
        vm.saveAlias(target, "Matt's Pixel 6")
        advanceUntilIdle()
        assertEquals("Matt's Pixel 6", vm.uiState.value.selectedDevice?.alias)
        assertTrue(messages.any { it.contains("Matt's Pixel 6") })

        collectJob.cancel()
    }
}
