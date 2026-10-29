package com.toolkit.privacy

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

fun saveToDownloads(ctx: Context, r: Result): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val v = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, r.name)
        put(MediaStore.Downloads.MIME_TYPE, r.mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/PrivacyToolkit")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
    ctx.contentResolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out) } }
    return true
}

fun share(ctx: Context, r: Result) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", r.file)
    val i = Intent(Intent.ACTION_SEND).setType(r.mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share ${r.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Picked>>(emptyList()) }
    var mode by remember { mutableStateOf("smart") }
    var quality by remember { mutableFloatStateOf(75f) }
    var maxSide by remember { mutableIntStateOf(2048) }
    var webp by remember { mutableStateOf(true) }
    var vidH by remember { mutableIntStateOf(720) }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var pendingSave by remember { mutableStateOf<Result?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { picked = uris.map { pickFile(ctx, it) }; results = emptyList(); status = "" }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val r = pendingSave
        if (uri != null && r != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> r.file.inputStream().use { it.copyTo(out) } }
                status = "Saved ${r.name}"
            } catch (e: Exception) { status = "Save failed: ${e.message}" }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Text("Offline compressor", style = MaterialTheme.typography.headlineSmall)
            Text("Pick any files. Everything happens on your phone, nothing is uploaded.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose files") }
                if (picked.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = { picked = emptyList(); results = emptyList() }) { Text("Clear") }
            }
            if (picked.isNotEmpty()) {
                Text("${picked.size} file(s) · ${fmtSize(picked.sumOf { it.size })}", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                picked.take(6).forEach { Text("• ${it.name}  (${fmtSize(it.size)})", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                if (picked.size > 6) Text("… and ${picked.size - 6} more", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mode", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("smart" to "Smart", "zip" to "ZIP bundle", "gzip" to "GZIP each", "xz" to "XZ each (smallest)")) { (k, label) ->
                            FilterChip(selected = mode == k, onClick = { mode = k }, label = { Text(label) })
                        }
                    }
                    Text(
                        when (mode) {
                            "smart" -> "Photos are re-encoded smaller, videos are re-encoded to a lower resolution, everything else goes into one ZIP."
                            "zip" -> "All files go into a single .zip (maximum Deflate)."
                            "gzip" -> "Each file becomes its own .gz."
                            else -> "Each file becomes its own .xz (LZMA2). Best ratio, opens with 7-Zip/Termux/most file managers."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (mode == "smart") {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Photos", style = MaterialTheme.typography.titleMedium)
                        Text("Quality: ${quality.toInt()}")
                        Slider(value = quality, onValueChange = { quality = it }, valueRange = 30f..95f)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(0 to "Original size", 2048 to "2048 px", 1280 to "1280 px", 1024 to "1024 px")) { (v, l) ->
                                FilterChip(selected = maxSide == v, onClick = { maxSide = v }, label = { Text(l) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = webp, onClick = { webp = true }, label = { Text("WebP (smaller)") })
                            FilterChip(selected = !webp, onClick = { webp = false }, label = { Text("JPEG (compatible)") })
                        }
                        Text("Videos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(720, 480, 360).forEach { h ->
                                FilterChip(selected = vidH == h, onClick = { vidH = h }, label = { Text("${h}p") })
                            }
                        }
                    }
                }
            }
        }
        item {
            Button(
                enabled = picked.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    scope.launch {
                        busy = true; results = emptyList(); progress = 0f
                        val outDir = File(ctx.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
                        try {
                            results = Compressor.run(ctx, picked, Opts(mode, quality.toInt(), maxSide, webp, vidH), outDir) { s, p ->
                                status = s; progress = p
                            }
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Compressing…" else "Compress") }
            if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (results.isNotEmpty()) {
            item {
                val inS = results.sumOf { it.inSize }
                val outS = results.sumOf { it.outSize }
                val pct = if (inS > 0) (100 - outS * 100 / inS).toInt() else 0
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${fmtSize(inS)} → ${fmtSize(outS)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (pct >= 0) "Saved $pct%" else "Output is ${-pct}% larger (files were already compressed)", color = if (pct > 0) Color(0xFF43A047) else Color(0xFFFFB300))
                        if (Build.VERSION.SDK_INT >= 29) {
                            Button(onClick = {
                                val ok = results.count { saveToDownloads(ctx, it) }
                                status = "Saved $ok file(s) to Downloads/PrivacyToolkit"
                            }) { Text("Save all to Downloads") }
                        }
                    }
                }
            }
            items(results, key = { it.file.name }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.name, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("${fmtSize(r.inSize)} → ${fmtSize(r.outSize)}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pendingSave = r; saver.launch(r.name) }) { Text("Save as…") }
                            OutlinedButton(onClick = { share(ctx, r) }) { Text("Share") }
                        }
                    }
                }
            }
        }
    }
}
