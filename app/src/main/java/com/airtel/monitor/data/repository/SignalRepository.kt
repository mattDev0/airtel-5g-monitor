package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.core.network.SettingsWriter
import com.airtel.monitor.core.network.WriteSpec
import com.airtel.monitor.core.util.BandMask
import com.airtel.monitor.data.dto.parseBandLock
import com.airtel.monitor.data.dto.parseCellLock
import com.airtel.monitor.data.model.BandLockConfig
import com.airtel.monitor.data.model.CellLockConfig
import com.airtel.monitor.data.model.LockSettings
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val SUL_ONLY_5G_BANDS = setOf(75, 76, 80, 81, 82, 83, 84, 86)

class SignalRepository(
    private val session: RouterSession,
    private val writer: SettingsWriter
) {
    private val _lockSettings = MutableStateFlow<LockSettings?>(null)
    val lockSettings: StateFlow<LockSettings?> = _lockSettings.asStateFlow()

    fun loadLockSettings(): RouterResult<LockSettings> {
        val bandRes = session.execute(161)
        val cellRes = session.execute(160)
        val band = bandRes.getOrNull()
        val cell = cellRes.getOrNull()
        val bandOk = band != null && (band.optBoolean("success", false) || band.has("all_band_4g"))
        val cellOk = cell != null && (cell.optBoolean("success", false) || cell.has("lte_lock_sw"))

        if (!bandOk && !cellOk) {
            return when {
                bandRes is RouterResult.NotLoggedIn -> bandRes
                cellRes is RouterResult.NotLoggedIn -> cellRes
                bandRes is RouterResult.Unreachable -> bandRes
                cellRes is RouterResult.Unreachable -> cellRes
                else -> RouterResult.BadResponse("Could not read lock settings from router")
            }
        }

        val parsed = LockSettings(
            bands = band?.let { parseBandLock(it) } ?: BandLockConfig(),
            cells = cell?.let { parseCellLock(it) } ?: CellLockConfig()
        )
        _lockSettings.value = parsed
        return RouterResult.Ok(parsed)
    }

    suspend fun saveBandLock(
        lock4g: Boolean,
        bands4g: Set<Int>,
        lock5g: Boolean,
        bands5g: Set<Int>
    ): RouterResult<BandLockConfig> {
        if (lock4g && bands4g.isEmpty()) {
            return RouterResult.BadResponse("Pick at least one 4G band")
        }
        if (lock5g && bands5g.isEmpty()) {
            return RouterResult.BadResponse("Pick at least one 5G band")
        }
        if (lock5g && bands5g.all { it in SUL_ONLY_5G_BANDS }) {
            return RouterResult.BadResponse("A 5G lock can't use supplementary uplink bands only")
        }

        val res = writer.write(
            WriteSpec(
                cmd = 161,
                preflight = { current ->
                    val now = parseBandLock(current)
                    val bad4g = (bands4g - now.supported4g.toSet()).firstOrNull()
                    if (bad4g != null) {
                        RouterResult.BadResponse("This router doesn't support 4G band B$bad4g")
                    } else {
                        val bad5g = (bands5g - now.supported5g.toSet()).firstOrNull()
                        if (bad5g != null) {
                            RouterResult.BadResponse("This router doesn't support 5G band n$bad5g")
                        } else null
                    }
                },
                buildPayload = {
                    mapOf(
                        "band4gRadio" to if (lock4g) "1" else "0",
                        "band5gRadio" to if (lock5g) "1" else "0",
                        "lock4gBand" to BandMask.encode(bands4g),
                        "lock5gBand" to BandMask.encode(bands5g)
                    )
                },
                verify = { readBack ->
                    val b = parseBandLock(readBack)
                    b.lock4gEnabled == lock4g && b.lock5gEnabled == lock5g &&
                        (!lock4g || b.locked4g == bands4g) && (!lock5g || b.locked5g == bands5g)
                },
                parseResult = { parseBandLock(it) },
                verifyRetries = 8,
                verifyDelayMs = 2000L
            )
        )
        if (res is RouterResult.Ok) {
            _lockSettings.value = (_lockSettings.value ?: LockSettings()).copy(bands = res.data)
        }
        return res
    }

    suspend fun saveLteCellLock(enabled: Boolean, cells: List<LteLockCell>): RouterResult<CellLockConfig> {
        val clean = cells.map { LteLockCell(it.earfcn.trim(), it.pci.trim()) }
        if (enabled) {
            if (clean.isEmpty()) return RouterResult.BadResponse("Add at least one 4G cell")
            clean.forEach { c ->
                if (c.earfcn.toLongOrNull()?.let { it in 0..262143 } != true) {
                    return RouterResult.BadResponse("4G EARFCN must be a number from 0 to 262143")
                }
                if (c.pci.isNotEmpty() && c.pci.toIntOrNull()?.let { it in 0..503 } != true) {
                    return RouterResult.BadResponse("4G PCI must be from 0 to 503, or blank")
                }
            }
            if (clean.distinct().size != clean.size) {
                return RouterResult.BadResponse("The same 4G cell is listed twice")
            }
        }

        val on = enabled && clean.isNotEmpty()
        val res = writer.write(
            WriteSpec(
                cmd = 160,
                buildPayload = {
                    mapOf(
                        "subcmd" to 0,
                        "lte_lock_sw" to if (on) "1" else "0",
                        "lte_lock_freq" to if (on) clean.joinToString(",") { it.earfcn } else "",
                        "lte_lock_pci" to if (on) clean.joinToString(",") { it.pci } else ""
                    )
                },
                verify = { readBack ->
                    val c = parseCellLock(readBack)
                    c.lteEnabled == on && (!on || c.lteCells == clean)
                },
                parseResult = { parseCellLock(it) },
                verifyRetries = 8,
                verifyDelayMs = 2000L
            )
        )
        if (res is RouterResult.Ok) {
            _lockSettings.value = (_lockSettings.value ?: LockSettings()).copy(cells = res.data)
        }
        return res
    }

    suspend fun saveNrCellLock(enabled: Boolean, cells: List<NrLockCell>): RouterResult<CellLockConfig> {
        val clean = cells.map { NrLockCell(it.band.trim(), it.arfcn.trim(), it.pci.trim()) }
        if (enabled) {
            if (clean.isEmpty()) return RouterResult.BadResponse("Add at least one 5G cell")
            clean.forEach { c ->
                if (c.band.toIntOrNull()?.let { it in 1..264 } != true) {
                    return RouterResult.BadResponse("5G band must be a number from 1 to 264 (e.g. 78 for n78)")
                }
                if (c.arfcn.toLongOrNull()?.let { it in 1..3279165 } != true) {
                    return RouterResult.BadResponse("5G ARFCN must be a number from 1 to 3279165")
                }
                if (c.pci.isNotEmpty() && c.pci.toIntOrNull()?.let { it in 0..1007 } != true) {
                    return RouterResult.BadResponse("5G PCI must be from 0 to 1007, or blank")
                }
            }
            if (clean.distinct().size != clean.size) {
                return RouterResult.BadResponse("The same 5G cell is listed twice")
            }
        }

        val on = enabled && clean.isNotEmpty()
        val res = writer.write(
            WriteSpec(
                cmd = 160,
                buildPayload = {
                    mapOf(
                        "subcmd" to 1,
                        "nr_lock_sw" to if (on) "1" else "0",
                        "nr_lock_freq" to if (on) clean.joinToString(",") { it.arfcn } else "",
                        "nr_lock_pci" to if (on) clean.joinToString(",") { it.pci } else "",
                        "nr_lock_cell_band" to if (on) clean.joinToString(",") { it.band } else ""
                    )
                },
                verify = { readBack ->
                    val c = parseCellLock(readBack)
                    c.nrEnabled == on && (!on || c.nrCells == clean)
                },
                parseResult = { parseCellLock(it) },
                verifyRetries = 8,
                verifyDelayMs = 2000L
            )
        )
        if (res is RouterResult.Ok) {
            _lockSettings.value = (_lockSettings.value ?: LockSettings()).copy(cells = res.data)
        }
        return res
    }
}
