package com.toolkit.privacy

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Streams a continuous sine tone (optionally a sweep) so speakers, hearing range and room resonances can be tested. */
class ToneGen {
    @Volatile private var running = false
    @Volatile var freq = 440.0
    @Volatile var sweepTo = 0.0
    @Volatile var sweepSeconds = 10.0
    @Volatile var volume = 0.4f
    @Volatile var left = true
    @Volatile var right = true
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val rate = 44100
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("DEPRECATION")
            val track = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192), AudioTrack.MODE_STREAM)
            track.play()
            val buf = ShortArray(2048)
            var phase = 0.0
            var t = 0.0
            val start = freq
            while (running) {
                for (i in 0 until buf.size / 2) {
                    val f = if (sweepTo > 0) start + (sweepTo - start) * ((t / sweepSeconds) % 1.0) else freq
                    phase += 2 * PI * f / rate
                    if (phase > 2 * PI) phase -= 2 * PI
                    val s = (sin(phase) * volume * 32000).toInt().toShort()
                    buf[2 * i] = if (left) s else 0
                    buf[2 * i + 1] = if (right) s else 0
                    t += 1.0 / rate
                }
                track.write(buf, 0, buf.size)
            }
            try { track.stop(); track.release() } catch (e: Exception) { }
        }.also { it.start() }
    }

    fun stop() { running = false }
    val isRunning get() = running
}

/** Microphone loudness meter. Values are dB relative to full scale plus an adjustable calibration offset to approximate dB SPL. */
class SoundMeter(private val onLevel: (db: Double, peakDb: Double) -> Unit) {
    @Volatile private var running = false
    @Volatile var calibration = 90.0
    private var thread: Thread? = null

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2) } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        running = true
        thread = Thread {
            val buf = ShortArray(rate / 8)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var sum = 0.0; var peak = 0
                        for (i in 0 until n) { val v = buf[i].toInt(); sum += v.toDouble() * v; if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
                        val rms = sqrt(sum / n).coerceAtLeast(1.0)
                        val db = 20 * log10(rms / 32768.0) + calibration
                        val pk = 20 * log10(peak.coerceAtLeast(1) / 32768.0) + calibration
                        onLevel(db.coerceIn(0.0, 140.0), pk.coerceIn(0.0, 140.0))
                    }
                }
            } catch (e: Exception) { }
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
        }.also { it.start() }
        return true
    }

    fun stop() { running = false }

    companion object {
        fun describe(db: Double) = when {
            db < 30 -> "Very quiet"
            db < 45 -> "Quiet (library)"
            db < 60 -> "Normal conversation"
            db < 70 -> "Busy office / traffic"
            db < 80 -> "Loud: vacuum cleaner"
            db < 90 -> "Very loud: harmful after 8 hours"
            db < 100 -> "Dangerous: harmful after 15 minutes"
            db < 115 -> "Painful: harmful in under 1 minute"
            else -> "Extreme: instant hearing damage risk"
        }

        /** Safe exposure time in minutes at this level (NIOSH 85 dB / 8 h, 3 dB exchange rate). */
        fun safeMinutes(db: Double): Double = if (db <= 85) Double.MAX_VALUE else 480.0 / Math.pow(2.0, (db - 85) / 3.0)
    }
}
