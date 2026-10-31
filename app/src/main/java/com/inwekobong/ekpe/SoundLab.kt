package com.inwekobong.ekpe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SpectrumView(c: Context) : View(c) {
    var bars = FloatArray(32)
    private val p = Paint().apply { color = C.ACC }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat() / bars.size
        for (i in bars.indices) {
            val h = bars[i].coerceIn(0f, 1f) * height
            p.color = if (bars[i] > 0.8f) 0xFFFF3B30.toInt() else if (bars[i] > 0.55f) 0xFFFFCC00.toInt() else C.ACC
            cv.drawRect(i * w + 1, height - h, (i + 1) * w - 1, height.toFloat(), p)
        }
    }
}

/** Decibel meter, live spectrum and a noise alarm that records a WAV clip when the level passes your threshold. */
class SoundLabSection(private val a: MainActivity) : Tool {
    private lateinit var dbText: TextView
    private lateinit var peakText: TextView
    private lateinit var spec: SpectrumView
    private lateinit var clips: LinearLayout
    private lateinit var startBtn: android.widget.Button
    @Volatile private var running = false
    @Volatile private var threshold = 85
    @Volatile private var offset = 90
    @Volatile private var alarmOn = true
    private var player: MediaPlayer? = null

    private val rate = 44100
    private val N = 2048

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Sound Lab", 20f, C.TXT, true), 0)
        p.add(a.tv("Readings are approximate (phone microphones aren't calibrated). Adjust the calibration offset against a known meter if you have one. Recording works while this screen is open.", 12f, C.SUB), 4)
        dbText = a.tv("-- dB", 44f, C.ACC, true); p.add(dbText, 8)
        peakText = a.tv("Peak: -- dB", 12f, C.SUB); p.add(peakText, 0)
        spec = SpectrumView(a)
        p.addView(spec, LinearLayout.LayoutParams(-1, a.dp(140)).apply { topMargin = a.dp(8) })
        startBtn = a.button("Start listening") { if (running) stopListening() else startListening() }
        p.add(startBtn, 10)
        p.add(a.sw("Noise alarm: record when too loud", true) { alarmOn = it }, 8)
        p.add(a.slider("Alarm threshold (dB)", 120, threshold) { threshold = it }, 4)
        p.add(a.slider("Calibration offset", 120, offset) { offset = it }, 4)
        p.add(a.tv("Recorded clips", 14f, C.TXT, true), 12)
        clips = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(clips, 6)
        refreshClips()
        return p
    }

    private fun dir(): File = (a.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: a.filesDir).also { it.mkdirs() }

    private fun startListening() {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            a.ask(arrayOf(Manifest.permission.RECORD_AUDIO)) { if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening() else a.toast("Microphone permission needed") }
            return
        }
        running = true; startBtn.text = "Stop listening"
        Thread { loop() }.start()
    }

    private fun stopListening() { running = false; startBtn.text = "Start listening" }

    @Suppress("MissingPermission")
    private fun loop() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, N * 4)) } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { a.runOnUiThread { a.toast("Microphone unavailable"); stopListening() }; return }
        rec.startRecording()
        val buf = ShortArray(N)
        val pre = ArrayDeque<ShortArray>()
        var out: RandomAccessFile? = null; var outFile: File? = null; var written = 0L
        var quietMs = 0.0; var clipPeak = 0.0; var peak = 0.0; var shown = 0L; var smooth = 0.0
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        try {
            while (running) {
                var got = 0
                while (got < N && running) { val r = rec.read(buf, got, N - got); if (r <= 0) break; got += r }
                if (got < N) continue
                var sum = 0.0; for (s in buf) sum += s.toDouble() * s
                val rms = sqrt(sum / N)
                val db = (20 * log10(maxOf(rms, 1.0) / 32768.0) + offset).coerceIn(0.0, 130.0)
                smooth = if (smooth == 0.0) db else smooth * 0.5 + db * 0.5
                if (db > peak) peak = db
                // alarm / recording
                val frameMs = N * 1000.0 / rate
                val chunk = buf.copyOf()
                if (alarmOn && db >= threshold) {
                    quietMs = 0.0; clipPeak = maxOf(clipPeak, db)
                    if (out == null) {
                        outFile = File(dir(), "noise_${fmt.format(Date())}.wav")
                        out = RandomAccessFile(outFile, "rw"); out.write(ByteArray(44)); written = 0
                        pre.forEach { written += writePcm(out!!, it) }; pre.clear()
                        vibrate()
                    }
                } else if (out != null) quietMs += frameMs
                if (out != null) {
                    written += writePcm(out, chunk)
                    if (quietMs >= 3000 || written > rate * 2L * 60) { finishWav(out, written); out = null; clipPeak = 0.0; a.runOnUiThread { refreshClips() } }
                } else { pre.addLast(chunk); if (pre.size > 20) pre.removeFirst() }
                // spectrum + UI ~10 fps
                val now = System.currentTimeMillis()
                if (now - shown > 100) {
                    shown = now
                    val bars = spectrum(buf, win)
                    val d = smooth; val pk = peak
                    a.runOnUiThread {
                        dbText.text = "${d.toInt()} dB"
                        dbText.setTextColor(if (d >= threshold) 0xFFFF3B30.toInt() else C.ACC)
                        peakText.text = "Peak: ${pk.toInt()} dB" + if (alarmOn) " · alarm at $threshold dB" else ""
                        spec.bars = bars; spec.invalidate()
                    }
                }
            }
        } finally {
            runCatching { rec.stop() }; rec.release()
            out?.let { runCatching { finishWav(it, written) } }
            a.runOnUiThread { refreshClips() }
        }
    }

    private fun writePcm(f: RandomAccessFile, s: ShortArray): Long {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) { b[2 * i] = (s[i].toInt() and 0xFF).toByte(); b[2 * i + 1] = ((s[i].toInt() shr 8) and 0xFF).toByte() }
        f.write(b); return b.size.toLong()
    }

    private fun finishWav(f: RandomAccessFile, dataLen: Long) {
        fun le(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * it)) and 0xFF).toByte() }
        f.seek(0)
        f.write("RIFF".toByteArray()); f.write(le(36 + dataLen, 4)); f.write("WAVEfmt ".toByteArray()); f.write(le(16, 4))
        f.write(le(1, 2)); f.write(le(1, 2)); f.write(le(rate.toLong(), 4)); f.write(le(rate * 2L, 4)); f.write(le(2, 2)); f.write(le(16, 2))
        f.write("data".toByteArray()); f.write(le(dataLen, 4)); f.close()
    }

    private fun vibrate() {
        runCatching {
            @Suppress("DEPRECATION") val v = a.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** 32 log-spaced bands from ~60 Hz to ~16 kHz via a radix-2 FFT. */
    private fun spectrum(s: ShortArray, win: DoubleArray): FloatArray {
        val re = DoubleArray(N) { s[it] * win[it] }; val im = DoubleArray(N)
        var j = 0
        for (i in 1 until N) { var bit = N shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t } }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len; val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < N) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi; re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        val bars = FloatArray(32); val binHz = rate.toDouble() / N
        for (b in 0 until 32) {
            val lo = 60.0 * Math.pow(16000.0 / 60.0, b / 32.0); val hi = 60.0 * Math.pow(16000.0 / 60.0, (b + 1) / 32.0)
            val i0 = (lo / binHz).toInt().coerceIn(1, N / 2 - 1); val i1 = maxOf(i0 + 1, (hi / binHz).toInt()).coerceAtMost(N / 2)
            var m = 0.0; for (i in i0 until i1) m = maxOf(m, sqrt(re[i] * re[i] + im[i] * im[i]))
            val dbfs = 20 * log10(maxOf(m / (N / 2) / 32768.0, 1e-9))
            bars[b] = ((dbfs + 95) / 70).toFloat().coerceIn(0f, 1f)
        }
        return bars
    }

    private fun refreshClips() {
        if (!::clips.isInitialized) return
        clips.removeAllViews()
        val fs = dir().listFiles { f -> f.name.startsWith("noise_") && f.name.endsWith(".wav") }?.sortedByDescending { it.name } ?: emptyList()
        if (fs.isEmpty()) clips.add(a.tv("No clips yet. Loud moments will be saved here.", 12f, C.SUB), 0)
        fs.take(30).forEach { f ->
            val c = a.card()
            c.addView(a.tv(f.name.removePrefix("noise_").removeSuffix(".wav").replace('_', ' '), 13f, C.TXT, true))
            c.addView(a.tv("${f.length() / 1024} KB", 11f, C.SUB))
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(a.button("Play") { play(f) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(a.button("Delete") { f.delete(); refreshClips() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
            c.add(r, 6); clips.add(c, 6)
        }
        clips.applyTypeface(a.tf)
    }

    private fun play(f: File) {
        runCatching { player?.release(); player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() } }.onFailure { a.toast("Cannot play clip") }
    }

    override fun stop() {
        running = false
        runCatching { player?.release() }; player = null
        if (::startBtn.isInitialized) startBtn.text = "Start listening"
    }
}
