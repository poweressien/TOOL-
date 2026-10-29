package com.toolkit.privacy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

data class Place(val name: String, val lat: Double, val lon: Double)

private val presets = listOf(
    Place("Lagos", 6.5244, 3.3792),
    Place("Abuja", 9.0765, 7.3986),
    Place("London", 51.5072, -0.1276),
    Place("New York", 40.7128, -74.0060),
    Place("Tokyo", 35.6762, 139.6503),
    Place("Dubai", 25.2048, 55.2708)
)

private fun loadFavs(p: SharedPreferences): List<Place> =
    (p.getStringSet("favs", emptySet()) ?: emptySet()).mapNotNull { s ->
        val x = s.split("|")
        if (x.size != 3) null
        else x[1].toDoubleOrNull()?.let { la -> x[2].toDoubleOrNull()?.let { lo -> Place(x[0], la, lo) } }
    }.sortedBy { it.name }

private fun saveFavs(p: SharedPreferences, list: List<Place>) {
    p.edit().putStringSet("favs", list.map { "${it.name.replace("|", "/")}|${it.lat}|${it.lon}" }.toSet()).apply()
}

private class MapBridge(private val cb: (Double, Double) -> Unit) {
    @JavascriptInterface
    fun onPick(lat: Double, lon: Double) {
        Handler(Looper.getMainLooper()).post { cb(lat, lon) }
    }
}

private const val MAP_HTML = """
<html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>html,body,#m{height:100%;margin:0}</style></head>
<body><div id="m"></div><script>
var map=L.map('m').setView([LAT,LON],5);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
var mk=L.marker([LAT,LON]).addTo(map);
map.on('click',function(e){mk.setLatLng(e.latlng);Android.onPick(e.latlng.lat,e.latlng.lng);});
function setPos(a,b){mk.setLatLng([a,b]);map.setView([a,b],12);}
</script></body></html>
"""

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MockLocationScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("mock", Context.MODE_PRIVATE) }
    var lat by remember { mutableStateOf("6.5244") }
    var lon by remember { mutableStateOf("3.3792") }
    var favName by remember { mutableStateOf("") }
    var favs by remember { mutableStateOf(loadFavs(prefs)) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var msg by remember { mutableStateOf("") }
    val state by MockLocationService.state.collectAsState()

    fun goTo(p: Place) {
        lat = p.lat.toString(); lon = p.lon.toString()
        web?.evaluateJavascript("setPos(${p.lat},${p.lon})", null)
    }

    fun start() {
        val la = lat.toDoubleOrNull()
        val lo = lon.toDoubleOrNull()
        if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
            msg = "Enter a valid latitude (-90..90) and longitude (-180..180)"
            return
        }
        msg = ""
        val i = Intent(ctx, MockLocationService::class.java).putExtra("lat", la).putExtra("lon", lo)
        ContextCompat.startForegroundService(ctx, i)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) start() else msg = "Location permission is required"
    }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(MapBridge { la, lo -> lat = "%.6f".format(la); lon = "%.6f".format(lo) }, "Android")
                    loadDataWithBaseURL(
                        "https://localhost/",
                        MAP_HTML.replace("LAT", "6.5244").replace("LON", "3.3792"),
                        "text/html", "utf-8", null
                    )
                    web = this
                }
            }
        )
        Column(
            Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Tap the map to pick a test position · $state", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets) { p -> AssistChip(onClick = { goTo(p) }, label = { Text(p.name) }) }
                items(favs) { p ->
                    InputChip(
                        selected = false,
                        onClick = { goTo(p) },
                        label = { Text("★ ${p.name}") },
                        trailingIcon = {
                            Text("✕", Modifier.clickable {
                                favs = favs - p
                                saveFavs(prefs, favs)
                            })
                        }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(favName, { favName = it }, label = { Text("Favorite name") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val la = lat.toDoubleOrNull()
                    val lo = lon.toDoubleOrNull()
                    if (la != null && lo != null) {
                        favs = (favs + Place(favName.ifBlank { "Pin" }, la, lo)).sortedBy { it.name }
                        saveFavs(prefs, favs)
                        favName = ""
                    }
                }) { Text("Save ★") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val perms = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray()
                    permLauncher.launch(perms)
                }) { Text("Start") }
                OutlinedButton(onClick = { ctx.stopService(Intent(ctx, MockLocationService::class.java)) }) { Text("Stop") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Dev options") }
            }
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
            Text(
                "One-time setup: Developer options → Select mock location app → Privacy Toolkit.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
