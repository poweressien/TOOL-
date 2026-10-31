package com.toolkit.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One CameraX session: live preview, hardware zoom, torch, in-memory still capture and a low-rate live frame feed. */
class LensCam(private val ctx: Context, private val owner: LifecycleOwner) {
    @Volatile var camera: Camera? = null; private set
    @Volatile var ready = false; private set
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Executor { Handler(Looper.getMainLooper()).post(it) }
    @Volatile var live: ((Bitmap) -> Unit)? = null
    @Volatile var liveIntervalMs = 1000L
    private var lastLive = 0L

    private fun rotate(b: Bitmap, deg: Int): Bitmap = if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    fun bind(view: PreviewView) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            try {
                val p = f.get(); provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build(); capture = cap
                val ana = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(Size(960, 720)).build()
                ana.setAnalyzer(exec) { proxy: ImageProxy ->
                    try {
                        val cb = live; val now = System.currentTimeMillis()
                        if (cb != null && now - lastLive >= liveIntervalMs) { lastLive = now; cb(rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)) }
                    } catch (e: Exception) { } finally { proxy.close() }
                }
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap, ana)
                ready = true
            } catch (e: Exception) { ready = false }
        }, main)
    }

    fun zoomMax(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    fun zoomMin(): Float = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    fun zoom(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    fun setZoom(r: Float) { try { camera?.cameraControl?.setZoomRatio(r.coerceIn(zoomMin(), zoomMax())) } catch (e: Exception) { } }
    fun hasTorch(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true
    fun torch(on: Boolean) { try { camera?.cameraControl?.enableTorch(on) } catch (e: Exception) { } }

    /** Full-resolution still, already rotated upright. Null on failure. */
    suspend fun grab(): Bitmap? = suspendCancellableCoroutine { c ->
        val cap = capture
        if (cap == null) { c.resume(null); return@suspendCancellableCoroutine }
        cap.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val b = try { rotate(image.toBitmap(), image.imageInfo.rotationDegrees) } catch (e: Throwable) { null }
                image.close(); if (c.isActive) c.resume(b)
            }
            override fun onError(exception: ImageCaptureException) { if (c.isActive) c.resume(null) }
        })
    }

    fun unbind() { live = null; try { torch(false); provider?.unbindAll() } catch (e: Exception) { }; ready = false; camera = null }
}
