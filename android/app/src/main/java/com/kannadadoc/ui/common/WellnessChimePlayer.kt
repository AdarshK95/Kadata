package com.kannadadoc.ui.common

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthesizes and plays a subtle, warm, soothing three-note singing-bowl/kalimba chime
 * (C5 -> E5 -> G5) for the app-wide Wellness Break Reminder.
 * - Zero permissions and zero external audio files.
 * - Respects Silent / Vibrate mode and Android Do Not Disturb.
 */
object WellnessChimePlayer {

    const val SAMPLE_RATE_HZ = 22_050
    private const val TOTAL_DURATION_SEC = 1.75
    private const val MASTER_GAIN = 0.20

    private data class ChimeNote(
        val freqHz: Double,
        val startSec: Double,
        val decayRate: Double,
        val gain: Double
    )

    private val SOOTHING_TRIAD = listOf(
        ChimeNote(freqHz = 523.25, startSec = 0.00, decayRate = 3.2, gain = 0.78), // C5
        ChimeNote(freqHz = 659.25, startSec = 0.24, decayRate = 3.0, gain = 0.72), // E5
        ChimeNote(freqHz = 783.99, startSec = 0.48, decayRate = 2.6, gain = 0.68)  // G5
    )

    /**
     * Pure JVM-testable PCM synthesizer producing 16-bit mono samples with smooth cosine
     * attack/release envelopes (zero clicks/pops and guaranteed no clipping).
     */
    fun synthesizeSoothingChimePcm(sampleRate: Int = SAMPLE_RATE_HZ): ShortArray {
        val totalSamples = (sampleRate * TOTAL_DURATION_SEC).roundToInt()
        val samples = ShortArray(totalSamples)
        val attackSec = 0.035
        val tailFadeSec = 0.14

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            var mix = 0.0

            for (note in SOOTHING_TRIAD) {
                val localT = t - note.startSec
                if (localT < 0.0) continue

                val attack = if (localT < attackSec) {
                    0.5 * (1.0 - cos(PI * localT / attackSec))
                } else {
                    1.0
                }
                val decay = exp(-note.decayRate * localT)
                val envelope = attack * decay * note.gain

                // Warm fundamental + very soft octave shimmer for a natural bell tone
                val fundamental = sin(2.0 * PI * note.freqHz * localT)
                val harmonic2 = 0.18 * sin(2.0 * PI * (note.freqHz * 2.0) * localT) * exp(-2.5 * localT)
                mix += (fundamental + harmonic2) * envelope
            }

            // Smooth end fade-out to guarantee zero pop on AudioTrack stop
            val remaining = TOTAL_DURATION_SEC - t
            val endFade = if (remaining < tailFadeSec) {
                0.5 * (1.0 - cos(PI * (remaining.coerceAtLeast(0.0) / tailFadeSec)))
            } else {
                1.0
            }

            val scaled = (mix * MASTER_GAIN * endFade).coerceIn(-0.95, 0.95)
            samples[i] = (scaled * Short.MAX_VALUE).roundToInt().toShort()
        }
        return samples
    }

    /**
     * Plays the gentle chime off the main thread when the device is not silenced or in DND.
     */
    suspend fun playGentleChime(context: Context) = withContext(Dispatchers.Default) {
        if (!shouldPlaySound(context)) return@withContext
        val pcm = synthesizeSoothingChimePcm(SAMPLE_RATE_HZ)
        val bufferBytes = pcm.size * 2
        var track: AudioTrack? = null
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) return@withContext
            val written = track.write(pcm, 0, pcm.size)
            if (written <= 0) return@withContext
            track.play()
            delay((TOTAL_DURATION_SEC * 1000).toLong() + 120L)
        } catch (_: Throwable) {
            // Audio chime is strictly non-critical; never crash or block UX
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
        }
    }

    private fun shouldPlaySound(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return false
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val filter = nm?.currentInterruptionFilter
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        ) {
            return false
        }
        return true
    }
}
