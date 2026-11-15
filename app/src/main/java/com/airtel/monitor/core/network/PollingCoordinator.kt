package com.airtel.monitor.core.network

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PollingMode {
    /** Standard dashboard polling (cmd 18/401/205 + device list + 20s slow cache). */
    NORMAL,
    /** Antenna alignment mode (1s cmd 205 only). */
    ALIGNMENT
}

/**
 * Single owner of polling timers. Automatically stops when the app leaves foreground
 * (via ProcessLifecycleOwner) or when the user pauses telemetry.
 */
class PollingCoordinator(
    private val pollIntervalProvider: () -> Long,
    private val onNormalTick: suspend (forceSlow: Boolean) -> Unit,
    private val onAlignmentTick: suspend () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    attachProcessLifecycle: Boolean = true
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loopJob: Job? = null

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _mode = MutableStateFlow(PollingMode.NORMAL)
    val mode: StateFlow<PollingMode> = _mode.asStateFlow()

    private var forceSlowNext = true

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            onForegroundChanged(true)
        }

        override fun onStop(owner: LifecycleOwner) {
            onForegroundChanged(false)
        }
    }

    init {
        if (attachProcessLifecycle) {
            // Must register on main thread per ProcessLifecycleOwner contract.
            CoroutineScope(Dispatchers.Main.immediate).launch {
                ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            }
        }
    }

    fun onForegroundChanged(inForeground: Boolean) {
        if (_isAppInForeground.value == inForeground) return
        _isAppInForeground.value = inForeground
        syncLoop()
    }

    fun setPaused(paused: Boolean) {
        if (_isPaused.value == paused) return
        _isPaused.value = paused
        syncLoop()
    }

    fun togglePause(): Boolean {
        val next = !_isPaused.value
        setPaused(next)
        return next
    }

    fun setMode(newMode: PollingMode) {
        if (_mode.value == newMode) return
        _mode.value = newMode
        restartLoop()
    }

    fun onIntervalChanged() {
        restartLoop()
    }

    fun triggerImmediateRefresh(includeSlow: Boolean = true) {
        if (includeSlow) forceSlowNext = true
        scope.launch {
            runOneTick()
        }
    }

    private fun syncLoop() {
        val shouldRun = _isAppInForeground.value && !_isPaused.value
        if (shouldRun) {
            if (loopJob?.isActive != true) {
                startLoop()
            }
        } else {
            loopJob?.cancel()
            loopJob = null
        }
    }

    private fun restartLoop() {
        loopJob?.cancel()
        loopJob = null
        syncLoop()
    }

    private fun startLoop() {
        loopJob = scope.launch {
            while (isActive && _isAppInForeground.value && !_isPaused.value) {
                runOneTick()
                val delayMs = when (_mode.value) {
                    PollingMode.ALIGNMENT -> 2000L
                    PollingMode.NORMAL -> pollIntervalProvider().coerceAtLeast(500L)
                }
                delay(delayMs)
            }
        }
    }

    private suspend fun runOneTick() {
        when (_mode.value) {
            PollingMode.ALIGNMENT -> onAlignmentTick()
            PollingMode.NORMAL -> {
                val forceSlow = forceSlowNext
                forceSlowNext = false
                onNormalTick(forceSlow)
            }
        }
    }

    fun shutdown() {
        loopJob?.cancel()
        loopJob = null
    }
}
