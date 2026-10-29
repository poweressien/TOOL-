package com.toolkit.privacy

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.pow

private fun hashHex(alg: String, data: ByteArray) = MessageDigest.getInstance(alg).digest(data).joinToString("") { "%02x".format(it) }

private fun lum(r: Int, g: Int, b: Int): Double {
    fun c(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * c(r) + 0.7152 * c(g) + 0.0722 * c(b)
}

@Composable
fun DevUtilsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    var tool by remember { mutableStateOf("JSON") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var urlSafe by remember { mutableStateOf(false) }
    var fileHashes by remember { mutableStateOf("") }
    var r by remember { mutableIntStateOf(45) }
    var g by remember { mutableIntStateOf(212) }
    var b by remember { mutableIntStateOf(191) }
    var hexIn by remember { mutableStateOf("") }
    var uuids by remember { mutableStateOf("") }
    var tsIn by remember { mutableStateOf("") }
    var tsOut by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            fileHashes = "Hashing…"
            fileHashes = try {
                withContext(Dispatchers.IO) {
                    val mds = listOf("MD5", "SHA-1", "SHA-256", "SHA-512").map { MessageDigest.getInstance(it) }
                    ctx.contentResolver.openInputStream(uri)!!.use { i ->
                        val buf = ByteArray(65536)
                        while (true) { val n = i.read(buf); if (n < 0) break; mds.forEach { it.update(buf, 0, n) } }
                    }
                    mds.joinToString("\n") { "${it.algorithm}: " + it.digest().joinToString("") { b -> "%02x".format(b) } }
                }
            } catch (e: Exception) { "Failed: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dev utilities", style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("JSON", "Base64", "Hash", "URL", "Color", "Time / UUID")) { t ->
                FilterChip(selected = tool == t, onClick = { tool = t; output = "" }, label = { Text(t) })
            }
        }

        if (tool != "Color" && tool != "Time / UUID") {
            OutlinedTextField(input, { input = it }, label = { Text("Input") }, textStyle = mono, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp))
        }

        when (tool) {
            "JSON" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    output = try {
                        when (val v = JSONTokener(input).nextValue()) { is JSONObject -> v.toString(2); is JSONArray -> v.toString(2); else -> "Not a JSON object or array" }
                    } catch (e: Exception) { "Invalid JSON: ${e.message}" }
                }) { Text("Format") }
                OutlinedButton(onClick = {
                    output = try {
                        when (val v = JSONTokener(input).nextValue()) { is JSONObject -> v.toString(); is JSONArray -> v.toString(); else -> "Not a JSON object or array" }
                    } catch (e: Exception) { "Invalid JSON: ${e.message}" }
                }) { Text("Minify") }
                OutlinedButton(onClick = {
                    output = try { JSONTokener(input).nextValue(); "✓ Valid JSON" } catch (e: Exception) { "✗ ${e.message}" }
                }) { Text("Validate") }
            }
            "Base64" -> {
                FilterChip(selected = urlSafe, onClick = { urlSafe = !urlSafe }, label = { Text("URL-safe") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val flags = (if (urlSafe) Base64.URL_SAFE else Base64.DEFAULT) or Base64.NO_WRAP
                    Button(onClick = { output = Base64.encodeToString(input.toByteArray(), flags) }) { Text("Encode") }
                    OutlinedButton(onClick = { output = try { String(Base64.decode(input.trim(), flags)) } catch (e: Exception) { "Invalid Base64" } }) { Text("Decode") }
                }
            }
            "Hash" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val d = input.toByteArray()
                        output = listOf("MD5", "SHA-1", "SHA-256", "SHA-512").joinToString("\n\n") { "$it\n${hashHex(it, d)}" }
                    }) { Text("Hash text") }
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Hash a file") }
                }
                if (fileHashes.isNotEmpty()) {
                    Text(fileHashes, style = mono)
                    TextButton(onClick = { copyText(ctx, fileHashes) }) { Text("Copy file hashes") }
                }
            }
            "URL" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { output = URLEncoder.encode(input, "UTF-8") }) { Text("Encode") }
                OutlinedButton(onClick = { output = try { URLDecoder.decode(input, "UTF-8") } catch (e: Exception) { "Invalid encoding" } }) { Text("Decode") }
            }
            "Color" -> {
                val hex = "#%02X%02X%02X".format(r, g, b)
                Box(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(16.dp)).background(Color(r, g, b)))
                Text(hex, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val mx = maxOf(r, g, b) / 255f
                val mn = minOf(r, g, b) / 255f
                val d = mx - mn
                val h = when {
                    d == 0f -> 0f
                    mx == r / 255f -> 60f * (((g - b) / 255f / d) % 6)
                    mx == g / 255f -> 60f * ((b - r) / 255f / d + 2)
                    else -> 60f * ((r - g) / 255f / d + 4)
                }.let { if (it < 0) it + 360 else it }
                Text("rgb($r, $g, $b)   hsv(${h.toInt()}°, ${if (mx == 0f) 0 else (d / mx * 100).toInt()}%, ${(mx * 100).toInt()}%)", style = mono)
                val l = lum(r, g, b)
                val onWhite = 1.05 / (l + 0.05)
                val onBlack = (l + 0.05) / 0.05
                Text("Contrast: %.1f:1 on white · %.1f:1 on black".format(onWhite, onBlack), style = MaterialTheme.typography.bodySmall)
                Text("R $r"); Slider(r.toFloat(), { r = it.toInt() }, valueRange = 0f..255f)
                Text("G $g"); Slider(g.toFloat(), { g = it.toInt() }, valueRange = 0f..255f)
                Text("B $b"); Slider(b.toFloat(), { b = it.toInt() }, valueRange = 0f..255f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hexIn, { hexIn = it }, label = { Text("Paste hex, e.g. #1E90FF") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val s = hexIn.trim().removePrefix("#")
                        if (s.length == 6) s.toIntOrNull(16)?.let { v -> r = v shr 16 and 255; g = v shr 8 and 255; b = v and 255 }
                    }) { Text("Set") }
                }
                Button(onClick = { copyText(ctx, hex) }) { Text("Copy $hex") }
            }
            else -> {
                Button(onClick = { uuids = (1..5).joinToString("\n") { UUID.randomUUID().toString() } }) { Text("Generate 5 UUIDs") }
                if (uuids.isNotEmpty()) { Text(uuids, style = mono); TextButton(onClick = { copyText(ctx, uuids) }) { Text("Copy") } }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Timestamp converter", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(tsIn, { tsIn = it }, label = { Text("Unix seconds/millis, or yyyy-MM-dd HH:mm:ss (UTC)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { tsIn = (System.currentTimeMillis() / 1000).toString() }) { Text("Now") }
                    OutlinedButton(onClick = {
                        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        val loc = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss zzz", Locale.US)
                        val num = tsIn.trim().toLongOrNull()
                        tsOut = if (num != null) {
                            val ms = if (num > 100_000_000_000L) num else num * 1000
                            "UTC:   ${iso.format(Date(ms))}\nLocal: ${loc.format(Date(ms))}\nSeconds: ${ms / 1000}\nMillis:  $ms"
                        } else try {
                            val p = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(tsIn.trim())!!
                            "Seconds: ${p.time / 1000}\nMillis:  ${p.time}"
                        } catch (e: Exception) { "Could not parse input" }
                    }) { Text("Convert") }
                }
                if (tsOut.isNotEmpty()) Text(tsOut, style = mono)
            }
        }

        if (output.isNotEmpty()) {
            OutlinedTextField(output, {}, readOnly = true, label = { Text("Output") }, textStyle = mono, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyText(ctx, output) }) { Text("Copy") }
                OutlinedButton(onClick = { input = output; output = "" }) { Text("Use as input") }
            }
        }
    }
}
