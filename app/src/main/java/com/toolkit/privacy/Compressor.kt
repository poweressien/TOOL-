@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.toolkit.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Picked(val uri: Uri, val name: String, val size: Long, val mime: String)
data class Opts(val mode: String, val imgQuality: Int, val maxSide: Int, val webp: Boolean, val vidHeight: Int)
data class Result(val name: String, val file: File, val inSize: Long, val outSize: Long, val mime: String)

fun pickFile(ctx: Context, uri: Uri): Picked {
    var name = "file"
    var size = 0L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            if (n >= 0) name = c.getString(n) ?: name
            if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
        }
    }
    return Picked(uri, name, size, ctx.contentResolver.getType(uri) ?: "application/octet-stream")
}

fun fmtSize(b: Long): String = when {
    b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
    b >= 1_048_576 -> "%.2f MB".format(b / 1_048_576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

object Compressor {
    private fun unique(used: MutableSet<String>, name: String): String {
        if (used.add(name)) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 2
        while (!used.add("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    suspend fun run(
        ctx: Context, files: List<Picked>, o: Opts, outDir: File,
        report: (String, Float) -> Unit
    ): List<Result> {
        val results = mutableListOf<Result>()
        val used = mutableSetOf<String>()
        val total = files.size.coerceAtLeast(1)
        var done = 0
        fun step(label: String) = report(label, done / total.toFloat())

        val bundle = mutableListOf<Picked>()
        for (f in files) {
            val isImg = f.mime.startsWith("image/") && f.mime != "image/gif"
            val isVid = f.mime.startsWith("video/")
            step("${f.name}…")
            when {
                o.mode == "smart" && isImg -> {
                    results += withContext(Dispatchers.IO) { compressImage(ctx, f, outDir, o, used) }
                    done++
                }
                o.mode == "smart" && isVid -> {
                    results += compressVideo(ctx, f, outDir, o, used) { p -> report("${f.name} ${(p * 100).toInt()}%", (done + p) / total) }
                    done++
                }
                o.mode == "gzip" -> {
                    results += withContext(Dispatchers.IO) { single(ctx, f, outDir, used, "gz") }
                    done++
                }
                o.mode == "xz" -> {
                    results += withContext(Dispatchers.IO) { single(ctx, f, outDir, used, "xz") }
                    done++
                }
                else -> bundle += f // zip bundle, or non-media files in smart mode
            }
        }
        if (bundle.isNotEmpty()) {
            step("Building ZIP…")
            results += withContext(Dispatchers.IO) { zip(ctx, bundle, outDir, used) }
        }
        report("Done", 1f)
        return results
    }

    private fun zip(ctx: Context, files: List<Picked>, outDir: File, used: MutableSet<String>): Result {
        val out = File(outDir, unique(used, "archive.zip"))
        val entryNames = mutableSetOf<String>()
        var inSize = 0L
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { z ->
            z.setLevel(9)
            for (f in files) {
                z.putNextEntry(ZipEntry(unique(entryNames, f.name)))
                ctx.contentResolver.openInputStream(f.uri)!!.use { inp -> inSize += inp.copyTo(z) }
                z.closeEntry()
            }
        }
        return Result(out.name, out, inSize, out.length(), "application/zip")
    }

    private fun single(ctx: Context, f: Picked, outDir: File, used: MutableSet<String>, kind: String): Result {
        val out = File(outDir, unique(used, "${f.name}.$kind"))
        var inSize = 0L
        ctx.contentResolver.openInputStream(f.uri)!!.use { inp ->
            val raw = BufferedOutputStream(FileOutputStream(out))
            val enc = if (kind == "gz") GZIPOutputStream(raw) else XZOutputStream(raw, LZMA2Options(6))
            enc.use { inSize = inp.copyTo(it) }
        }
        return Result(out.name, out, inSize, out.length(), if (kind == "gz") "application/gzip" else "application/x-xz")
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun compressImage(ctx: Context, f: Picked, outDir: File, o: Opts, used: MutableSet<String>): Result {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(f.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val big = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (o.maxSide > 0) while (big / (sample * 2) >= o.maxSide) sample *= 2
        var bmp = cr.openInputStream(f.uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot decode ${f.name}")
        var changedSize = sample > 1
        val longSide = maxOf(bmp.width, bmp.height)
        if (o.maxSide > 0 && longSide > o.maxSide) {
            val s = o.maxSide / longSide.toFloat()
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
            changedSize = true
        }
        val orient = try {
            cr.openInputStream(f.uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        } catch (e: Exception) { 1 }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        if (!o.webp && bmp.hasAlpha()) {
            val bg = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(bg).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp = bg
        }
        val base = f.name.substringBeforeLast('.', f.name)
        val ext = if (o.webp) "webp" else "jpg"
        val out = File(outDir, unique(used, "$base.$ext"))
        FileOutputStream(out).use { bmp.compress(if (o.webp) webpFormat() else Bitmap.CompressFormat.JPEG, o.imgQuality, it) }
        // If we made it bigger without resizing, keep the original bytes instead.
        if (!changedSize && f.size > 0 && out.length() >= f.size) {
            out.delete()
            val keep = File(outDir, unique(used, f.name))
            cr.openInputStream(f.uri)!!.use { i -> keep.outputStream().use { i.copyTo(it) } }
            return Result(keep.name, keep, f.size, keep.length(), f.mime)
        }
        return Result(out.name, out, f.size, out.length(), if (o.webp) "image/webp" else "image/jpeg")
    }

    private suspend fun compressVideo(
        ctx: Context, f: Picked, outDir: File, o: Opts, used: MutableSet<String>, onProgress: (Float) -> Unit
    ): Result = withContext(Dispatchers.Main) {
        val out = File(outDir, unique(used, f.name.substringBeforeLast('.', f.name) + ".mp4"))
        val bitrate = when {
            o.vidHeight >= 720 -> 2_500_000
            o.vidHeight >= 480 -> 1_200_000
            else -> 700_000
        }
        suspendCancellableCoroutine<Unit> { cont ->
            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()
            val handler = Handler(Looper.getMainLooper())
            lateinit var transformer: Transformer
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 300)
                }
            }
            transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(f.uri))
                .setEffects(Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(o.vidHeight))))
                .build()
            transformer.start(item, out.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation { handler.removeCallbacks(poll); transformer.cancel() }
        }
        Result(out.name, out, f.size, out.length(), "video/mp4")
    }
}
