package com.toolkit.privacy

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

/**
 * Camera heart-rate: the fingertip covers the lens and flash, every heartbeat changes how much light passes through the skin.
 * Streams the average brightness of each frame to [onSample].
 */
class PpgMeter(private val ctx: Context, private val onSample: (Long, Double) -> Unit, private val onError: (String) -> Unit) {
    private var cam: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile private var closed = false

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { val c = cm.getCameraCharacteristics(it); c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK && c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: cm.cameraIdList.firstOrNull() ?: run { onError("No camera found"); return }
            val th = HandlerThread("ppg").also { it.start() }; thread = th
            val h = Handler(th.looper)
            val rd = ImageReader.newInstance(176, 144, ImageFormat.YUV_420_888, 3); reader = rd
            rd.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val p = img.planes[0]; val buf = p.buffer
                    var sum = 0L; var n = 0; var i = 0
                    val lim = buf.limit()
                    while (i < lim) { sum += buf.get(i).toInt() and 0xFF; n++; i += 7 }
                    if (n > 0 && !closed) onSample(System.currentTimeMillis(), sum.toDouble() / n)
                } catch (e: Exception) { } finally { img.close() }
            }, h)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    cam = d
                    try {
                        d.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    rb.addTarget(rd.surface)
                                    rb.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                    s.setRepeatingRequest(rb.build(), null, h)
                                    // lock exposure after the sensor settled so auto-exposure does not hide the pulse
                                    h.postDelayed({
                                        try {
                                            if (closed) return@postDelayed
                                            val rb2 = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                            rb2.addTarget(rd.surface)
                                            rb2.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                            rb2.set(CaptureRequest.CONTROL_AE_LOCK, true)
                                            s.setRepeatingRequest(rb2.build(), null, h)
                                        } catch (e: Exception) { }
                                    }, 1800)
                                } catch (e: Exception) { onError("Camera: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { onError("Could not start the camera") }
                        }, h)
                    } catch (e: Exception) { onError("Camera: ${e.message}") }
                }
                override fun onDisconnected(d: CameraDevice) { stop() }
                override fun onError(d: CameraDevice, error: Int) { onError("Camera error $error"); stop() }
            }, h)
        } catch (e: Exception) { onError("Camera: ${e.message}") }
    }

    fun stop() {
        closed = true
        try { session?.stopRepeating() } catch (e: Exception) { }
        try { session?.close() } catch (e: Exception) { }
        try { cam?.close() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { thread?.quitSafely() } catch (e: Exception) { }
        session = null; cam = null; reader = null; thread = null
    }
}
