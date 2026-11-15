package com.airtel.monitor.data.remote

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.core.network.toKotlinResult
import com.airtel.monitor.data.model.DnsConfig
import com.airtel.monitor.data.model.LockSettings
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
import com.airtel.monitor.data.model.RouterState
import com.airtel.monitor.data.model.WifiRadioConfig
import com.airtel.monitor.data.repository.LanRepository
import com.airtel.monitor.data.repository.MobileRepository
import com.airtel.monitor.data.repository.SignalRepository
import com.airtel.monitor.data.repository.StatusRepository
import com.airtel.monitor.data.repository.SystemRepository
import com.airtel.monitor.data.repository.WifiRepository
import org.json.JSONObject

typealias RouterSettings = com.airtel.monitor.core.network.RouterSettings
typealias AliasLookup = com.airtel.monitor.core.network.AliasLookup
typealias RouterTransport = com.airtel.monitor.core.network.RouterTransport
typealias OkHttpRouterTransport = com.airtel.monitor.core.network.OkHttpRouterTransport
typealias BandMask = com.airtel.monitor.core.util.BandMask

/**
 * Compatibility facade delegating to the v3 session, SettingsWriter, and repositories.
 */
class ZltRouterClient(
    preferencesRepository: RouterSettings,
    aliasRepository: AliasLookup,
    transport: RouterTransport = OkHttpRouterTransport(),
    clock: () -> Long = System::currentTimeMillis
) {
    private val session = RouterSession(preferencesRepository, transport)
    private val writer = SettingsWriter(session)
    private val statusRepo = StatusRepository(session, aliasRepository, clock)
    private val signalRepo = SignalRepository(session, writer)
    private val wifiRepo = WifiRepository(session, writer)
    private val mobileRepo = MobileRepository(session, writer, statusRepo)
    private val lanRepo = LanRepository(session, writer)
    private val systemRepo = SystemRepository(session)

    val peakDlMbps: Double
        get() = statusRepo.peakDlMbps
    val peakUlMbps: Double
        get() = statusRepo.peakUlMbps

    fun resetState() = statusRepo.resetState()

    fun login(): Boolean = session.login().isOk

    fun query(
        cmdId: Int,
        method: String = "GET",
        extra: Map<String, Any> = emptyMap(),
        timeoutSeconds: Long = 4
    ): JSONObject = session.execute(cmdId, method, extra, timeoutSeconds).getOrNull() ?: JSONObject()

    fun queryRaw(
        cmdId: Int,
        method: String = "POST",
        extra: Map<String, Any> = emptyMap(),
        timeoutSeconds: Long = 5
    ): String = session.executeRaw(cmdId, method, extra, timeoutSeconds).getOrNull().orEmpty()

    fun pollMetrics(): Result<RouterState> = statusRepo.pollFast().toKotlinResult()

    fun reboot(): Boolean = systemRepo.reboot().isOk

    fun getDns(): Pair<Boolean, DnsConfig> = when (val res = lanRepo.loadDns()) {
        is RouterResult.Ok -> true to res.data
        else -> false to DnsConfig()
    }

    suspend fun setDns(primary: String, secondary: String): Result<String> =
        when (val res = lanRepo.saveDns(primary, secondary)) {
            is RouterResult.Ok -> Result.success("DNS updated and confirmed by router")
            is RouterResult.LimitedAccess -> Result.failure(IllegalStateException(res.message))
            else -> Result.failure(IllegalStateException(res.errorMessage()))
        }

    suspend fun setMobileData(on: Boolean): Result<String> = toMessageResult(mobileRepo.setMobileData(on))

    suspend fun setFlightMode(on: Boolean): Result<String> = toMessageResult(mobileRepo.setFlightMode(on))

    fun rescanNetwork(): Result<String> = toMessageResult(mobileRepo.rescanNetwork())

    fun getLockSettings(): Pair<Boolean, LockSettings> = when (val res = signalRepo.loadLockSettings()) {
        is RouterResult.Ok -> true to res.data
        else -> false to LockSettings()
    }

    suspend fun setBandLock(lock4g: Boolean, bands4g: Set<Int>, lock5g: Boolean, bands5g: Set<Int>): Result<String> =
        when (val res = signalRepo.saveBandLock(lock4g, bands4g, lock5g, bands5g)) {
            is RouterResult.Ok -> Result.success("Band lock saved and confirmed by router")
            is RouterResult.LimitedAccess -> Result.failure(IllegalStateException(res.message))
            else -> Result.failure(IllegalStateException(res.errorMessage()))
        }

    suspend fun setLteCellLock(enabled: Boolean, cells: List<LteLockCell>): Result<String> =
        when (val res = signalRepo.saveLteCellLock(enabled, cells)) {
            is RouterResult.Ok -> Result.success("${if (enabled && cells.isNotEmpty()) "4G cell lock" else "4G cell unlock"} saved and confirmed by router")
            is RouterResult.LimitedAccess -> Result.failure(IllegalStateException(res.message))
            else -> Result.failure(IllegalStateException(res.errorMessage()))
        }

    suspend fun setNrCellLock(enabled: Boolean, cells: List<NrLockCell>): Result<String> =
        when (val res = signalRepo.saveNrCellLock(enabled, cells)) {
            is RouterResult.Ok -> Result.success("${if (enabled && cells.isNotEmpty()) "5G cell lock" else "5G cell unlock"} saved and confirmed by router")
            is RouterResult.LimitedAccess -> Result.failure(IllegalStateException(res.message))
            else -> Result.failure(IllegalStateException(res.errorMessage()))
        }

    fun getWifiRadios(): Pair<Boolean, WifiRadioConfig> = when (val res = wifiRepo.loadWifiRadios()) {
        is RouterResult.Ok -> true to res.data
        else -> false to WifiRadioConfig()
    }

    suspend fun setWifi24g(enabled: Boolean): Result<String> = toMessageResult(wifiRepo.setWifi24g(enabled))

    suspend fun setWifi5g(enabled: Boolean): Result<String> = toMessageResult(wifiRepo.setWifi5g(enabled))

    fun startPing(target: String, pingTimes: Int = 4): Result<Unit> =
        when (val res = systemRepo.startPing(target, pingTimes)) {
            is RouterResult.Ok -> Result.success(Unit)
            else -> Result.failure(IllegalStateException(res.errorMessage()))
        }

    fun getPingOutput(): String = systemRepo.getPingOutput()

    fun stopPing(): Result<Unit> {
        systemRepo.stopPing()
        return Result.success(Unit)
    }

    fun startTrace(target: String): Result<Unit> =
        when (val res = systemRepo.startTrace(target)) {
            is RouterResult.Ok -> Result.success(Unit)
            else -> Result.failure(IllegalStateException(res.errorMessage()))
        }

    fun isTraceRunning(): Boolean = systemRepo.isTraceRunning()

    fun getTraceOutput(): String = systemRepo.getTraceOutput()

    fun stopTrace(): Result<Unit> {
        systemRepo.stopTrace()
        return Result.success(Unit)
    }

    private fun toMessageResult(res: RouterResult<String>): Result<String> = when (res) {
        is RouterResult.Ok -> Result.success(res.data)
        is RouterResult.LimitedAccess -> Result.failure(IllegalStateException(res.message))
        else -> Result.failure(IllegalStateException(res.errorMessage()))
    }
}
