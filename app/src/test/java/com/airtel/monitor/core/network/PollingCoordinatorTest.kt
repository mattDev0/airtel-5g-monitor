package com.airtel.monitor.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollingCoordinatorTest {

    @Test
    fun `pauses on background and user pause, resumes on foreground`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var normalTicks = 0
        var alignmentTicks = 0

        val coordinator = PollingCoordinator(
            pollIntervalProvider = { 2000L },
            onNormalTick = { normalTicks++ },
            onAlignmentTick = { alignmentTicks++ },
            dispatcher = dispatcher,
            attachProcessLifecycle = false
        )

        // Starts paused until foreground
        runCurrent()
        assertEquals(0, normalTicks)

        coordinator.onForegroundChanged(true)
        runCurrent()
        assertEquals(1, normalTicks)

        advanceTimeBy(2000L)
        runCurrent()
        assertEquals(2, normalTicks)

        // User pause stops ticks
        coordinator.setPaused(true)
        advanceTimeBy(4000L)
        runCurrent()
        assertEquals(2, normalTicks)

        // Resume + switch to alignment mode (1s ticks)
        coordinator.setPaused(false)
        coordinator.setMode(PollingMode.ALIGNMENT)
        runCurrent()
        assertEquals(1, alignmentTicks)

        advanceTimeBy(1000L)
        runCurrent()
        assertEquals(2, alignmentTicks)

        // Background stops alignment mode too
        coordinator.onForegroundChanged(false)
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(2, alignmentTicks)

        coordinator.shutdown()
    }
}
