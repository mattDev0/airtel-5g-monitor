package com.airtel.monitor.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * User-triggered client-side speed test against Cloudflare (`speed.cloudflare.com/__down` / `__up`).
 * Only executed when the user explicitly taps Start.
 */
class SpeedTestRunner(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pingSamples: Int = 5,
    private val downloadBytes: Long = DEFAULT_DOWNLOAD_BYTES,
    private val uploadBytes: Int = DEFAULT_UPLOAD_BYTES
) {
    suspend fun run(
        onProgress: (phase: String, fraction: Float, pingMs: Double?, jitterMs: Double?, dlMbps: Double?, ulMbps: Double?) -> Unit = { _, _, _, _, _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val normalizedBase = baseUrl.trimEnd('/')
        val rtts = mutableListOf<Double>()

        for (i in 0 until pingSamples.coerceAtLeast(1)) {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder()
                .url("$normalizedBase/__down?bytes=0&t=${System.nanoTime()}")
                .get()
                .header("Cache-Control", "no-store")
                .build()
            val t0 = System.nanoTime()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.Error("Speed test latency probe returned HTTP ${resp.code}")
                    }
                    resp.body?.close()
                }
            } catch (e: IOException) {
                return@withContext Result.Error("Speed test failed: ${e.message ?: "Network error"}")
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            rtts += elapsedMs
            val currentPing = rtts.average()
            val currentJitter = computeJitter(rtts)
            onProgress(
                "Measuring ping...",
                (i + 1).toFloat() / pingSamples * 0.2f,
                currentPing,
                currentJitter,
                null,
                null
            )
        }

        val avgPing = rtts.average()
        val jitter = computeJitter(rtts)

        currentCoroutineContext().ensureActive()
        val dlMbps = try {
            measureDownloadMbps(normalizedBase, avgPing, jitter, onProgress)
        } catch (e: IOException) {
            return@withContext Result.Error("Download test failed: ${e.message ?: "Network error"}")
        }

        currentCoroutineContext().ensureActive()
        val ulMbps = try {
            measureUploadMbps(normalizedBase, avgPing, jitter, dlMbps, onProgress)
        } catch (e: IOException) {
            return@withContext Result.Error("Upload test failed: ${e.message ?: "Network error"}")
        }

        onProgress("Complete", 1f, avgPing, jitter, dlMbps, ulMbps)
        Result.Success(
            pingMs = avgPing,
            jitterMs = jitter,
            downloadMbps = dlMbps,
            uploadMbps = ulMbps
        )
    }

    private suspend fun measureDownloadMbps(
        normalizedBase: String,
        avgPing: Double,
        jitter: Double,
        onProgress: (phase: String, fraction: Float, pingMs: Double?, jitterMs: Double?, dlMbps: Double?, ulMbps: Double?) -> Unit
    ): Double {
        val req = Request.Builder()
            .url("$normalizedBase/__down?bytes=$downloadBytes&t=${System.nanoTime()}")
            .get()
            .header("Cache-Control", "no-store")
            .build()

        val startNs = System.nanoTime()
        var totalBytes = 0L
        var lastEmitNs = startNs

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}")
            }
            val source = resp.body?.source() ?: throw IOException("Empty download response")
            val buffer = ByteArray(64 * 1024)
            source.use {
                val input = it.inputStream()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                    val nowNs = System.nanoTime()
                    if (nowNs - lastEmitNs >= 120_000_000L) {
                        val elapsedSec = ((nowNs - startNs).coerceAtLeast(1L)) / 1_000_000_000.0
                        val mbps = (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)
                        val progress = 0.2f + (totalBytes.toFloat() / downloadBytes.coerceAtLeast(1L)).coerceIn(0f, 1f) * 0.4f
                        onProgress("Testing download...", progress, avgPing, jitter, mbps, null)
                        lastEmitNs = nowNs
                    }
                }
            }
        }
        val elapsedSec = ((System.nanoTime() - startNs).coerceAtLeast(1_000_000L)) / 1_000_000_000.0
        val finalMbps = (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)
        onProgress("Download measured", 0.6f, avgPing, jitter, finalMbps, null)
        return finalMbps
    }

    private suspend fun measureUploadMbps(
        normalizedBase: String,
        avgPing: Double,
        jitter: Double,
        dlMbps: Double,
        onProgress: (phase: String, fraction: Float, pingMs: Double?, jitterMs: Double?, dlMbps: Double?, ulMbps: Double?) -> Unit
    ): Double {
        val ctx = currentCoroutineContext()
        val startNs = System.nanoTime()
        var sentBytes = 0L
        var lastEmitNs = startNs

        val body = object : RequestBody() {
            override fun contentType() = OCTET_STREAM
            override fun contentLength(): Long = uploadBytes.toLong()
            override fun writeTo(sink: BufferedSink) {
                val chunk = ByteArray(32 * 1024) { 0x5A }
                var remaining = uploadBytes
                while (remaining > 0) {
                    ctx.ensureActive()
                    val toWrite = minOf(remaining, chunk.size)
                    sink.write(chunk, 0, toWrite)
                    remaining -= toWrite
                    sentBytes += toWrite
                    val nowNs = System.nanoTime()
                    if (nowNs - lastEmitNs >= 120_000_000L) {
                        val elapsedSec = ((nowNs - startNs).coerceAtLeast(1L)) / 1_000_000_000.0
                        val mbps = (sentBytes * 8.0) / (elapsedSec * 1_000_000.0)
                        val progress = 0.6f + (sentBytes.toFloat() / uploadBytes.coerceAtLeast(1)).coerceIn(0f, 1f) * 0.4f
                        onProgress("Testing upload...", progress, avgPing, jitter, dlMbps, mbps)
                        lastEmitNs = nowNs
                    }
                }
                sink.flush()
            }
        }

        val req = Request.Builder()
            .url("$normalizedBase/__up")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}")
            }
            resp.body?.close()
        }
        val elapsedSec = ((System.nanoTime() - startNs).coerceAtLeast(1_000_000L)) / 1_000_000_000.0
        return (uploadBytes.toLong() * 8.0) / (elapsedSec * 1_000_000.0)
    }

    sealed interface Result {
        data class Success(
            val pingMs: Double,
            val jitterMs: Double,
            val downloadMbps: Double,
            val uploadMbps: Double
        ) : Result

        data class Error(val message: String) : Result
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://speed.cloudflare.com"
        const val DEFAULT_DOWNLOAD_BYTES = 10_000_000L
        const val DEFAULT_UPLOAD_BYTES = 4_000_000
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()

        internal fun computeJitter(rtts: List<Double>): Double {
            if (rtts.size < 2) return 0.0
            var sumDiff = 0.0
            for (i in 1 until rtts.size) {
                sumDiff += abs(rtts[i] - rtts[i - 1])
            }
            return sumDiff / (rtts.size - 1)
        }
    }
}
