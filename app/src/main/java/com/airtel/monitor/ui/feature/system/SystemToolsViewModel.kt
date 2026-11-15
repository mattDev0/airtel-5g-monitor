package com.airtel.monitor.ui.feature.system

import com.airtel.monitor.data.model.RouterFeatureFlags
import com.airtel.monitor.data.model.RouterTimeConfig
import com.airtel.monitor.data.model.WanLinkDetectConfig
import kotlinx.coroutines.flow.flowOf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.datastore.AppPreferencesSnapshot
import com.airtel.monitor.core.datastore.SpeedUnitPreference
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.core.util.SensitiveFormatters
import com.airtel.monitor.data.model.AboutRouterInfo
import com.airtel.monitor.data.model.LogGrade
import com.airtel.monitor.data.model.LogSettingsConfig
import com.airtel.monitor.data.model.ScheduledRebootConfig
import com.airtel.monitor.data.model.SelfCheckSnapshot
import com.airtel.monitor.data.model.SpeedTestPhase
import com.airtel.monitor.data.model.SpeedTestProgress
import com.airtel.monitor.data.remote.SpeedTestRunner
import com.airtel.monitor.data.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ActiveSystemSheet {
    SCHEDULED_REBOOT,
    LOGS,
    CHANGE_PASSWORD,
    ROUTER_TIME,
    WAN_LINK_DETECT
}

data class SystemToolsUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val aboutInfo: AboutRouterInfo? = null,
    val routerTime: String = "",
    val maskSensitive: Boolean = true,
    val speedUnit: SpeedUnitPreference = SpeedUnitPreference.MBPS,
    val revealedFields: Set<String> = emptySet(),
    val selfCheck: SelfCheckSnapshot? = null,
    val selfCheckLoading: Boolean = false,
    val selfCheckError: String? = null,
    val scheduledReboot: ScheduledRebootConfig? = null,
    val rebootScheduleSaving: Boolean = false,
    val logSettings: LogSettingsConfig? = null,
    val sysLogLines: List<String> = emptyList(),
    val logsLoading: Boolean = false,
    val logsSaving: Boolean = false,
    val logsError: String? = null,
    val passwordSaving: Boolean = false,
    val passwordError: String? = null,
    val speedTest: SpeedTestProgress = SpeedTestProgress(),
    val timeConfig: RouterTimeConfig? = null,
    val wanDetectConfig: WanLinkDetectConfig? = null,
    val featureFlags: RouterFeatureFlags = RouterFeatureFlags(),
    val sheetBusy: Boolean = false,
    val sheetError: String? = null,
    val activeSheet: ActiveSystemSheet? = null
)

class SystemToolsViewModel(
    private val systemRepository: SystemRepository,
    private val preferencesFlow: Flow<AppPreferencesSnapshot>,
    private val updateSavedPassword: (String) -> Unit,
    private val routerSession: RouterSession,
    private val speedTestRunner: SpeedTestRunner,
    private val featureFlagsFlow: Flow<RouterFeatureFlags> = flowOf(RouterFeatureFlags()),
    private val ensureFlagsLoaded: suspend () -> RouterFeatureFlags = { RouterFeatureFlags() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onStatusMessage: (String) -> Unit,
    private val onForcePollRefresh: () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(SystemToolsUiState(isLoading = true))
    val uiState: StateFlow<SystemToolsUiState> = _uiState.asStateFlow()

    private var speedTestJob: Job? = null

    init {
        viewModelScope.launch {
            preferencesFlow.collect { prefs ->
                _uiState.update {
                    it.copy(
                        maskSensitive = prefs.maskSensitive,
                        speedUnit = prefs.speedUnit
                    )
                }
            }
        }
        viewModelScope.launch {
            featureFlagsFlow.collect { flags ->
                _uiState.update { it.copy(featureFlags = flags) }
            }
        }
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val (aboutRes, timeRes, scheduleRes, logCfgRes) = withContext(ioDispatcher) {
                listOf(
                    systemRepository.loadAboutInfo(),
                    systemRepository.loadRouterTime(),
                    systemRepository.loadScheduledReboot(),
                    systemRepository.loadLogSettings()
                )
            }
            val flags = withContext(ioDispatcher) { ensureFlagsLoaded() }
            @Suppress("UNCHECKED_CAST")
            val typedAbout = aboutRes as RouterResult<AboutRouterInfo>
            @Suppress("UNCHECKED_CAST")
            val typedTime = timeRes as RouterResult<String>
            @Suppress("UNCHECKED_CAST")
            val typedSchedule = scheduleRes as RouterResult<ScheduledRebootConfig>
            @Suppress("UNCHECKED_CAST")
            val typedLogCfg = logCfgRes as RouterResult<LogSettingsConfig>

            val primaryError = when {
                typedAbout is RouterResult.NotLoggedIn -> typedAbout.errorMessage()
                typedAbout is RouterResult.Unreachable -> typedAbout.errorMessage()
                else -> null
            }

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    errorMessage = primaryError,
                    featureFlags = flags,
                    aboutInfo = (typedAbout as? RouterResult.Ok)?.data ?: state.aboutInfo,
                    routerTime = (typedTime as? RouterResult.Ok)?.data ?: state.routerTime,
                    scheduledReboot = (typedSchedule as? RouterResult.Ok)?.data ?: state.scheduledReboot,
                    logSettings = (typedLogCfg as? RouterResult.Ok)?.data ?: state.logSettings
                )
            }
        }
    }

    fun toggleReveal(fieldKey: String) {
        _uiState.update { state ->
            val next = if (fieldKey in state.revealedFields) {
                state.revealedFields - fieldKey
            } else {
                state.revealedFields + fieldKey
            }
            state.copy(revealedFields = next)
        }
    }

    fun openSheet(sheet: ActiveSystemSheet) {
        _uiState.update { it.copy(activeSheet = sheet, passwordError = null, logsError = null, sheetError = null) }
        when (sheet) {
            ActiveSystemSheet.LOGS -> loadSysLog()
            ActiveSystemSheet.ROUTER_TIME -> loadRouterTimeConfig()
            ActiveSystemSheet.WAN_LINK_DETECT -> loadWanLinkDetect()
            else -> Unit
        }
    }

    fun dismissSheet() {
        _uiState.update { it.copy(activeSheet = null, passwordError = null, logsError = null, sheetError = null) }
    }

    fun runSelfCheck() {
        if (_uiState.value.selfCheckLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(selfCheckLoading = true, selfCheckError = null) }
            val res = withContext(ioDispatcher) { systemRepository.runSelfCheck() }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update { it.copy(selfCheckLoading = false, selfCheck = res.data) }
                    onStatusMessage("Self-check completed")
                }
                else -> {
                    _uiState.update { it.copy(selfCheckLoading = false, selfCheckError = res.errorMessage()) }
                    onStatusMessage(res.errorMessage())
                }
            }
        }
    }

    fun saveScheduledReboot(
        config: ScheduledRebootConfig,
        onError: (String) -> Unit
    ) {
        if (_uiState.value.rebootScheduleSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(rebootScheduleSaving = true) }
            val res = withContext(ioDispatcher) { systemRepository.saveScheduledReboot(config) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update {
                        it.copy(
                            rebootScheduleSaving = false,
                            scheduledReboot = res.data,
                            activeSheet = null
                        )
                    }
                    onStatusMessage(
                        if (res.data.enabled) "Scheduled reboot saved"
                        else "Scheduled reboot disabled"
                    )
                }
                else -> {
                    _uiState.update { it.copy(rebootScheduleSaving = false) }
                    onError(res.errorMessage())
                }
            }
        }
    }

    fun loadSysLog() {
        viewModelScope.launch {
            _uiState.update { it.copy(logsLoading = true, logsError = null) }
            val (cfgRes, logRes) = withContext(ioDispatcher) {
                systemRepository.loadLogSettings() to systemRepository.loadSysLog()
            }
            _uiState.update { state ->
                state.copy(
                    logsLoading = false,
                    logSettings = (cfgRes as? RouterResult.Ok)?.data ?: state.logSettings,
                    sysLogLines = (logRes as? RouterResult.Ok)?.data ?: state.sysLogLines,
                    logsError = if (logRes is RouterResult.Ok) null else logRes.errorMessage()
                )
            }
        }
    }

    fun saveLogSettings(
        grade: LogGrade,
        sizeKbRaw: String,
        onError: (String) -> Unit
    ) {
        val validationErr = SensitiveFormatters.validateLogSizeKb(sizeKbRaw)
        if (validationErr != null) {
            onError(validationErr)
            return
        }
        val sizeKb = sizeKbRaw.trim().toInt()
        viewModelScope.launch {
            _uiState.update { it.copy(logsSaving = true, logsError = null) }
            val res = withContext(ioDispatcher) { systemRepository.saveLogSettings(grade, sizeKb) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update { it.copy(logsSaving = false, logSettings = res.data) }
                    onStatusMessage("Log settings saved")
                }
                else -> {
                    _uiState.update { it.copy(logsSaving = false) }
                    onError(res.errorMessage())
                }
            }
        }
    }

    fun clearSysLog() {
        if (_uiState.value.logsSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(logsSaving = true, logsError = null) }
            val res = withContext(ioDispatcher) { systemRepository.clearSysLog() }
            when (res) {
                is RouterResult.Ok -> {
                    val reload = withContext(ioDispatcher) { systemRepository.loadSysLog() }
                    _uiState.update {
                        it.copy(
                            logsSaving = false,
                            sysLogLines = (reload as? RouterResult.Ok)?.data ?: emptyList()
                        )
                    }
                    onStatusMessage("System logs cleared")
                }
                else -> {
                    _uiState.update { it.copy(logsSaving = false, logsError = res.errorMessage()) }
                    onStatusMessage(res.errorMessage())
                }
            }
        }
    }

    fun changeAdminPassword(
        oldPassword: String,
        newPassword: String,
        confirmPassword: String
    ) {
        val localError = SensitiveFormatters.validateRouterPassword(oldPassword, newPassword, confirmPassword)
        if (localError != null) {
            _uiState.update { it.copy(passwordError = localError) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(passwordSaving = true, passwordError = null) }
            val res = withContext(ioDispatcher) {
                systemRepository.changeAdminPassword(oldPassword, newPassword, confirmPassword)
            }
            when (res) {
                is RouterResult.Ok -> {
                    updateSavedPassword(newPassword)
                    routerSession.resetSession()
                    onForcePollRefresh()
                    _uiState.update { it.copy(passwordSaving = false, activeSheet = null) }
                    onStatusMessage("Router admin password updated")
                }
                else -> {
                    _uiState.update { it.copy(passwordSaving = false, passwordError = res.errorMessage()) }
                }
            }
        }
    }

    fun rebootRouter() {
        viewModelScope.launch {
            val res = withContext(ioDispatcher) { systemRepository.reboot() }
            when (res) {
                is RouterResult.Ok -> {
                    routerSession.resetSession()
                    onStatusMessage("Router reboot initiated")
                }
                else -> onStatusMessage(res.errorMessage())
            }
        }
    }

    fun loadRouterTimeConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) { systemRepository.loadRouterTimeConfig() }
            when (res) {
                is RouterResult.Ok -> _uiState.update {
                    it.copy(sheetBusy = false, timeConfig = res.data, routerTime = res.data.systemTime.ifBlank { it.routerTime })
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
    }

    fun saveRouterTimeConfig(config: RouterTimeConfig, manualDateTime: String) {
        if (_uiState.value.sheetBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) {
                systemRepository.saveRouterTimeConfig(config, manualDateTime)
            }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update {
                        it.copy(
                            sheetBusy = false,
                            timeConfig = res.data,
                            routerTime = res.data.systemTime.ifBlank { it.routerTime },
                            activeSheet = null
                        )
                    }
                    onStatusMessage("Time and NTP settings saved")
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
    }

    fun syncClientTime(clientTime: String) {
        if (_uiState.value.sheetBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) { systemRepository.syncClientTime(clientTime) }
            when (res) {
                is RouterResult.Ok -> {
                    val refreshed = withContext(ioDispatcher) { systemRepository.loadRouterTimeConfig() }
                    _uiState.update { state ->
                        val nextCfg = (refreshed as? RouterResult.Ok)?.data ?: state.timeConfig
                        state.copy(
                            sheetBusy = false,
                            timeConfig = nextCfg,
                            routerTime = nextCfg?.systemTime?.ifBlank { clientTime } ?: clientTime
                        )
                    }
                    onStatusMessage("Router clock synced to phone")
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
    }

    fun loadWanLinkDetect() {
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val flags = ensureFlagsLoaded()
            val res = withContext(ioDispatcher) { systemRepository.loadWanLinkDetect() }
            when (res) {
                is RouterResult.Ok -> _uiState.update {
                    it.copy(sheetBusy = false, wanDetectConfig = res.data, featureFlags = flags)
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage(), featureFlags = flags) }
            }
        }
    }

    fun saveWanLinkDetect(config: WanLinkDetectConfig) {
        if (_uiState.value.sheetBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val rescanAllowed = _uiState.value.featureFlags.wanDetectRescanAvailable
            val res = withContext(ioDispatcher) { systemRepository.saveWanLinkDetect(config, rescanAllowed) }
            when (res) {
                is RouterResult.Ok -> {
                    _uiState.update {
                        it.copy(sheetBusy = false, wanDetectConfig = res.data, activeSheet = null)
                    }
                    onStatusMessage("WAN link detection settings saved")
                }
                else -> _uiState.update { it.copy(sheetBusy = false, sheetError = res.errorMessage()) }
            }
        }
    }

    fun factoryReset() {
        if (_uiState.value.sheetBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(sheetBusy = true, sheetError = null) }
            val res = withContext(ioDispatcher) { systemRepository.factoryReset() }
            when (res) {
                is RouterResult.Ok -> {
                    routerSession.resetSession()
                    _uiState.update { it.copy(sheetBusy = false) }
                    onStatusMessage("Factory reset initiated — router is restarting")
                }
                else -> {
                    _uiState.update { it.copy(sheetBusy = false) }
                    onStatusMessage(res.errorMessage())
                }
            }
        }
    }

    fun startSpeedTest() {
        if (speedTestJob?.isActive == true) return
        speedTestJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    speedTest = SpeedTestProgress(
                        phase = SpeedTestPhase.PING,
                        progressFraction = 0.05f,
                        statusLabel = "Measuring ping..."
                    )
                )
            }
            try {
                val res = speedTestRunner.run { label, fraction, pingMs, jitterMs, dlMbps, ulMbps ->
                    val phase = when {
                        ulMbps != null -> SpeedTestPhase.UPLOAD
                        dlMbps != null -> SpeedTestPhase.DOWNLOAD
                        else -> SpeedTestPhase.PING
                    }
                    _uiState.update { state ->
                        state.copy(
                            speedTest = SpeedTestProgress(
                                phase = phase,
                                progressFraction = fraction,
                                pingMs = pingMs,
                                jitterMs = jitterMs,
                                downloadMbps = dlMbps,
                                uploadMbps = ulMbps,
                                statusLabel = label
                            )
                        )
                    }
                }
                when (res) {
                    is SpeedTestRunner.Result.Success -> {
                        _uiState.update {
                            it.copy(
                                speedTest = SpeedTestProgress(
                                    phase = SpeedTestPhase.COMPLETE,
                                    progressFraction = 1f,
                                    pingMs = res.pingMs,
                                    jitterMs = res.jitterMs,
                                    downloadMbps = res.downloadMbps,
                                    uploadMbps = res.uploadMbps,
                                    statusLabel = "Complete"
                                )
                            )
                        }
                    }
                    is SpeedTestRunner.Result.Error -> {
                        _uiState.update { state ->
                            state.copy(
                                speedTest = state.speedTest.copy(
                                    phase = SpeedTestPhase.ERROR,
                                    errorMessage = res.message,
                                    statusLabel = "Failed"
                                )
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                _uiState.update { state ->
                    state.copy(
                        speedTest = state.speedTest.copy(
                            phase = SpeedTestPhase.CANCELLED,
                            statusLabel = "Cancelled"
                        )
                    )
                }
            }
        }
    }

    fun cancelSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = null
    }

    override fun onCleared() {
        speedTestJob?.cancel()
    }

    companion object {
        fun factory(
            container: AppContainer,
            onForcePollRefresh: () -> Unit = {}
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SystemToolsViewModel(
                    systemRepository = container.systemRepository,
                    preferencesFlow = container.preferencesRepository.preferencesFlow,
                    updateSavedPassword = { newPass -> container.preferencesRepository.updateSettings(pass = newPass) },
                    routerSession = container.routerSession,
                    speedTestRunner = container.speedTestRunner,
                    featureFlagsFlow = container.featureFlagRepository.flags,
                    ensureFlagsLoaded = {
                        (container.featureFlagRepository.ensureLoaded() as? RouterResult.Ok)?.data
                            ?: container.featureFlagRepository.flags.value
                    },
                    onStatusMessage = container::emitStatusMessage,
                    onForcePollRefresh = onForcePollRefresh
                )
            }
        }
    }
}
