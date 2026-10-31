package com.inwekobong.ekpe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/** Combines magnetometer, front-camera bright-spot (IR LED) detection and Radar's Wi-Fi/Bluetooth list into a suspicion score. */
@SuppressLint("MissingPermission")
class BugSweepSection(private val a: MainActivity) : Tool {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var out: LinearLayout
    private lateinit var preview: ImageView
    private var sweeping = false
    private var t0 = 0L
    private val DURATION = 30000L

    private var emfSpike = 0.0
    private val camSsids = HashSet<String>()
    private val closeBle = HashSet<String>()
    @Volatile private var spotsMax = 0
    @Volatile private var spotRun = 0
    @Volatile private var camNote = ""
    private var frameNo = 0

    private var ht: HandlerThread? = null
    private var dev: CameraDevice? = null
    private var sess: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    private val SSID_WORDS = listOf("cam", "ipc", "ipcam", "dvr", "nvr", "hikvision", "ezviz", "yi-", "yicam", "tuya", "smartlife", "wificam", "spy", "mini-cam", "hd-", "v380", "icsee", "xmeye", "camhi", "p2p", "wifi_cam", "lookcam")

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Bug Sweep", 20f, C.TXT, true), 0)
        p.add(a.tv("Gives a suspicion score, not a guarantee. For the camera test, dim the room, hold the phone with the front camera facing walls, smoke detectors, clocks and plugs, and move slowly for 30 seconds. Infrared LEDs may show as bright dots.", 12f, C.SUB), 4)
        p.add(a.button("Start 30-second sweep") { start() }, 10)
        preview = ImageView(a).apply { setBackgroundColor(0xFF000000.toInt()); scaleType = ImageView.ScaleType.FIT_CENTER }
        p.addView(preview, LinearLayout.LayoutParams(-1, a.dp(220)).apply { topMargin = a.dp(8) })
        status = a.tv("Ready.", 13f, C.SUB)
        p.add(status, 8)
        out = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(out, 8)
        return p
    }

    private fun start() {
        if (sweeping) return
        val need = (GuardPerm.radar() + Manifest.permission.CAMERA).filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { a.ask(need.toTypedArray()) { begin() }; return }
        begin()
    }

    private fun begin() {
        RadarEngine.start(a)
        sweeping = true; t0 = System.currentTimeMillis()
        emfSpike = 0.0; camSsids.clear(); closeBle.clear(); spotsMax = 0; spotRun = 0; camNote = ""; frameNo = 0
        out.removeAllViews()
        if (a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) runCatching { startCam() }.onFailure { camNote = "Camera unavailable" }
        else camNote = "Camera permission denied"
        ui.post(sampler)
    }

    private val sampler = object : Runnable {
        override fun run() {
            if (!sweeping) return
            val el = System.currentTimeMillis() - t0
            try { sample() } catch (e: Exception) { }
            status.text = "Sweeping… ${(DURATION - el) / 1000}s left · bright spots seen: $spotsMax"
            if (el >= DURATION) finish() else ui.postDelayed(this, 500)
        }
    }

    private fun sample() {
        val j = JSONObject(RadarEngine.snapshot())
        emfSpike = maxOf(emfSpike, abs(j.optDouble("emf") - j.optDouble("base")))
        val w = j.optJSONArray("wifi")
        if (w != null) for (i in 0 until w.length()) {
            val o = w.getJSONObject(i); val s = o.optString("s").lowercase()
            if (s.isNotEmpty() && SSID_WORDS.any { s.contains(it) } && o.optInt("r") > -80) camSsids.add(o.optString("s"))
        }
        val b = j.optJSONArray("ble")
        if (b != null) for (i in 0 until b.length()) {
            val o = b.getJSONObject(i)
            if (o.optString("n").isEmpty() && o.optDouble("d", 99.0) <= 2.0) closeBle.add(o.optString("a"))
        }
    }

    private fun finish() {
        sweeping = false; stopCam()
        val emfPts = when { emfSpike >= 40 -> 25; emfSpike >= 20 -> 15; emfSpike >= 10 -> 5; else -> 0 }
        val irPts = when { spotsMax >= 2 -> 30; spotsMax == 1 -> 20; else -> 0 }
        val ssidPts = minOf(30, camSsids.size * 15)
        val blePts = minOf(20, closeBle.size * 7)
        val score = minOf(100, emfPts + irPts + ssidPts + blePts)
        val label = when { score < 25 -> "LOW"; score < 55 -> "MEDIUM"; else -> "HIGH" }
        status.text = "Suspicion score: $score / 100 ($label)"
        val rows = listOf(
            Row("RESULT", header = true),
            Row("Score", "$score / 100 — $label"),
            Row("Magnetic field", "Peak change ${"%.0f".format(emfSpike)} µT (+$emfPts)"),
            Row("Front camera", (if (camNote.isNotEmpty()) camNote else "Steady bright spots: $spotsMax") + " (+$irPts)"),
            Row("Camera-like Wi-Fi", (if (camSsids.isEmpty()) "None" else camSsids.joinToString(", ")) + " (+$ssidPts)"),
            Row("Unnamed Bluetooth <2 m", "${closeBle.size} (+$blePts)"),
            Row("NOTE", header = true),
            Row("Meaning", "Lamps, phones, speakers and routers also trigger these signals. A high score means look closer, not that a bug exists. Check the spots by eye and with a torch for lens glints.")
        )
        out.removeAllViews(); out.addView(a.table(rows)); out.applyTypeface(a.tf)
    }

    private fun startCam() {
        val cm = a.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        if (id == null) { camNote = "No front camera"; return }
        val ch = cm.getCameraCharacteristics(id)
        val size = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { abs(it.width * it.height - 320 * 240) } ?: Size(320, 240)
        val so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        preview.rotation = so.toFloat(); preview.scaleX = -1f
        val th = HandlerThread("bugcam").also { it.start() }; ht = th
        val bh = Handler(th.looper)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2); reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try { analyze(img) } finally { img.close() }
        }, bh)
        @Suppress("DEPRECATION")
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                dev = d
                try {
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }
                    d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sess = s; try { s.setRepeatingRequest(req.build(), null, bh) } catch (e: Exception) { } }
                        override fun onConfigureFailed(s: CameraCaptureSession) { camNote = "Camera could not start" }
                    }, bh)
                } catch (e: Exception) { camNote = "Camera could not start" }
            }
            override fun onDisconnected(d: CameraDevice) { d.close() }
            override fun onError(d: CameraDevice, e: Int) { d.close(); camNote = "Camera error $e" }
        }, bh)
    }

    private fun analyze(img: Image) {
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride; val w = img.width; val h = img.height
        val cw = w / 16 + 1
        val cells = IntArray(cw * (h / 16 + 1))
        val show = (frameNo++ % 3 == 0)
        val px = if (show) IntArray(w * h) else null
        var sum = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val v = buf.get(y * rs + x).toInt() and 0xFF
            sum += v
            if (v > 235) cells[(y / 16) * cw + x / 16]++
            if (px != null) px[y * w + x] = if (v > 235) 0xFFFF2030.toInt() else (0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
        }
        val mean = sum / (w * h)
        val spots = cells.count { it >= 3 }
        if (mean < 90 && spots in 1..6) { spotRun++; if (spotRun >= 5) spotsMax = maxOf(spotsMax, spots) } else spotRun = 0
        if (px != null) {
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bm.setPixels(px, 0, w, 0, 0, w, h)
            ui.post { preview.setImageBitmap(bm) }
        }
    }

    private fun stopCam() {
        runCatching { sess?.close() }; runCatching { dev?.close() }; runCatching { reader?.close() }; runCatching { ht?.quitSafely() }
        sess = null; dev = null; reader = null; ht = null
    }

    override fun stop() { sweeping = false; ui.removeCallbacksAndMessages(null); stopCam() }
}
