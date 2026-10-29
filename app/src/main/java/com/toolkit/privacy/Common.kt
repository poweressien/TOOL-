package com.toolkit.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import java.io.File

fun Context.findActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

fun copyText(ctx: Context, text: String, sensitive: Boolean = false) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("text", text)
    if (sensitive && Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    cm.setPrimaryClip(clip)
}

fun cacheOut(ctx: Context): File = File(ctx.cacheDir, "out").apply { mkdirs() }

fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
    val cr = ctx.contentResolver
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
    val big = maxOf(b.outWidth, b.outHeight)
    var sample = 1
    while (maxSide in 1 until big && big / (sample * 2) >= maxSide) sample *= 2
    var bmp = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val orient = try {
        cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    } catch (e: Exception) { 1 }
    val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    return bmp
}

fun shareAll(ctx: Context, results: List<Result>) {
    val uris = ArrayList<Uri>(results.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it.file) })
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Shared output list: summary, Save all / Share all, and per-file Save as / Share. */
@Composable
fun ResultList(results: List<Result>) {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Result?>(null) }
    var note by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val r = pending
        if (uri != null && r != null) {
            note = try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out) } }
                "Saved ${r.name}"
            } catch (e: Exception) { "Save failed: ${e.message}" }
        }
    }
    if (results.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val inS = results.sumOf { it.inSize }
        val outS = results.sumOf { it.outSize }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (inS > 0) "${results.size} file(s) · ${fmtSize(inS)} → ${fmtSize(outS)}" else "${results.size} file(s) · ${fmtSize(outS)}",
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        Button(onClick = {
                            val ok = results.count { saveToDownloads(ctx, it) }
                            note = "Saved $ok file(s) to Downloads/PrivacyToolkit"
                        }) { Text("Save all") }
                    }
                    OutlinedButton(onClick = { shareAll(ctx, results) }) { Text("Share all") }
                }
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = Color(0xFF43A047))
            }
        }
        results.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.name, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(if (r.inSize > 0) "${fmtSize(r.inSize)} → ${fmtSize(r.outSize)}" else fmtSize(r.outSize), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pending = r; saver.launch(r.name) }) { Text("Save as…") }
                        OutlinedButton(onClick = { share(ctx, r) }) { Text("Share") }
                    }
                }
            }
        }
    }
}
