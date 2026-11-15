package com.airtel.monitor.core.network

/** Typed outcome for router requests and verified settings writes. */
sealed interface RouterResult<out T> {
    data class Ok<T>(val data: T) : RouterResult<T>
    data class NotLoggedIn(val message: String = "Authentication required or failed") : RouterResult<Nothing>
    data class LimitedAccess(val cmd: Int, val message: String = "LIMITED_ACCESS") : RouterResult<Nothing>
    data class Unreachable(val message: String = "Router unreachable", val cause: Throwable? = null) : RouterResult<Nothing>
    data class BadResponse(val message: String, val rawBody: String = "") : RouterResult<Nothing>

    val isOk: Boolean
        get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.data
}

class RouterException(val error: RouterResult<Nothing>) : Exception(error.errorMessage(), (error as? RouterResult.Unreachable)?.cause)

fun RouterResult<*>.errorMessage(): String = when (this) {
    is RouterResult.Ok -> ""
    is RouterResult.NotLoggedIn -> message
    is RouterResult.LimitedAccess -> "Command $cmd is restricted by the router ($message)"
    is RouterResult.Unreachable -> message
    is RouterResult.BadResponse -> message
}

inline fun <T, R> RouterResult<T>.map(transform: (T) -> R): RouterResult<R> = when (this) {
    is RouterResult.Ok -> RouterResult.Ok(transform(data))
    is RouterResult.NotLoggedIn -> this
    is RouterResult.LimitedAccess -> this
    is RouterResult.Unreachable -> this
    is RouterResult.BadResponse -> this
}

inline fun <T> RouterResult<T>.onOk(action: (T) -> Unit): RouterResult<T> {
    if (this is RouterResult.Ok) action(data)
    return this
}

fun <T> RouterResult<T>.toKotlinResult(): Result<T> = when (this) {
    is RouterResult.Ok -> Result.success(data)
    is RouterResult.NotLoggedIn -> Result.failure(RouterException(this))
    is RouterResult.LimitedAccess -> Result.failure(RouterException(this))
    is RouterResult.Unreachable -> Result.failure(RouterException(this))
    is RouterResult.BadResponse -> Result.failure(RouterException(this))
}
