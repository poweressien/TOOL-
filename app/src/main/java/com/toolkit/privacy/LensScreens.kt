package com.toolkit.privacy

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private val modes = listOf("Identify", "Read", "Translate", "Plants", "Parts", "Defects", "Zoom")

@Composable
private fun LensImage(b: Bitmap?) {
    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 340.dp), contentScale = ContentScale.Fit)
}

private suspend fun still(cam: LensCam, max: Int = 1600): Bitmap? = cam.grab()?.let { LensAi.shrink(it, max) }

@Composable
fun RealityLensScreen() {
    val ctx = LocalContext.current
    val act = ctx.findActivity()
    var perm by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    if (!perm || act == null) {
        LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("RealityLens", style = MaterialTheme.typography.headlineSmall) }
            item { Section("Camera access", "RealityLens sees through your camera. Pictures are analysed on the phone and are not uploaded.") { Button(onClick = { permL.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") } } }
        }
        return
    }
    val cam = remember { LensCam(ctx, act) }
    DisposableEffect(Unit) { onDispose { cam.unbind() } }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("Identify") }
    var zoom by remember { mutableStateOf(1f) }
    var zMax by remember { mutableStateOf(1f) }
    var torch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { if (cam.ready) { zMax = cam.zoomMax() }; delay(700) } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("RealityLens", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AndroidView(factory = { c -> PreviewView(c).also { pv -> pv.scaleType = PreviewView.ScaleType.FILL_CENTER; cam.bind(pv) } }, modifier = Modifier.fillMaxWidth().height(300.dp))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Zoom ${"%.1f".format(zoom)}×", Modifier.width(92.dp), fontWeight = FontWeight.Medium)
                            Slider(zoom, { zoom = it; cam.setZoom(it) }, Modifier.weight(1f), valueRange = 1f..maxOf(zMax, 1.1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (cam.hasTorch()) FilterChip(selected = torch, onClick = { torch = !torch; cam.torch(torch) }, label = { Text("🔦 Light") })
                            Text("Hardware zoom up to ${"%.0f".format(zMax)}×. Use the Zoom tab for more.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { ChipRow(modes, mode) { mode = it; cam.live = null } }
        item {
            when (mode) {
                "Identify" -> IdentifyPanel(cam, scope)
                "Read" -> ReadPanel(cam, scope)
                "Translate" -> TranslatePanel(cam, scope)
                "Plants" -> PlantsPanel(cam, scope)
                "Parts" -> PartsPanel(cam, scope)
                "Defects" -> DefectsPanel(cam, scope)
                else -> ZoomPanel(cam, scope, zoom)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ identify

@Composable
private fun IdentifyPanel(cam: LensCam, scope: CoroutineScope) {
    val ctx = LocalContext.current
    var liveOn by remember { mutableStateOf(false) }
    var liveLabels by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var found by remember { mutableStateOf<List<LensAi.Obj>>(emptyList()) }
    var labels by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    var wiki by remember { mutableStateOf<LensAi.Wiki?>(null) }
    var msg by remember { mutableStateOf("") }
    val working = remember { booleanArrayOf(false) }

    LaunchedEffect(liveOn) {
        if (!liveOn) { cam.live = null; return@LaunchedEffect }
        cam.live = { bmp -> if (!working[0]) { working[0] = true; scope.launch { try { liveLabels = LensAi.labels(bmp, 0.5f).take(6) } catch (e: Exception) { }; working[0] = false } } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("What am I looking at?", "Names objects, animals, food, places and more. 400+ everyday categories, recognised on the phone.") {
            SwitchRow("Live labels", "Updates about once a second", liveOn) { liveOn = it; if (!it) liveLabels = emptyList() }
            if (liveOn) {
                if (liveLabels.isEmpty()) Hint("Point at something…")
                liveLabels.forEach { (n, c) -> Row(Modifier.fillMaxWidth()) { Text(n, Modifier.weight(1f), fontWeight = FontWeight.Medium); Text("${(c * 100).toInt()}%", style = MaterialTheme.typography.bodySmall) }; PercentBar((c * 100).toInt(), Info, 4) }
            }
            Button(enabled = !busy, onClick = {
                busy = true; msg = ""; wiki = null
                scope.launch {
                    try {
                        val b = still(cam, 1280)
                        if (b == null) msg = "Camera not ready yet." else {
                            labels = LensAi.labels(b, 0.4f).take(8); found = LensAi.objects(b)
                            val palette = listOf(0xFF22C55E, 0xFF3B82F6, 0xFFF59E0B, 0xFFEF4444, 0xFFA855F7, 0xFF14B8A6)
                            shot = LensAi.drawBoxes(b, found.mapIndexed { i, o -> Triple(o.box, "${o.label} ${(o.conf * 100).toInt()}%".replace(" 0%", ""), palette[i % palette.size].toInt()) })
                            val top = found.firstOrNull { it.conf > 0.5f }?.label?.takeIf { it != "Object" } ?: labels.firstOrNull()?.first
                            if (top != null) wiki = withContext(Dispatchers.IO) { LensAi.wiki(top) }
                            if (labels.isEmpty() && found.isEmpty()) msg = "Nothing recognised. Move closer and add light."
                        }
                    } catch (e: Throwable) { msg = "Recognition failed: ${e.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Analysing…" else "📸 Capture & name") }
            if (msg.isNotEmpty()) Hint(msg)
        }
        LensImage(shot)
        if (labels.isNotEmpty()) Section("Best guesses") {
            labels.forEach { (n, c) -> Row(Modifier.fillMaxWidth()) { Text(n, Modifier.weight(1f)); Text("${(c * 100).toInt()}%", style = MaterialTheme.typography.bodySmall) } }
            if (found.isNotEmpty()) Hint("${found.size} object(s) boxed in the picture.")
        }
        wiki?.let { w -> Section(w.title, w.desc.ifBlank { null }) {
            Text(w.extract.take(500) + if (w.extract.length > 500) "…" else "", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (w.url.isNotBlank()) OutlinedButton(onClick = { openUrl(ctx, w.url) }) { Text("Read more") }
                OutlinedButton(onClick = { openUrl(ctx, "https://www.google.com/search?q=" + java.net.URLEncoder.encode(w.title, "UTF-8")) }) { Text("Search web") }
            }
        } }
    }
}

// ------------------------------------------------------------------------------------------------ read

@Composable
private fun ReadPanel(cam: LensCam, scope: CoroutineScope) {
    val ctx = LocalContext.current
    var script by remember { mutableStateOf("Auto") }
    var busy by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var ents by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var risk by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Read text from the real world", "Documents, receipts, labels, boards, screens. Works offline.") {
            Text("Alphabet", style = MaterialTheme.typography.bodySmall)
            ChipRow(LensAi.scripts, script) { script = it }
            Button(enabled = !busy, onClick = {
                busy = true; info = ""
                scope.launch {
                    try {
                        val b = still(cam, 2400)
                        if (b == null) info = "Camera not ready yet." else {
                            val (used, t) = LensAi.ocr(b, script)
                            text = t.text.trim(); shot = b
                            val lang = if (text.length > 12) LensAi.detectLanguage(text) else null
                            info = if (text.isEmpty()) "No text found. Fill the frame, hold steady, add light." else "${text.lines().size} lines · $used alphabet${lang?.let { " · language: ${java.util.Locale(it).displayLanguage}" } ?: ""}"
                            ents = FileIntelLogic.entities(text); risk = SensitiveScan.scan(text).hits.size
                        }
                    } catch (e: Throwable) { info = "Reading failed: ${e.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Reading…" else "📄 Capture & read") }
            if (info.isNotEmpty()) Hint(info)
        }
        if (text.isNotEmpty()) {
            Section("Text") {
                MonoBox(text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { copyText(ctx, text) }) { Text("Copy") }; OutlinedButton(onClick = { shareText(ctx, text) }) { Text("Share") } }
                if (risk > 0) Text("⚠ Contains $risk sensitive-looking item(s) (card, ID, password…). Be careful where you paste it.", color = Warn, style = MaterialTheme.typography.bodySmall)
            }
            if (ents.isNotEmpty()) Section("Detected details") { ents.forEach { (k, v) -> Text(k, fontWeight = FontWeight.Bold); v.forEach { Text("  $it", style = MonoStyle) } } }
        }
        LensImage(shot)
    }
}

// ------------------------------------------------------------------------------------------------ translate

@Composable
private fun TranslatePanel(cam: LensCam, scope: CoroutineScope) {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    var target by remember { mutableStateOf(sp.getString(Prefs.LENS_TARGET_LANG, "English") ?: "English") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var pairs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Translate signs and text", "Point at a sign, menu or notice. The translation is painted over the picture. Language packs (~30 MB each) download once, then it works offline.") {
            Text("Translate into", style = MaterialTheme.typography.bodySmall)
            ChipRow(LensAi.languages.keys.toList(), target) { target = it; sp.edit().putString(Prefs.LENS_TARGET_LANG, it).apply() }
            Button(enabled = !busy, onClick = {
                busy = true; status = "Capturing…"; pairs = emptyList()
                scope.launch {
                    try {
                        val b = still(cam, 1800)
                        if (b == null) status = "Camera not ready yet." else {
                            status = "Reading text…"
                            val (_, t) = LensAi.ocr(b, "Auto")
                            val blocks = LensAi.blocks(t)
                            if (blocks.isEmpty()) status = "No text found." else {
                                val src = LensAi.detectLanguage(t.text) ?: "en"
                                status = "Translating from ${java.util.Locale(src).displayLanguage}… (first time downloads the language pack)"
                                val dst = LensAi.languages[target] ?: "en"
                                val out = LensAi.translate(blocks.map { it.text.replace('\n', ' ') }, src, dst)
                                if (out == null) status = "That language pair is not supported by the on-device translator." else {
                                    shot = LensAi.drawTranslated(b, blocks.map { it.box }.zip(out))
                                    pairs = blocks.map { it.text.replace('\n', ' ') }.zip(out)
                                    status = "Translated ${blocks.size} text block(s) to $target."
                                }
                            }
                        }
                    } catch (e: Throwable) { status = "Translation failed: ${e.message}. The first run needs internet to download the language pack." }
                    busy = false
                }
            }) { Text(if (busy) "Working…" else "🌍 Capture & translate") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Hint(status)
        }
        LensImage(shot)
        if (pairs.isNotEmpty()) Section("Side by side") {
            pairs.forEach { (a, b) -> Text(a, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(b, fontWeight = FontWeight.Medium) }
            OutlinedButton(onClick = { shareText(ctx, pairs.joinToString("\n\n") { "${it.first}\n→ ${it.second}" }) }) { Text("Share") }
        }
    }
}

// ------------------------------------------------------------------------------------------------ plants

@Composable
private fun PlantsPanel(cam: LensCam, scope: CoroutineScope) {
    val ctx = LocalContext.current
    val sp = remember { Prefs.sp(ctx) }
    var key by remember { mutableStateOf(sp.getString(Prefs.LENS_PLANTNET_KEY, "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var plantLabels by remember { mutableStateOf<List<Pair<String, Float>>>(emptyList()) }
    var hits by remember { mutableStateOf<List<LensAi.Plant>>(emptyList()) }
    var wiki by remember { mutableStateOf<LensAi.Wiki?>(null) }
    var msg by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Recognise plants", "Frame one leaf, flower or fruit. On the phone it tells you what kind of plant it is; add a free Pl@ntNet key to get the exact species.") {
            Button(enabled = !busy, onClick = {
                busy = true; msg = ""; hits = emptyList(); wiki = null
                scope.launch {
                    try {
                        val b = still(cam, 1280)
                        if (b == null) msg = "Camera not ready yet." else {
                            shot = b
                            val all = LensAi.labels(b, 0.3f)
                            plantLabels = all.filter { PlantWords.isPlant(it.first) }.take(6)
                            if (plantLabels.isEmpty()) msg = "Doesn't look like a plant. Try closer, with the leaf or flower filling the frame."
                            if (key.isNotBlank()) {
                                val r = withContext(Dispatchers.IO) { LensAi.plantNet(b, key.trim()) }
                                if (r == null) msg = "Pl@ntNet did not answer (check the key, quota or internet)." else hits = r
                            }
                            val q = hits.firstOrNull()?.name ?: plantLabels.firstOrNull()?.first
                            if (q != null) wiki = withContext(Dispatchers.IO) { LensAi.wiki(q) }
                        }
                    } catch (e: Throwable) { msg = "Failed: ${e.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Identifying…" else "🌿 Capture & identify") }
            if (msg.isNotEmpty()) Hint(msg)
        }
        LensImage(shot)
        if (hits.isNotEmpty()) Section("Species (Pl@ntNet)") {
            hits.forEach { h -> Row(Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { Text(h.name, fontWeight = FontWeight.Bold); if (h.common.isNotBlank()) Text(h.common, style = MaterialTheme.typography.bodySmall) }; Text("${(h.score * 100).toInt()}%") }; PercentBar((h.score * 100).toInt(), Good, 4) }
        }
        if (plantLabels.isNotEmpty()) Section("On-device read") { plantLabels.forEach { (n, c) -> Text("$n · ${(c * 100).toInt()}%") } }
        wiki?.let { w -> Section(w.title, w.desc.ifBlank { null }) { Text(w.extract.take(600), style = MaterialTheme.typography.bodyMedium); if (w.url.isNotBlank()) OutlinedButton(onClick = { openUrl(ctx, w.url) }) { Text("Read more") } } }
        Section("Exact species (optional)", "Pl@ntNet is a free plant-identification service. Make a free account at my.plantnet.org, copy your API key and paste it here. Photos are sent to Pl@ntNet only when a key is set.") {
            OutlinedTextField(key, { key = it; sp.edit().putString(Prefs.LENS_PLANTNET_KEY, it.trim()).apply() }, Modifier.fillMaxWidth(), label = { Text("Pl@ntNet API key") }, singleLine = true)
            OutlinedButton(onClick = { openUrl(ctx, "https://my.plantnet.org/") }) { Text("Get a free key") }
        }
    }
}

// ------------------------------------------------------------------------------------------------ parts

@Composable
private fun PartsPanel(cam: LensCam, scope: CoroutineScope) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var read by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf<List<ComponentKb.Part>>(emptyList()) }
    var smd by remember { mutableStateOf<List<String>>(emptyList()) }
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var typed by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val bands = remember { mutableStateListOf("brown", "black", "red", "gold") }
    var five by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val typedParts = remember(typed) { if (typed.isBlank()) emptyList() else ComponentKb.lookup(typed) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Explain electronic parts", "Aim at a chip, module or board. It reads the markings and explains the part from a built-in database of ~100 common components.") {
            Button(enabled = !busy, onClick = {
                busy = true; msg = ""
                scope.launch {
                    try {
                        val b = still(cam, 2400)
                        if (b == null) msg = "Camera not ready yet." else {
                            shot = b; val (_, t) = LensAi.ocr(b, "Latin"); read = t.text.replace('\n', ' ')
                            parts = ComponentKb.lookup(t.text); smd = Smd.find(t.text)
                            labels = LensAi.labels(b, 0.5f).take(5).map { it.first }
                            if (parts.isEmpty() && smd.isEmpty()) msg = if (read.isBlank()) "No markings readable. Get close, angle the light to avoid glare." else "Read “${read.take(60)}” but it's not in the database. Search it below."
                        }
                    } catch (e: Throwable) { msg = "Failed: ${e.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Reading…" else "🔬 Capture & explain") }
            if (msg.isNotEmpty()) Hint(msg)
            if (read.isNotBlank()) Text("Markings read: $read", style = MonoStyle)
            if (labels.isNotEmpty()) Hint("Looks like: ${labels.joinToString(", ")}")
        }
        LensImage(shot)
        (parts + typedParts).distinctBy { it.name }.forEach { p -> PartCard(p) }
        smd.forEach { Text("🔸 $it", style = MaterialTheme.typography.bodyMedium) }
        Section("Look up a part number") {
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text("e.g. LM358, ESP32, IRFZ44N") }, singleLine = true)
            if (typed.isNotBlank() && typedParts.isEmpty()) Hint("Not in the offline list.")
            if (typed.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openUrl(ctx, "https://www.google.com/search?q=" + java.net.URLEncoder.encode("$typed datasheet", "UTF-8")) }) { Text("Datasheet") }
                OutlinedButton(onClick = { openUrl(ctx, "https://www.alldatasheet.com/view.jsp?Searchword=" + java.net.URLEncoder.encode(typed, "UTF-8")) }) { Text("AllDatasheet") }
            }
        }
        Section("Decode a marking", "Resistor / capacitor codes printed on the part, e.g. 103, 4R7, 104, 2K2, 4n7") {
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Code on the part") }, singleLine = true)
            if (code.isNotBlank()) {
                Smd.resistor(code)?.let { Text("As resistor: $it", fontWeight = FontWeight.Medium) }
                Smd.capacitor(code)?.let { Text("As ceramic capacitor: $it", fontWeight = FontWeight.Medium) }
                if (Smd.resistor(code) == null && Smd.capacitor(code) == null) Hint("Not a recognised code.")
            }
        }
        Section("Resistor colour bands") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !five, onClick = { five = false; while (bands.size > 4) bands.removeAt(bands.size - 1); while (bands.size < 4) bands.add("black") }, label = { Text("4 bands") })
                FilterChip(selected = five, onClick = { five = true; while (bands.size < 5) bands.add(2, "black") }, label = { Text("5 bands") })
            }
            bands.forEachIndexed { i, c -> Text("Band ${i + 1}: $c", style = MaterialTheme.typography.bodySmall); ChipRow(ResistorBands.colors, c) { bands[i] = it } }
            ResistorBands.value(bands.toList())?.let { Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Good) } ?: Hint("Check the band colours (gold/silver only as multiplier or tolerance).")
        }
    }
}

@Composable
private fun PartCard(p: ComponentKb.Part) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row { Text(p.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(p.kind, style = MaterialTheme.typography.bodySmall, color = Info) }
        Text(p.info, style = MaterialTheme.typography.bodyMedium)
    } }
}

// ------------------------------------------------------------------------------------------------ defects

@Composable
private fun DefectsPanel(cam: LensCam, scope: CoroutineScope) {
    var compare by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var shot by remember { mutableStateOf<Bitmap?>(null) }
    var rep by remember { mutableStateOf<Defects.Report?>(null) }
    var ref by remember { mutableStateOf<Bitmap?>(null) }
    var msg by remember { mutableStateOf("") }
    fun small(b: Bitmap): Bitmap = Bitmap.createScaledBitmap(b, 640, (640f * b.height / b.width).toInt().coerceAtLeast(64), true)
    fun px(b: Bitmap): IntArray { val a = IntArray(b.width * b.height); b.getPixels(a, 0, b.width, 0, 0, b.width, b.height); return a }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Spot defects", "Finds cracks, scratches, stains, chips and burn marks. Works best on one plain surface in even light, filling the frame.") {
            ChipRow(listOf("Single photo", "Compare with a good one"), if (compare) "Compare with a good one" else "Single photo") { compare = it.startsWith("Compare"); rep = null; msg = "" }
            if (compare) {
                Button(enabled = !busy, onClick = { busy = true; scope.launch { ref = still(cam, 1200)?.let { small(it) }; msg = if (ref != null) "Reference saved. Now frame the item to test the same way and tap Check." else "Camera not ready."; busy = false } }) { Text(if (ref == null) "1. Capture a GOOD item" else "1. Re-capture reference") }
            }
            Button(enabled = !busy && (!compare || ref != null), onClick = {
                busy = true; msg = ""
                scope.launch {
                    try {
                        val b = still(cam, 1200)
                        if (b == null) msg = "Camera not ready yet." else {
                            val s = small(b)
                            val r = withContext(Dispatchers.Default) {
                                val g = Defects.toGray(px(s))
                                if (compare) { val rg = Defects.toGray(px(ref!!)); if (rg.size == g.size) Defects.diff(rg, g, s.width, s.height) else Defects.analyze(g, s.width, s.height) } else Defects.analyze(g, s.width, s.height)
                            }
                            rep = r
                            shot = LensAi.drawBoxes(s, r.flags.map { Triple(Rect(it.x, it.y, it.x + it.w, it.y + it.h), it.kind.take(18), if (it.strength > 2.5) 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()) })
                        }
                    } catch (e: Throwable) { msg = "Failed: ${e.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Inspecting…" else if (compare) "2. Check item" else "🔎 Capture & inspect") }
            if (msg.isNotEmpty()) Hint(msg)
        }
        LensImage(shot)
        rep?.let { r ->
            Section("Condition") {
                if (r.usable) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScoreGauge(r.score, 96.dp, "condition")
                    Column { Text(when { r.score >= 90 -> "Looks clean"; r.score >= 65 -> "Minor issues"; else -> "Defects found" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Text("${r.flags.size} area(s) flagged · sharpness ${r.sharpness.toInt()}", style = MaterialTheme.typography.bodySmall) }
                }
                r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                r.flags.forEachIndexed { i, f -> Text("${i + 1}. ${f.kind}${if (f.strength > 2.5) " (strong)" else ""}", style = MaterialTheme.typography.bodySmall) }
                Hint("This is a visual aid, not a certified inspection. Red = strong deviation, amber = mild.")
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ zoom

@Composable
private fun ZoomPanel(cam: LensCam, scope: CoroutineScope, hw: Float) {
    val ctx = LocalContext.current
    var crop by remember { mutableStateOf("2×") }
    var frames by remember { mutableStateOf("6") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var plain by remember { mutableStateOf<Bitmap?>(null) }
    var showPlain by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<List<Result>>(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Super zoom", "Step 1: use the Zoom slider above (real optics/sensor crop). Step 2: this tab takes several quick shots, lines them up to a fraction of a pixel and combines them for more detail than any one shot, then sharpens.") {
            Text("Extra digital crop (on top of ${"%.1f".format(hw)}×)", style = MaterialTheme.typography.bodySmall)
            ChipRow(listOf("1×", "2×", "3×", "4×"), crop) { crop = it }
            Text("Frames to combine", style = MaterialTheme.typography.bodySmall)
            ChipRow(listOf("1", "4", "6", "8"), frames) { frames = it }
            Button(enabled = !busy, onClick = {
                busy = true; saved = emptyList(); result = null; plain = null
                scope.launch {
                    try {
                        val n = frames.toInt(); val k = crop.dropLast(1).toInt()
                        val px = ArrayList<IntArray>(); var w = 0; var h = 0
                        for (i in 1..n) {
                            status = "Capturing $i / $n — hold the phone as still as you can"
                            val b = cam.grab() ?: continue
                            val cw = b.width / k; val ch = b.height / k
                            val c = LensAi.shrink(Bitmap.createBitmap(b, (b.width - cw) / 2, (b.height - ch) / 2, cw, ch), 1024)
                            if (w == 0) { w = c.width; h = c.height }
                            val sc = if (c.width != w || c.height != h) Bitmap.createScaledBitmap(c, w, h, true) else c
                            val a = IntArray(w * h); sc.getPixels(a, 0, w, 0, 0, w, h); px += a
                            if (i < n) delay(250)
                        }
                        if (px.isEmpty()) status = "Camera not ready yet." else {
                            status = "Combining ${px.size} frame(s)…"
                            val (hi, base) = withContext(Dispatchers.Default) {
                                val st = if (px.size > 1) SuperRes.stack(px, w, h, 2) else SuperRes.bilinear(px[0], w, h, 2)
                                SuperRes.unsharp(st, w * 2, h * 2, 0.9, 2) to SuperRes.bilinear(px[0], w, h, 2)
                            }
                            result = Bitmap.createBitmap(hi, w * 2, h * 2, Bitmap.Config.ARGB_8888); plain = Bitmap.createBitmap(base, w * 2, h * 2, Bitmap.Config.ARGB_8888)
                            val f = File(cacheOut(ctx), "superzoom_${System.currentTimeMillis()}.jpg")
                            withContext(Dispatchers.IO) { FileOutputStream(f).use { result!!.compress(Bitmap.CompressFormat.JPEG, 93, it) } }
                            saved = listOf(Result(f.name, f, 0, f.length(), "image/jpeg"))
                            status = "Done: ${w * 2}×${h * 2} px from ${px.size} frame(s)."
                        }
                    } catch (e: OutOfMemoryError) { status = "Not enough memory. Use fewer frames or a smaller crop." }
                    catch (e: Throwable) { status = "Failed: ${e.message}" }
                    busy = false
                }
            }) { Text(if (busy) "Working…" else "🔭 Capture super zoom") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Hint(status)
            Hint("Tips: lean on something solid, use the light, tap Capture and don't move until it finishes. A little natural hand tremor is actually what gives the extra detail.")
        }
        if (result != null) {
            SwitchRow("Compare with plain digital zoom", "", showPlain) { showPlain = it }
            LensImage(if (showPlain) plain else result)
            ResultList(saved)
        }
    }
}
