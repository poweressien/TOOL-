package com.toolkit.privacy

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private fun qrBitmap(text: String, size: Int = 768): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) AColor.BLACK else AColor.WHITE
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

private fun wifiEscape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")

@Composable
fun QrScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var scanned by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("https://") }
    var ssid by remember { mutableStateOf("") }
    var wifiPw by remember { mutableStateOf("") }
    var sec by remember { mutableStateOf("WPA") }
    var hidden by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }

    val imagePick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(ctx, uri, 2000) } ?: error("Cannot open image")
                val client = BarcodeScanning.getClient()
                val list = client.process(InputImage.fromBitmap(bmp, 0)).await()
                client.close()
                scanned = list.firstOrNull()?.rawValue ?: ""
                msg = if (scanned.isEmpty()) "No QR / barcode found in image" else ""
            } catch (e: Exception) { msg = "Scan failed: ${e.message}" }
        }
    }

    fun generate(text: String) {
        if (text.isBlank()) return
        val bmp = qrBitmap(text)
        qr = bmp
        val f = File(cacheOut(ctx), "qr-code.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        results = listOf(Result(f.name, f, 0, f.length(), "image/png"))
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("QR tool", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Scan", "Text / URL", "Wi-Fi").forEachIndexed { i, l ->
                FilterChip(selected = mode == i, onClick = { mode = i; msg = "" }, label = { Text(l) })
            }
        }
        when (mode) {
            0 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        msg = ""
                        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
                        GmsBarcodeScanning.getClient(ctx, opts).startScan()
                            .addOnSuccessListener { scanned = it.rawValue ?: "" }
                            .addOnFailureListener { msg = "Scanner unavailable: ${it.message}. Try \"Scan from image\"." }
                    }) { Text("Scan with camera") }
                    OutlinedButton(onClick = { imagePick.launch("image/*") }) { Text("Scan from image") }
                }
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
                if (scanned.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Result", style = MaterialTheme.typography.titleMedium)
                            Text(scanned, fontWeight = FontWeight.Medium)
                            if (scanned.startsWith("WIFI:")) {
                                val s = Regex("S:((?:\\\\.|[^;])*);").find(scanned)?.groupValues?.get(1)
                                val p = Regex("P:((?:\\\\.|[^;])*);").find(scanned)?.groupValues?.get(1)
                                Text("Wi-Fi network: ${s ?: "?"}   password: ${p ?: "(none)"}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { copyText(ctx, scanned) }) { Text("Copy") }
                                if (scanned.startsWith("http://") || scanned.startsWith("https://")) {
                                    OutlinedButton(onClick = {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(scanned)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }) { Text("Open link") }
                                }
                            }
                            if (scanned.startsWith("http")) Text("Check the address before opening. QR codes can hide phishing links.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            1 -> {
                OutlinedTextField(input, { input = it }, label = { Text("Text or URL") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
                Button(onClick = { generate(input) }) { Text("Generate QR") }
            }
            else -> {
                OutlinedTextField(ssid, { ssid = it }, label = { Text("Network name (SSID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(wifiPw, { wifiPw = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("WPA", "WEP", "nopass").forEach { s -> FilterChip(selected = sec == s, onClick = { sec = s }, label = { Text(if (s == "nopass") "Open" else s) }) }
                    FilterChip(selected = hidden, onClick = { hidden = !hidden }, label = { Text("Hidden") })
                }
                Button(enabled = ssid.isNotBlank(), onClick = {
                    generate("WIFI:T:$sec;S:${wifiEscape(ssid)};" + (if (sec != "nopass") "P:${wifiEscape(wifiPw)};" else "") + (if (hidden) "H:true;" else "") + ";")
                }) { Text("Generate Wi-Fi QR") }
                Text("Guests scan it with their camera to join without typing the password.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (mode != 0) {
            qr?.let { Image(it.asImageBitmap(), "QR code", Modifier.size(260.dp)) }
            ResultList(results)
        }
    }
}
