package com.toolkit.privacy

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

private class ApiResp(val code: Int, val msg: String, val ms: Long, val size: Int, val headers: String, val body: String)

private fun prettyJson(s: String): String = try {
    when (val v = JSONTokener(s).nextValue()) {
        is JSONObject -> v.toString(2)
        is JSONArray -> v.toString(2)
        else -> s
    }
} catch (e: Exception) { s }

private fun parseHeaders(h: String): List<Pair<String, String>> = h.lines().mapNotNull { l ->
    val i = l.indexOf(':')
    if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
}

private fun send(method: String, url: String, headers: String, body: String): ApiResp {
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).followRedirects(true).build()
    val hs = parseHeaders(headers)
    val ct = hs.firstOrNull { it.first.equals("content-type", true) }?.second ?: "application/json"
    val needsBody = method in setOf("POST", "PUT", "PATCH")
    val rb = if (method == "GET" || method == "HEAD") null
    else if (body.isNotEmpty() || needsBody) body.toRequestBody(ct.toMediaTypeOrNull()) else null
    val req = Request.Builder().url(url).method(method, rb).apply { hs.forEach { (k, v) -> addHeader(k, v) } }.build()
    val t0 = System.nanoTime()
    client.newCall(req).execute().use { r ->
        val bytes = r.body?.bytes() ?: ByteArray(0)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val text = if (bytes.size > 500_000) String(bytes, 0, 500_000) + "\n…truncated" else String(bytes)
        val hdr = r.headers.joinToString("\n") { "${it.first}: ${it.second}" }
        return ApiResp(r.code, r.message, ms, bytes.size, hdr, prettyJson(text))
    }
}

@Composable
fun ApiTesterScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("api", Context.MODE_PRIVATE) }
    var method by remember { mutableStateOf("GET") }
    var url by remember { mutableStateOf("https://httpbin.org/get") }
    var headers by remember { mutableStateOf("Accept: application/json") }
    var body by remember { mutableStateOf("") }
    var reqTab by remember { mutableIntStateOf(0) }
    var resTab by remember { mutableIntStateOf(0) }
    var resp by remember { mutableStateOf<ApiResp?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf((prefs.getString("hist", "") ?: "").lines().filter { it.isNotBlank() }) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

    fun curl(): String = buildString {
        append("curl -X $method '$url'")
        parseHeaders(headers).forEach { (k, v) -> append(" -H '$k: $v'") }
        if (body.isNotEmpty()) append(" -d '${body.replace("'", "'\\''")}'")
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("API tester", style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m) }) }
        }
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = reqTab == 0, onClick = { reqTab = 0 }, label = { Text("Headers") })
            FilterChip(selected = reqTab == 1, onClick = { reqTab = 1 }, label = { Text("Body") })
        }
        if (reqTab == 0) OutlinedTextField(headers, { headers = it }, label = { Text("Name: value (one per line)") }, textStyle = mono, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
        else OutlinedTextField(body, { body = it }, label = { Text("Request body (JSON, text…)") }, textStyle = mono, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && url.isNotBlank(), onClick = {
                busy = true; error = ""; resp = null
                scope.launch {
                    try {
                        resp = withContext(Dispatchers.IO) { send(method, url.trim(), headers, body) }
                        history = (listOf("$method $url") + history.filter { it != "$method $url" }).take(10)
                        prefs.edit().putString("hist", history.joinToString("\n")).apply()
                    } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                    busy = false
                }
            }) { Text(if (busy) "Sending…" else "Send") }
            OutlinedButton(onClick = { copyText(ctx, curl()) }) { Text("Copy cURL") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text("Error: $error", color = MaterialTheme.colorScheme.error)
        resp?.let { r ->
            val c = when { r.code < 300 -> Color(0xFF34D399); r.code < 400 -> Color(0xFFFBBF24); else -> Color(0xFFEF4444) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.code} ${r.msg}", color = c, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("${r.ms} ms · ${fmtSize(r.size.toLong())}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = resTab == 0, onClick = { resTab = 0 }, label = { Text("Body") })
                        FilterChip(selected = resTab == 1, onClick = { resTab = 1 }, label = { Text("Headers") })
                        TextButton(onClick = { copyText(ctx, if (resTab == 0) r.body else r.headers) }) { Text("Copy") }
                    }
                    Text(if (resTab == 0) r.body else r.headers, style = mono)
                }
            }
        }
        if (history.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleSmall)
            history.forEach { h ->
                TextButton(onClick = { val i = h.indexOf(' '); method = h.substring(0, i); url = h.substring(i + 1) }) { Text(h, maxLines = 1) }
            }
        }
    }
}
