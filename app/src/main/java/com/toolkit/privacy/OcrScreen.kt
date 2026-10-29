package com.toolkit.privacy

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OcrScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    fun recognize(uri: Uri) {
        busy = true; msg = ""; text = ""
        scope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(ctx, uri, 2400) } ?: error("Cannot open image")
                preview = bmp
                val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val r = rec.process(InputImage.fromBitmap(bmp, 0)).await()
                text = r.text
                msg = if (r.text.isBlank()) "No text found" else "${r.text.lines().size} lines · ${r.text.length} characters"
                rec.close()
            } catch (e: Exception) { msg = "OCR failed: ${e.message}" }
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) recognize(uri) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { recognize(it) }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Offline OCR", style = MaterialTheme.typography.headlineSmall)
        Text("Extract text from photos and screenshots on-device (Latin scripts: English, Pidgin, French, Spanish…). Nothing is uploaded.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { pick.launch("image/*") }) { Text("Choose image") }
            OutlinedButton(enabled = !busy, onClick = {
                val f = File(ctx.cacheDir, "ocr").apply { mkdirs() }.let { File(it, "shot_${System.currentTimeMillis()}.jpg") }
                val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                photoUri = u
                take.launch(u)
            }) { Text("Take photo") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        preview?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit)
        }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        if (text.isNotEmpty() || preview != null) {
            OutlinedTextField(text, { text = it }, label = { Text("Recognized text (editable)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyText(ctx, text) }) { Text("Copy") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Share") }
                OutlinedButton(onClick = { saver.launch("ocr.txt") }) { Text("Save .txt") }
            }
        }
    }
}
