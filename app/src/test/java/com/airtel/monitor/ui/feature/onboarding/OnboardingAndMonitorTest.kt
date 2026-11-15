package com.airtel.monitor.ui.feature.onboarding

import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.network.GatewayDetector
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.WifiGatewayInfo
import com.airtel.monitor.core.util.NetworkFormatters
import com.airtel.monitor.data.model.CellularMetrics
import com.airtel.monitor.data.model.DailyUsageEntry
import com.airtel.monitor.data.model.DeviceItem
import com.airtel.monitor.data.model.DeviceType
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.domain.MonitorAlert
import com.airtel.monitor.domain.MonitorAlertConfig
import com.airtel.monitor.domain.MonitorAlertEvaluator
import com.airtel.monitor.domain.MonitorObservation
import com.airtel.monitor.domain.MonitorState
import com.airtel.monitor.domain.MonthlyUsageSummary
import com.airtel.monitor.widget.WidgetFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingAndMonitorTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `gateway detector picks ipv4 gateway and checks expected wifi subnet`() {
        val ipv6 = InetAddress.getByName("fe80::1")
        val loopback = InetAddress.getByName("127.0.0.1")
        val zero = InetAddress.getByName("0.0.0.0")
        val gw = InetAddress.getByName("192.168.8.1")

        val extracted = GatewayDetector.extractGatewayIpv4(
            routeGateways = listOf(ipv6, loopback, zero),
            dhcpServer = gw
        )
        assertEquals("192.168.8.1", extracted)

        assertNull(
            GatewayDetector.extractGatewayIpv4(
                routeGateways = listOf(ipv6, zero),
                dhcpServer = null
            )
        )

        assertTrue(GatewayDetector.isOnExpectedRouterWifi(true, "192.168.1.1", "192.168.1.1"))
        assertTrue(GatewayDetector.isOnExpectedRouterWifi(true, "192.168.1.254", "192.168.1.1"))
        assertFalse(GatewayDetector.isOnExpectedRouterWifi(true, "10.0.0.1", "192.168.1.1"))
        assertFalse(GatewayDetector.isOnExpectedRouterWifi(false, "192.168.1.1", "192.168.1.1"))
    }

    @Test
    fun `monitor evaluator fires quota once per cycle and resets on new cycle`() {
        val config = MonitorAlertConfig(
            notifyQuotaWarning = true,
            notifyNewSms = false,
            notifyNewDevice = false,
            notifyUnreachable = false
        )
        val first = MonitorAlertEvaluator.evaluate(
            previous = MonitorState(),
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = 82,
                usageWarnPercent = 80,
                cycleKey = "cycle-2026-11",
                smsUnreadCount = 0,
                activeDeviceMacs = emptySet()
            )
        )
        assertEquals(listOf(MonitorAlert.QuotaWarning(82, 80)), first.alerts)
        assertEquals("cycle-2026-11", first.nextState.lastQuotaAlertCycleKey)

        // Same cycle stays suppressed
        val second = MonitorAlertEvaluator.evaluate(
            previous = first.nextState,
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = 91,
                usageWarnPercent = 80,
                cycleKey = "cycle-2026-11",
                smsUnreadCount = 0,
                activeDeviceMacs = emptySet()
            )
        )
        assertTrue(second.alerts.isEmpty())

        // New cycle fires again
        val third = MonitorAlertEvaluator.evaluate(
            previous = second.nextState,
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = 80,
                usageWarnPercent = 80,
                cycleKey = "cycle-2026-12",
                smsUnreadCount = 0,
                activeDeviceMacs = emptySet()
            )
        )
        assertEquals(listOf(MonitorAlert.QuotaWarning(80, 80)), third.alerts)
    }

    @Test
    fun `monitor evaluator tracks sms watermark and opt-in new devices`() {
        val config = MonitorAlertConfig(
            notifyQuotaWarning = false,
            notifyNewSms = true,
            notifyNewDevice = true,
            notifyUnreachable = false
        )
        // First observation seeds SMS watermark and device MAC baseline silently
        val seed = MonitorAlertEvaluator.evaluate(
            previous = MonitorState(),
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = null,
                usageWarnPercent = null,
                cycleKey = "",
                smsUnreadCount = 1,
                activeDeviceMacs = setOf("aa:bb:cc:11:22:33")
            )
        )
        assertTrue(seed.alerts.isEmpty())
        assertTrue(seed.nextState.deviceBaselineInitialized)
        assertEquals(1, seed.nextState.lastNotifiedSmsUnreadCount)

        // Increase unread SMS + new MAC (different case) notifies once
        val spike = MonitorAlertEvaluator.evaluate(
            previous = seed.nextState,
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = null,
                usageWarnPercent = null,
                cycleKey = "",
                smsUnreadCount = 3,
                activeDeviceMacs = setOf("AA:BB:CC:11:22:33", "dd:ee:ff:44:55:66")
            )
        )
        assertEquals(
            listOf(
                MonitorAlert.NewSms(deltaCount = 2, totalUnread = 3),
                MonitorAlert.NewDevices(setOf("DD:EE:FF:44:55:66"))
            ),
            spike.alerts
        )

        // Clearing unread SMS resets watermark cleanly, then 2 new SMS alerts cleanly
        val cleared = MonitorAlertEvaluator.evaluate(
            previous = spike.nextState,
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = null,
                usageWarnPercent = null,
                cycleKey = "",
                smsUnreadCount = 0,
                activeDeviceMacs = setOf("DD:EE:FF:44:55:66")
            )
        )
        assertTrue(cleared.alerts.isEmpty())
        assertEquals(0, cleared.nextState.lastNotifiedSmsUnreadCount)

        val nextArrival = MonitorAlertEvaluator.evaluate(
            previous = cleared.nextState,
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = null,
                usageWarnPercent = null,
                cycleKey = "",
                smsUnreadCount = 2,
                activeDeviceMacs = setOf("DD:EE:FF:44:55:66")
            )
        )
        assertEquals(listOf(MonitorAlert.NewSms(deltaCount = 2, totalUnread = 2)), nextArrival.alerts)

        // Disabling opt-in device alerts clears baseline without firing
        val optOut = MonitorAlertEvaluator.evaluate(
            previous = nextArrival.nextState,
            config = config.copy(notifyNewDevice = false),
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = null,
                usageWarnPercent = null,
                cycleKey = "",
                smsUnreadCount = 2,
                activeDeviceMacs = setOf("11:22:33:44:55:66")
            )
        )
        assertTrue(optOut.alerts.isEmpty())
        assertFalse(optOut.nextState.deviceBaselineInitialized)
        assertTrue(optOut.nextState.knownDeviceMacs.isEmpty())
    }

    @Test
    fun `monitor evaluator requires two consecutive failures for unreachable and notifies recovery`() {
        val config = MonitorAlertConfig(
            notifyQuotaWarning = false,
            notifyNewSms = false,
            notifyNewDevice = false,
            notifyUnreachable = true
        )
        val fail1 = MonitorAlertEvaluator.evaluate(
            previous = MonitorState(),
            config = config,
            observation = MonitorObservation.PollFailure
        )
        assertTrue(fail1.alerts.isEmpty())
        assertEquals(1, fail1.nextState.consecutiveUnreachableCount)
        assertFalse(fail1.nextState.isUnreachableAlertActive)

        val fail2 = MonitorAlertEvaluator.evaluate(
            previous = fail1.nextState,
            config = config,
            observation = MonitorObservation.PollFailure
        )
        assertEquals(listOf(MonitorAlert.RouterUnreachable), fail2.alerts)
        assertTrue(fail2.nextState.isUnreachableAlertActive)

        // 3rd consecutive failure stays deduplicated
        val fail3 = MonitorAlertEvaluator.evaluate(
            previous = fail2.nextState,
            config = config,
            observation = MonitorObservation.PollFailure
        )
        assertTrue(fail3.alerts.isEmpty())

        // Recovery clears failure count and alerts once
        val recovered = MonitorAlertEvaluator.evaluate(
            previous = fail3.nextState,
            config = config,
            observation = MonitorObservation.PollSuccess(
                usageUsedPercent = null,
                usageWarnPercent = null,
                cycleKey = "",
                smsUnreadCount = 0,
                activeDeviceMacs = emptySet()
            )
        )
        assertEquals(listOf(MonitorAlert.RouterRecovered), recovered.alerts)
        assertEquals(0, recovered.nextState.consecutiveUnreachableCount)
        assertFalse(recovered.nextState.isUnreachableAlertActive)
    }

    @Test
    fun `speed formatter and widget formatter render units and snapshots accurately`() {
        assertEquals("80.00" to "Mbps", NetworkFormatters.formatThroughput(80.0, SpeedUnitPreference.MBPS))
        assertEquals("10.00" to "MB/s", NetworkFormatters.formatThroughput(80.0, SpeedUnitPreference.MBS))
        assertEquals("Peak 64.0M", NetworkFormatters.formatPeakThroughput(64.0, SpeedUnitPreference.MBPS))
        assertEquals("Peak 8.00 MB/s", NetworkFormatters.formatPeakThroughput(64.0, SpeedUnitPreference.MBS))

        val now = 1_700_000_000_000L
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val routerState = RouterState(
            cellular = CellularMetrics(
                networkType = "NR5G_NSA",
                signalLvl = 4,
                rsrp5g = "-86",
                rsrp4g = "-95"
            ),
            monthlyUsage = MonthlyUsageSummary(usedMb = 25600.0),
            devices = listOf(
                DeviceItem(mac = "00:11", ip = "192.168.1.1", hostname = "GW", alias = "", type = DeviceType.ROUTER, band = "LAN", isRouter = true, isOnline = true),
                DeviceItem(mac = "00:12", ip = "192.168.1.20", hostname = "Pixel", alias = "", type = DeviceType.PHONE, band = "5G", isOnline = true),
                DeviceItem(mac = "00:13", ip = "192.168.1.21", hostname = "Mac", alias = "", type = DeviceType.PC, band = "5G", isOnline = true),
                DeviceItem(mac = "00:14", ip = "192.168.1.22", hostname = "Old", alias = "", type = DeviceType.PC, band = "2.4G", isOnline = false)
            )
        )
        val snap = WidgetFormatter.buildSnapshot(
            state = routerState,
            dailyEntries = listOf(DailyUsageEntry(dateIso = todayIso, dailyMb = 512.0, endTotalMb = 25600.0, baselineMb = 25088.0)),
            nowMillis = now
        )
        assertEquals("NR5G_NSA", snap.networkType)
        assertEquals(4, snap.signalBars)
        assertEquals("-86 dBm", snap.rsrpText)
        assertEquals("25.0 GB", snap.monthlyDataText)
        assertEquals("512 MB", snap.todayDataText)
        assertEquals(2, snap.deviceCount)
        assertTrue(snap.connected)
    }

    @Test
    fun `onboarding view model prefills detected gateway handles probes and saves settings`() = runTest(testDispatcher) {
        var appliedArgs: List<Any>? = null
        var probeResult: RouterResult<String> = RouterResult.NotLoggedIn("bad creds")

        val vm = OnboardingViewModel(
            initialHost = "192.168.1.1",
            initialUsername = "user",
            initialPassword = "",
            gatewayInfo = WifiGatewayInfo(isWifiConnected = true, gatewayIp = "192.168.8.1"),
            testProbe = { _, _, _ -> probeResult },
            onApplySettings = { host, user, pass, done ->
                appliedArgs = listOf(host, user, pass, done)
            },
            ioDispatcher = testDispatcher
        )

        assertEquals("192.168.8.1", vm.uiState.value.routerHost)
        vm.updatePassword("secret123")
        vm.testConnection()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.testStatus is OnboardingTestStatus.WrongPassword)

        probeResult = RouterResult.Unreachable("timeout")
        vm.testConnection()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.testStatus is OnboardingTestStatus.Unreachable)

        probeResult = RouterResult.Ok("W304VA PRO")
        vm.testConnection()
        advanceUntilIdle()
        val connected = vm.uiState.value.testStatus as? OnboardingTestStatus.Connected
        assertEquals("W304VA PRO", connected?.deviceLabel)

        var doneCalled = false
        vm.saveAndContinue { doneCalled = true }
        assertTrue(doneCalled)
        assertEquals(listOf("192.168.8.1", "user", "secret123", true), appliedArgs)
    }
}
