package com.toolkit.privacy

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val SAMPLE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hello localhost</title>
<style>
  body{font-family:system-ui;margin:0;display:grid;place-items:center;min-height:100vh;background:#0b0f14;color:#e6edf3}
  button{padding:12px 20px;font-size:18px;border-radius:12px;border:0;background:#2dd4bf;color:#00201c}
</style>
</head>
<body>
  <div style="text-align:center">
    <h1>It works 🎉</h1>
    <p id="o">Served from <code></code></p>
    <button onclick="localStorage.n=(+localStorage.n||0)+1;n.textContent=localStorage.n;console.log('clicked',localStorage.n)">Clicks: <b id="n">0</b></button>
  </div>
  <script>document.querySelector('code').textContent=location.origin;n.textContent=localStorage.n||0;</script>
</body>
</html>"""

private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "-").take(40).ifBlank { "site" }
private fun listProjects(dir: File) = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlRunnerScreen() {
    val ctx = LocalContext.current
    val sitesDir = remember { File(ctx.filesDir, "sites").apply { mkdirs() } }
    var projects by remember { mutableStateOf(listProjects(sitesDir)) }
    var name by remember { mutableStateOf("my-site") }
    var html by remember { mutableStateOf(SAMPLE) }
    var url by remember { mutableStateOf<String?>(null) }
    var desktop by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    val console: SnapshotStateList<String> = remember { mutableStateListOf() }
    var web by remember { mutableStateOf<WebView?>(null) }
    var msg by remember { mutableStateOf("") }

    fun run(text: String) {
        val dir = File(sitesDir, safeName(name)).apply { mkdirs() }
        File(dir, "index.html").writeText(text)
        val port = LocalServer.start(dir)
        console.clear()
        projects = listProjects(sitesDir)
        url = "http://localhost:$port/"
    }

    fun clipboardText(): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }

    fun displayName(uri: Uri): String? = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    }

    val importHtml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            html = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            displayName(uri)?.substringBeforeLast('.')?.let { name = safeName(it) }
            msg = "Imported"
        } catch (e: Exception) { msg = "Import failed: ${e.message}" }
    }

    val addAsset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val dir = File(sitesDir, safeName(name)).apply { mkdirs() }
            val fn = (displayName(uri) ?: "asset").replace("/", "_")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> File(dir, fn).outputStream().use { i.copyTo(it) } }
            msg = "Added $fn (use it as ./$fn)"
        } catch (e: Exception) { msg = "Failed: ${e.message}" }
    }

    if (url != null) {
        BackHandler {
            val w = web
            if (w != null && w.canGoBack()) w.goBack() else { url = null; web = null }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { url = null; web = null }) { Text("← Editor") }
                Text(url ?: "", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { web?.reload() }) { Text("⟳") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = desktop, onClick = { desktop = !desktop }, label = { Text("Desktop mode") })
                FilterChip(selected = showConsole, onClick = { showConsole = !showConsole }, label = { Text("Console (${console.size})") })
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open in browser") }
            }
            if (showConsole) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 170.dp).padding(horizontal = 8.dp)) {
                    items(console) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { c ->
                    WebView.setWebContentsDebuggingEnabled(true)
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                console.add("[load error] ${error.description} · ${request.url}")
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                console.add("[${m.messageLevel()}] ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                                return true
                            }
                            override fun onJsAlert(view: WebView, u: String, message: String, result: JsResult): Boolean {
                                AlertDialog.Builder(c).setMessage(message)
                                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                                    .setOnCancelListener { result.cancel() }.show()
                                return true
                            }
                            override fun onJsConfirm(view: WebView, u: String, message: String, result: JsResult): Boolean {
                                AlertDialog.Builder(c).setMessage(message)
                                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                                    .setOnCancelListener { result.cancel() }.show()
                                return true
                            }
                        }
                        loadUrl(url!!)
                        web = this
                    }
                },
                update = { v ->
                    val ua = if (desktop) DESKTOP_UA else WebSettings.getDefaultUserAgent(ctx)
                    if (v.settings.userAgentString != ua) {
                        v.settings.userAgentString = ua
                        v.settings.useWideViewPort = desktop
                        v.settings.loadWithOverviewMode = desktop
                        v.reload()
                    }
                }
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Localhost runner", style = MaterialTheme.typography.headlineSmall)
        Text("Paste or import a single HTML file. It is served from a real http://localhost server on your phone, so localStorage, fetch, modules and JS all behave like on a PC.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = safeName(it) }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { run(html) }) { Text("Run ▶") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val t = clipboardText()
                if (t.isNullOrBlank()) msg = "Clipboard is empty" else { html = t; run(t) }
            }) { Text("Run clipboard") }
            OutlinedButton(onClick = { importHtml.launch(arrayOf("*/*")) }) { Text("Import .html") }
            OutlinedButton(onClick = { addAsset.launch(arrayOf("*/*")) }) { Text("Add asset") }
        }
        if (projects.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects) { p ->
                    InputChip(
                        selected = p == name, onClick = {
                            val f = File(sitesDir, "$p/index.html")
                            if (f.exists()) { html = f.readText(); name = p }
                        },
                        label = { Text(p) }
                    )
                }
            }
            TextButton(onClick = {
                File(sitesDir, safeName(name)).deleteRecursively()
                projects = listProjects(sitesDir)
                msg = "Deleted project"
            }) { Text("Delete project \"${safeName(name)}\"") }
        }
        OutlinedTextField(
            value = html, onValueChange = { html = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            label = { Text("index.html") }
        )
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}
