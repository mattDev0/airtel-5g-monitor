package com.airtel.monitor.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Connection settings read on each router request. */
interface RouterSettings {
    val routerHost: String
    val username: String
    val password: String
}

/** Device-name overrides; implemented by AliasRepository. */
fun interface AliasLookup {
    fun getAlias(identifier: String, fallback: String): String
}

/** Posts one JSON request to the router CGI and returns the raw reply body. */
fun interface RouterTransport {
    fun post(url: String, body: String, timeoutSeconds: Long): String
}

class OkHttpRouterTransport : RouterTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        // Off: OkHttp would silently resend a POST (reboot, DNS, Wi-Fi) after a
        // connection failure, and the router may already have acted on it.
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMediaType = "application/json; charset=UTF-8".toMediaType()

    override fun post(url: String, body: String, timeoutSeconds: Long): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ZLT-Monitor/3.0")
            .build()
        val call = client.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
            .newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                return JSONObject().put("error", "HTTP ${response.code}").toString()
            }
            return response.body?.string() ?: "{}"
        }
    }
}
