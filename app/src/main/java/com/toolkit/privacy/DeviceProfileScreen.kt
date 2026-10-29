package com.toolkit.privacy

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

data class DeviceProfile(val name: String, val w: Int, val h: Int, val ua: String)

private const val ANDROID_UA =
    "Mozilla/5.0 (Linux; Android 14; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val profiles = listOf(
    DeviceProfile("Pixel 8", 412, 915, ANDROID_UA.format("Pixel 8")),
    DeviceProfile("Galaxy S24", 360, 780, ANDROID_UA.format("SM-S921B")),
    DeviceProfile("Galaxy A15", 360, 800, ANDROID_UA.format("SM-A155F")),
    DeviceProfile("Tecno Spark", 360, 800, ANDROID_UA.format("TECNO KJ5")),
    DeviceProfile(
        "iPhone 15", 393, 852,
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    ),
    DeviceProfile("Small phone", 320, 568, ANDROID_UA.format("Small")),
    DeviceProfile(
        "Tablet", 800, 1280,
        "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )
)

/** Previews YOUR OWN site/web app at other screen sizes and user agents inside an in-app WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeviceProfileScreen() {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("https://") }
    var loaded by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(profiles[0]) }
    val dm = ctx.resources.displayMetrics

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Device preview (test your own site)", style = MaterialTheme.typography.titleMedium)
        Text(
            "This device: ${Build.MANUFACTURER} ${Build.MODEL} · " +
                "${(dm.widthPixels / dm.density).toInt()}×${(dm.heightPixels / dm.density).toInt()} dp",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { loaded = if (url.startsWith("http")) url else "https://$url" }) { Text("Load") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profiles) { p ->
                FilterChip(selected = sel == p, onClick = { sel = p }, label = { Text(p.name) })
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val scale = minOf(maxWidth.value / sel.w, maxHeight.value / sel.h, 1f)
            if (loaded.isEmpty()) {
                Text("Enter a URL and tap Load", Modifier.align(Alignment.Center))
            } else {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                        }
                    },
                    update = { v ->
                        v.settings.userAgentString = sel.ua
                        val key = "$loaded|${sel.name}"
                        if (v.tag != key) {
                            v.tag = key
                            v.loadUrl(loaded)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(sel.w.dp, sel.h.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                )
            }
        }
    }
}
