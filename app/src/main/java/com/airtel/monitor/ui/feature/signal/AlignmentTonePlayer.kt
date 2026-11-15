package com.airtel.monitor.ui.feature.signal

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates short PCM sine pips whose pitch scales with SINR (-10..30 dB -> 330..1050 Hz).
 * Fails silently if the audio subsystem is unavailable.
 */
object AlignmentTonePlayer {
    private const val SAMPLE_RATE = 22050
    private const val DURATION_MS = 90

    fun sinrToFrequencyHz(sinr: Float?): Double {
        if (sinr == null) return 380.0
        val norm = ((sinr + 10f) / 40f).coerceIn(0f, 1f)
        return 330.0 + norm * 720.0
    }

    fun playPip(sinr: Float?) {
        val freq = sinrToFrequencyHz(sinr)
        val numSamples = (SAMPLE_RATE * DURATION_MS) / 1000
        val pcm = ShortArray(numSamples)
        val fadeSamples = (numSamples * 0.18).toInt().coerceAtLeast(1)

        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * freq * i / SAMPLE_RATE
            val env = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > numSamples - fadeSamples -> (numSamples - i).toDouble() / fadeSamples
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            pcm[i] = (sin(angle) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
        }

        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(numSamples * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            try {
                val written = track.write(pcm, 0, min(pcm.size, minBuf / 2))
                if (written > 0) {
                    track.play()
                    Thread.sleep(DURATION_MS.toLong() + 15L)
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        } catch (_: Throwable) {
            // Audio output optional on restricted or headless devices
        }
    }
}
