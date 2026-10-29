package com.toolkit.privacy

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private data class MetaInfo(val f: Picked, val camera: String, val date: String, val gps: String?, val software: String)

private fun readMeta(ctx: Context, f: Picked): MetaInfo = try {
    ctx.contentResolver.openInputStream(f.uri)!!.use {
        val e = ExifInterface(it)
        val ll = e.latLong
        MetaInfo(
            f, listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" "),
            e.getAttribute(ExifInterface.TAG_DATETIME) ?: "", ll?.let { p -> "%.5f, %.5f".format(p[0], p[1]) },
            e.getAttribute(ExifInterface.TAG_SOFTWARE) ?: ""
        )
    }
} catch (e: Exception) { MetaInfo(f, "", "", null, "") }

private fun stripOne(ctx: Context, f: Picked, outDir: File, used: MutableSet<String>): Result {
    val bmp = loadBitmap(ctx, f.uri, Int.MAX_VALUE) ?: error("Cannot decode ${f.name}")
    val png = f.mime == "image/png"
    val base = f.name.substringBeforeLast('.', f.name)
    var name = "$base-clean.${if (png) "png" else "jpg"}"
    var i = 2
    while (!used.add(name)) name = "$base-clean ($i).${if (png) "png" else "jpg"}".also { i++ }
    val out = File(outDir, name)
    FileOutputStream(out).use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
    return Result(out.name, out, f.size, out.length(), if (png) "image/png" else "image/jpeg")
}

@Composable
fun MetadataScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var infos by remember { mutableStateOf<List<MetaInfo>>(emptyList()) }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            results = emptyList(); msg = ""
            scope.launch { infos = withContext(Dispatchers.IO) { uris.map { readMeta(ctx, pickFile(ctx, it)) } } }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Text("Metadata stripper", style = MaterialTheme.typography.headlineSmall)
            Text("Photos hide your GPS position, camera model and timestamps. See what a photo reveals, then export a clean copy with all metadata removed.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { picker.launch(arrayOf("image/*")) }) { Text("Choose photos") }
                if (infos.isNotEmpty()) Button(enabled = !busy, onClick = {
                    busy = true; msg = ""
                    scope.launch {
                        try {
                            results = withContext(Dispatchers.IO) {
                                val used = mutableSetOf<String>()
                                val dir = cacheOut(ctx)
                                infos.map { stripOne(ctx, it.f, dir, used) }
                            }
                            msg = "Clean copies ready: no EXIF, GPS or camera data."
                        } catch (e: Exception) { msg = "Failed: ${e.message}" }
                        busy = false
                    }
                }) { Text("Strip all (${infos.size})") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (msg.isNotEmpty()) Text(msg, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
        items(infos) { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(m.f.name, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (m.gps != null) Text("📍 GPS location embedded: ${m.gps}", color = Color(0xFFE53935), style = MaterialTheme.typography.bodySmall)
                    else Text("No GPS location", color = Color(0xFF43A047), style = MaterialTheme.typography.bodySmall)
                    if (m.camera.isNotBlank()) Text("Camera: ${m.camera}", style = MaterialTheme.typography.bodySmall)
                    if (m.date.isNotBlank()) Text("Taken: ${m.date}", style = MaterialTheme.typography.bodySmall)
                    if (m.software.isNotBlank()) Text("Software: ${m.software}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { ResultList(results) }
    }
}
