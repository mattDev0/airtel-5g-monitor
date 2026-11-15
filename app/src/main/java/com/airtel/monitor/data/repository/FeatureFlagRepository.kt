package com.airtel.monitor.data.repository

import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.RouterSession
import com.airtel.monitor.data.model.RouterFeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fetches and caches `cmd 80` UI feature/visibility flags for the active router session.
 */
class FeatureFlagRepository(
    private val session: RouterSession
) {
    private val _flags = MutableStateFlow(RouterFeatureFlags())
    val flags: StateFlow<RouterFeatureFlags> = _flags.asStateFlow()

    fun reset() {
        _flags.value = RouterFeatureFlags()
    }

    /** Loads `cmd 80` if not yet cached for this session, or unconditionally when [force] is true. */
    fun ensureLoaded(force: Boolean = false): RouterResult<RouterFeatureFlags> {
        val current = _flags.value
        if (!force && current.isLoaded) {
            return RouterResult.Ok(current)
        }
        return when (val res = session.execute(80, method = "GET", timeoutSeconds = 5L)) {
            is RouterResult.Ok -> {
                val parsed = RouterFeatureFlags.fromJson80(res.data)
                _flags.value = parsed
                RouterResult.Ok(parsed)
            }
            is RouterResult.NotLoggedIn -> res
            is RouterResult.LimitedAccess -> res
            is RouterResult.Unreachable -> res
            is RouterResult.BadResponse -> res
        }
    }
}
