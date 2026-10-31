package com.toolkit.privacy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.toolkit.privacy.FileIntelLogic.Cat

data class SmartEntry(val file: File, val size: Long, val modified: Long, val cat: Cat)

/** In-memory index shared by the Smart Files sections so the storage is walked once. */
object FileIndex {
    @Volatile var entries: List<SmartEntry> = emptyList()
    @Volatile var scannedAt: Long = 0L
}

private fun storageOk(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private fun rel(f: File) = (f.parentFile?.path ?: "").removePrefix(Environment.getExternalStorageDirectory().path)

fun openFile(ctx: Context, f: File) {
    try {
        val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, FileIntelLogic.mimeFor(f.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}

private fun shareFile(ctx: Context, f: File) {
    try {
        val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(FileIntelLogic.mimeFor(f.name)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}

private fun quickHash(f: File, full: Boolean): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().use { i ->
        val b = ByteArray(65536); var left = if (full) Long.MAX_VALUE else 65536L
        while (left > 0) { val n = i.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (n < 0) break; md.update(b, 0, n); left -= n }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

@Composable
private fun EntryRow(e: SmartEntry, selected: SnapshotStateMap<String, Boolean>? = null, note: String? = null) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) Checkbox(checked = selected[e.file.path] == true, onCheckedChange = { selected[e.file.path] = it })
            Text(e.cat.icon)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(e.file.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(rel(e.file) + " · " + dateFmt.format(Date(e.modified)), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Warn)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtSize(e.size), fontWeight = FontWeight.Bold)
                Row { TextButton(onClick = { openFile(ctx, e.file) }, contentPadding = PaddingValues(4.dp)) { Text("Open") }; TextButton(onClick = { shareFile(ctx, e.file) }, contentPadding = PaddingValues(4.dp)) { Text("Share") } }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ main

@Composable
fun SmartFilesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(storageOk(ctx)) }
    var section by remember { mutableStateOf("Overview") }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(FileIndex.entries) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = storageOk(ctx) }
    LaunchedEffect(granted) { while (!granted) { kotlinx.coroutines.delay(1500); granted = storageOk(ctx) } }

    fun scan() {
        scanning = true; status = "Indexing storage…"
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val root = Environment.getExternalStorageDirectory()
                val all = ArrayList<SmartEntry>()
                root.walkTopDown().onEnter { d -> !(d.parentFile == root && d.name == "Android") && !d.name.startsWith(".") }
                    .filter { it.isFile }.forEach { if (all.size < 400_000) all.add(SmartEntry(it, it.length(), it.lastModified(), FileIntelLogic.categorize(it.name))) }
                all
            }
            FileIndex.entries = list; FileIndex.scannedAt = System.currentTimeMillis(); entries = list
            status = "Indexed ${list.size} files (${fmtSize(list.sumOf { it.size })})"; scanning = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Smart files", style = MaterialTheme.typography.headlineSmall) }
        if (!granted) {
            item {
                Section("Allow file access", "Needed to index your files. The index stays in memory on this phone and is never uploaded.") {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else perm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }) { Text("Grant file access") }
                }
            }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !scanning, onClick = { scan() }) { Text(if (scanning) "Indexing…" else if (entries.isEmpty()) "Index my files" else "Re-index") }
                if (FileIndex.scannedAt > 0) Text("as of ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(FileIndex.scannedAt))}", style = MaterialTheme.typography.bodySmall)
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (status.isNotEmpty()) Hint(status)
        }
        item { ChipRow(listOf("Overview", "Search", "Duplicates", "Large", "PDF", "Smart ZIP"), section) { section = it } }
        if (entries.isEmpty() && section != "PDF") { item { Hint("Tap “Index my files” first.") }; return@LazyColumn }
        when (section) {
            "Overview" -> overview(entries) { entries = FileIndex.entries }
            "Search" -> searchSection(entries)
            "Duplicates" -> dupSection(entries) { gone -> entries = entries.filter { it.file.path !in gone }; FileIndex.entries = entries }
            "Large" -> largeSection(entries) { gone -> entries = entries.filter { it.file.path !in gone }; FileIndex.entries = entries }
            "PDF" -> pdfSection(entries)
            else -> zipSection(entries)
        }
    }
}

// ------------------------------------------------------------------------------------------------ overview + organize

private fun androidx.compose.foundation.lazy.LazyListScope.overview(entries: List<SmartEntry>, onChanged: () -> Unit) {
    item {
        val by = entries.groupBy { it.cat }
        Section("Automatic categories", "${entries.size} files sorted by type") {
            HBars(Cat.values().map { c -> "${c.icon} ${c.label} (${by[c]?.size ?: 0})" to (by[c]?.sumOf { it.size } ?: 0L).toDouble() }.filter { it.second > 0 }.sortedByDescending { it.second }) { fmtSize(it.toLong()) }
        }
    }
    item {
        val folders = entries.groupBy { it.file.parentFile?.path ?: "" }.map { (k, v) -> k to v.sumOf { it.size }.toDouble() }.sortedByDescending { it.second }.take(8)
        Section("Heaviest folders") { HBars(folders.map { it.first.removePrefix(Environment.getExternalStorageDirectory().path).ifEmpty { "/" } to it.second }) { fmtSize(it.toLong()) } }
    }
    item { OrganizeCard(entries, onChanged) }
}

@Composable
private fun OrganizeCard(entries: List<SmartEntry>, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val dl = remember { File(Environment.getExternalStorageDirectory(), "Download") }
    val loose = remember(entries) { entries.filter { it.file.parentFile == dl } }
    var msg by remember { mutableStateOf("") }
    var moved by remember { mutableStateOf(JsonStore.list(ctx, "file_moves").size) }
    Section("Tidy Downloads", "Moves loose files in Download into subfolders by type (Download/Images, Download/PDFs…). Nothing is deleted, and you can undo.") {
        val plan = loose.groupBy { it.cat }
        if (loose.isEmpty()) Hint("No loose files in Download.") else {
            plan.forEach { (c, l) -> Text("${c.icon} ${l.size} → Download/${c.label.replace(" ", "_").replace("(", "").replace(")", "")}/", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                val log = ArrayList<JSONObject>()
                loose.forEach { e ->
                    val dir = File(dl, e.cat.label.replace(" ", "_").replace("(", "").replace(")", "")).apply { mkdirs() }
                    var t = File(dir, e.file.name); var i = 1
                    while (t.exists()) { t = File(dir, e.file.nameWithoutExtension + " ($i)" + (if (e.file.extension.isNotEmpty()) "." + e.file.extension else "")); i++ }
                    if (e.file.renameTo(t)) log += JSONObject().put("from", e.file.path).put("to", t.path)
                }
                JsonStore.saveList(ctx, "file_moves", log); moved = log.size; msg = "Moved ${log.size} file(s). Re-index to refresh."; onChanged()
            }) { Text("Organize ${loose.size} file(s)") }
        }
        if (moved > 0) OutlinedButton(onClick = {
            var n = 0
            JsonStore.list(ctx, "file_moves").forEach { o -> val to = File(o.optString("to")); val from = File(o.optString("from")); if (to.exists() && !from.exists() && to.renameTo(from)) n++ }
            JsonStore.saveList(ctx, "file_moves", emptyList()); moved = 0; msg = "Undone: $n file(s) moved back."
        }) { Text("Undo last tidy ($moved)") }
        if (msg.isNotEmpty()) Hint(msg)
    }
}

// ------------------------------------------------------------------------------------------------ search

private fun androidx.compose.foundation.lazy.LazyListScope.searchSection(entries: List<SmartEntry>) {
    item { SearchCard(entries) }
}

private val textExt = setOf("txt", "md", "csv", "tsv", "json", "log", "html", "htm", "xml", "kt", "java", "js", "py", "yml", "yaml", "ini", "cfg", "sql", "css", "rtf")

@Composable
private fun SearchCard(entries: List<SmartEntry>) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<SmartEntry, String?>>>(emptyList()) }
    var desc by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Ask in plain words", "Try: “photos from last month bigger than 2 MB”, “pdf invoice 2024”, “documents containing school fees”, “videos older than 6 months”.") {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("What are you looking for?") }, minLines = 1, maxLines = 3)
            Button(enabled = text.isNotBlank() && !busy, onClick = {
                val q = FileIntelLogic.parse(text); desc = q.describe; busy = true; progress = ""
                scope.launch {
                    val base = withContext(Dispatchers.Default) {
                        entries.filter { FileIntelLogic.matches(q, it.file.name, it.size, it.modified, it.cat) }.sortedByDescending { it.modified }
                    }
                    if (q.content.isEmpty()) { results = base.take(150).map { it to null }; busy = false }
                    else {
                        val found = ArrayList<Pair<SmartEntry, String?>>()
                        withContext(Dispatchers.IO) {
                            val cands = base.filter { (FileIntelLogic.ext(it.file.name) in textExt && it.size < 3_000_000) || (it.cat == Cat.PDF && it.size < 25_000_000) }.take(600)
                            for ((i, e) in cands.withIndex()) {
                                if (i % 20 == 0) progress = "Reading ${i + 1}/${cands.size}…"
                                val body = try { if (e.cat == Cat.PDF) PdfText.extract(e.file.readBytes(), 60_000).text else e.file.readText().take(200_000) } catch (x: Exception) { "" }
                                val low = body.lowercase()
                                if (q.content.all { low.contains(it) } || (q.words.isEmpty() && q.content.any { low.contains(it) } && q.content.size == 1)) {
                                    val at = low.indexOf(q.content.first()); val s = maxOf(0, at - 40)
                                    found += e to "…" + body.substring(s, minOf(body.length, at + 90)).replace(Regex("\\s+"), " ") + "…"
                                    if (found.size >= 100) break
                                }
                            }
                        }
                        results = found; busy = false
                        progress = if (found.isEmpty()) "No text match (scanned text files and text-based PDFs)." else ""
                    }
                }
            }) { Text(if (busy) "Searching…" else "Search") }
            if (desc.isNotEmpty()) Hint("Understood as: $desc")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (progress.isNotEmpty()) Hint(progress)
        }
        if (results.isNotEmpty()) Text("${results.size} result(s)${if (results.size >= 150) " (showing first 150)" else ""}", fontWeight = FontWeight.Bold)
        results.forEach { (e, snip) -> EntryRow(e, null, snip) }
    }
}

// ------------------------------------------------------------------------------------------------ duplicates

private fun androidx.compose.foundation.lazy.LazyListScope.dupSection(entries: List<SmartEntry>, onGone: (Set<String>) -> Unit) {
    item { DupCard(entries, onGone) }
}

@Composable
private fun DupCard(entries: List<SmartEntry>, onGone: (Set<String>) -> Unit) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<List<SmartEntry>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    val sel: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
    var confirm by remember { mutableStateOf(false) }
    val chosen = sel.filter { it.value }.keys.map { File(it) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("Delete ${chosen.size} duplicate(s)?") },
        text = { Text("${fmtSize(chosen.sumOf { it.length() })} will be permanently deleted. At least one copy of every file is kept.") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            val gone = chosen.filter { it.delete() }.map { it.path }.toSet()
            groups = groups?.map { g -> g.filter { it.file.path !in gone } }?.filter { it.size > 1 }; gone.forEach { sel.remove(it) }; onGone(gone)
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Duplicate finder", "Matches by size, then a quick fingerprint, then a full SHA-1 so only truly identical files are grouped.") {
            Button(enabled = !busy, onClick = {
                busy = true; sel.clear()
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        val cands = entries.filter { it.size >= 20_000 }.groupBy { it.size }.filter { it.value.size > 1 }
                        val out = ArrayList<List<SmartEntry>>(); var i = 0
                        for ((_, l) in cands) {
                            i++; if (i % 25 == 0) progress = "Comparing $i / ${cands.size} size groups…"
                            val quick = l.groupBy { try { quickHash(it.file, false) } catch (e: Exception) { it.file.path } }.values.filter { it.size > 1 }
                            for (q in quick) q.groupBy { try { quickHash(it.file, true) } catch (e: Exception) { it.file.path } }.values.filter { it.size > 1 }.forEach { out += it.sortedBy { e -> e.modified } }
                        }
                        out.sortedByDescending { it[0].size * (it.size - 1) }
                    }
                    groups = res; busy = false; progress = ""
                    res.forEach { g -> g.drop(1).forEach { sel[it.file.path] = true } }
                }
            }) { Text(if (busy) "Comparing…" else "Find duplicates") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (progress.isNotEmpty()) Hint(progress)
            groups?.let { g ->
                val waste = g.sumOf { it[0].size * (it.size - 1) }
                Text(if (g.isEmpty()) "No duplicates found 🎉" else "${g.size} group(s) · ${fmtSize(waste)} reclaimable", fontWeight = FontWeight.Bold)
                if (g.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sel.clear(); g.forEach { x -> x.drop(1).forEach { sel[it.file.path] = true } } }) { Text("Keep oldest") }
                    OutlinedButton(onClick = { sel.clear(); g.forEach { x -> x.dropLast(1).forEach { sel[it.file.path] = true } } }) { Text("Keep newest") }
                }
                if (chosen.isNotEmpty()) Button(colors = ButtonDefaults.buttonColors(containerColor = Bad), onClick = { confirm = true }) { Text("Delete ${chosen.size} (${fmtSize(chosen.sumOf { it.length() })})") }
            }
        }
        groups?.forEachIndexed { gi, g ->
            Text("Group ${gi + 1} · ${g.size} × ${fmtSize(g[0].size)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            g.forEach { EntryRow(it, sel) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ large

private fun androidx.compose.foundation.lazy.LazyListScope.largeSection(entries: List<SmartEntry>, onGone: (Set<String>) -> Unit) {
    item { LargeCard(entries, onGone) }
}

@Composable
private fun LargeCard(entries: List<SmartEntry>, onGone: (Set<String>) -> Unit) {
    val odd = remember(entries) { FileIntelLogic.unusuallyLarge(entries.map { it.cat to it.size }) }
    val top = remember(entries) { entries.sortedByDescending { it.size }.take(25) }
    val sel: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
    var confirm by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val chosen = sel.filter { it.value }.keys.map { File(it) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("Delete ${chosen.size} file(s)?") }, text = { Text("${fmtSize(chosen.sumOf { it.length() })} will be permanently deleted.") },
        confirmButton = { TextButton(onClick = { confirm = false; val gone = chosen.filter { it.delete() }.map { it.path }.toSet(); gone.forEach { sel.remove(it) }; onGone(gone) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Unusually large files", "Compared with what is normal for each file type on your phone, not just raw size. ${odd.size} flagged.") {
            ChipRow(listOf("Unusual", "Top 25"), if (showAll) "Top 25" else "Unusual") { showAll = it == "Top 25" }
            if (chosen.isNotEmpty()) Button(colors = ButtonDefaults.buttonColors(containerColor = Bad), onClick = { confirm = true }) { Text("Delete ${chosen.size} (${fmtSize(chosen.sumOf { it.length() })})") }
        }
        if (showAll) top.forEach { EntryRow(it, sel) }
        else {
            if (odd.isEmpty()) Hint("Nothing stands out. Your biggest files are normal for their type.")
            odd.take(60).forEach { EntryRow(entries[it.index], sel, it.reason) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ PDF

private suspend fun ocrPdf(ctx: Context, uri: Uri, maxPages: Int, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
    val sb = StringBuilder()
    val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ""
    pfd.use { p ->
        PdfRenderer(p).use { r ->
            val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            for (i in 0 until minOf(r.pageCount, maxPages)) {
                progress("OCR page ${i + 1}/${minOf(r.pageCount, maxPages)}…")
                val page = r.openPage(i)
                val scale = 1600f / page.width.coerceAtLeast(1)
                val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); page.close()
                try { sb.append(rec.process(InputImage.fromBitmap(bmp, 0)).await().text).append("\n\n") } catch (e: Exception) { }
                bmp.recycle()
            }
            rec.close()
        }
    }
    sb.toString()
}

private fun androidx.compose.foundation.lazy.LazyListScope.pdfSection(entries: List<SmartEntry>) {
    item { PdfCard(entries) }
}

@Composable
private fun PdfCard(entries: List<SmartEntry>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<PdfText.Info?>(null) }
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
    var viaOcr by remember { mutableStateOf(false) }

    fun load(uri: Uri, label: String) {
        busy = true; status = "Reading…"; name = label; info = null; text = ""; viaOcr = false
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0) }
                if (bytes.size > 80_000_000) { status = "File is larger than 80 MB: too big to read here."; busy = false; return@launch }
                var i = withContext(Dispatchers.Default) { PdfText.extract(bytes) }
                if (!i.usable && !i.encrypted) {
                    status = "Trying the advanced PDF reader…"
                    withContext(Dispatchers.Default) { PdfBoxReader.read(ctx, bytes) }?.takeIf { it.usable }?.let { i = it }
                }
                info = i; text = i.text
                if (!i.usable) {
                    status = if (i.encrypted) "Password-protected PDF: cannot read text without the password." else "No embedded text found. Trying on-device OCR…"
                    if (!i.encrypted) { text = ocrPdf(ctx, uri, 12) { status = it }; viaOcr = true; status = if (text.isBlank()) "No readable text found." else "Text read with OCR (first 12 pages)." }
                } else status = "Text extracted directly from the PDF."
            } catch (e: Exception) { status = "Could not read this PDF: ${e.message}" }
            busy = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) load(u, u.lastPathSegment?.substringAfterLast('/') ?: "PDF") }
    val recent = remember(entries) { entries.filter { it.cat == Cat.PDF }.sortedByDescending { it.modified }.take(12) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Extract information from a PDF", "Reads text and details directly from the file; scanned PDFs are read with offline OCR. Nothing is uploaded.") {
            Button(enabled = !busy, onClick = { pick.launch(arrayOf("application/pdf")) }) { Text("Choose a PDF") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Hint(status)
        }
        if (recent.isNotEmpty() && info == null) Section("Recent PDFs on this phone") {
            recent.forEach { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(e.file.name, maxLines = 1); Text(fmtSize(e.size) + " · " + dateFmt.format(Date(e.modified)), style = MaterialTheme.typography.bodySmall) }
                    TextButton(enabled = !busy, onClick = { load(Uri.fromFile(e.file), e.file.name) }) { Text("Read") }
                }
            }
        }
        info?.let { i ->
            Section(name.ifEmpty { "PDF" }, "${i.pages} page(s)${if (viaOcr) " · OCR" else ""} · ${text.length} characters") {
                if (text.isNotBlank()) KV("Looks like", FileIntelLogic.guessDocType(text))
                i.meta.forEach { (k, v) -> KV(k, v) }
                if (i.encrypted) Text("🔒 Encrypted", color = Warn)
            }
            if (text.isNotBlank()) {
                val sum = remember(text) { FileIntelLogic.summarize(text, 3) }
                val kw = remember(text) { FileIntelLogic.keywords(text) }
                val ents = remember(text) { FileIntelLogic.entities(text) }
                val risk = remember(text) { SensitiveScan.scan(text) }
                if (sum.isNotEmpty() || kw.isNotEmpty()) Section("Quick summary") {
                    sum.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    if (kw.isNotEmpty()) Hint("Key terms: ${kw.joinToString(", ")}")
                }
                if (ents.isNotEmpty()) Section("Extracted details") {
                    ents.forEach { (k, v) -> Text(k, fontWeight = FontWeight.Bold); v.forEach { Text("  $it", style = MonoStyle) } }
                    OutlinedButton(onClick = { copyText(ctx, ents.entries.joinToString("\n\n") { (k, v) -> k + ":\n" + v.joinToString("\n") }) }) { Text("Copy details") }
                }
                if (risk.hits.isNotEmpty()) Section("⚠ Sensitive data inside", "${risk.hits.size} item(s) such as cards, IDs, keys or passwords. Be careful where you share this file.") {
                    risk.hits.take(8).forEach { Text("${it.label}: ${it.masked()}", style = MaterialTheme.typography.bodySmall) }
                }
                Section("Full text") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showText = !showText }) { Text(if (showText) "Hide" else "Show") }
                        OutlinedButton(onClick = { copyText(ctx, text) }) { Text("Copy") }
                        OutlinedButton(onClick = { shareText(ctx, text) }) { Text("Share") }
                    }
                    if (showText) MonoBox(text.take(12_000) + if (text.length > 12_000) "\n… (${text.length - 12_000} more characters)" else "")
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ smart zip

private fun androidx.compose.foundation.lazy.LazyListScope.zipSection(entries: List<SmartEntry>) {
    item { ZipCard(entries) }
}

@Composable
private fun ZipCard(entries: List<SmartEntry>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("One archive") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }
    val query = remember(q) { FileIntelLogic.parse(q) }
    val picked = remember(q, entries) {
        if (q.isBlank()) emptyList() else entries.filter { FileIntelLogic.matches(query, it.file.name, it.size, it.modified, it.cat) }.take(3000)
    }
    val total = picked.sumOf { it.size }
    val est = picked.sumOf { (it.size * FileIntelLogic.estimateRatio(it.file.name, it.cat)).toLong() }
    val stored = picked.count { FileIntelLogic.shouldStore(it.file.name) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Smart ZIP", "Describe what to pack. Already-compressed files (photos, video, music) are stored as-is so nothing is wasted, text and documents are squeezed.") {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("e.g. “pdfs from last month”, “photos 2024”, “documents older than 1 year”") }, minLines = 1, maxLines = 3)
            if (q.isNotBlank()) Hint("Understood as: ${query.describe}")
            ChipRow(listOf("One archive", "By type", "By month", "By folder"), strategy) { strategy = it }
            if (picked.isNotEmpty()) {
                Text("${picked.size} file(s) · ${fmtSize(total)} → about ${fmtSize(est)} (saves ~${if (total > 0) ((total - est) * 100 / total) else 0}%)", fontWeight = FontWeight.Medium)
                Hint("$stored already-compressed file(s) will be stored without recompression (faster, same size).")
            }
            Button(enabled = picked.isNotEmpty() && !busy, onClick = {
                busy = true; results = emptyList()
                scope.launch {
                    val out = withContext(Dispatchers.IO) {
                        val dir = cacheOut(ctx).apply { listFiles()?.filter { it.name.endsWith(".zip") }?.forEach { it.delete() } }
                        val plan = FileIntelLogic.planZips(picked.map { FileIntelLogic.ZipItem(it.file.name, it.cat, it.size, it.modified, it.file.parentFile?.name ?: "") }, strategy)
                        val res = ArrayList<Result>(); val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        for ((key, idx) in plan) {
                            val zf = File(dir, "${key}_$stamp.zip"); val names = HashSet<String>(); var inSize = 0L
                            ZipOutputStream(BufferedOutputStream(FileOutputStream(zf))).use { z ->
                                for (i in idx) {
                                    val e = picked[i]
                                    status = "Packing ${e.file.name}"
                                    var n = (e.file.parentFile?.name?.let { "$it/" } ?: "") + e.file.name; var c = 1
                                    while (!names.add(n)) { n = (e.file.parentFile?.name?.let { "$it/" } ?: "") + e.file.nameWithoutExtension + "_$c" + (if (e.file.extension.isNotEmpty()) "." + e.file.extension else ""); c++ }
                                    z.setLevel(if (FileIntelLogic.shouldStore(e.file.name)) 0 else 6)
                                    z.putNextEntry(ZipEntry(n).apply { time = e.modified })
                                    try { e.file.inputStream().use { it.copyTo(z) }; inSize += e.size } catch (x: Exception) { }
                                    z.closeEntry()
                                }
                            }
                            res += Result(zf.name, zf, inSize, zf.length(), "application/zip")
                        }
                        res
                    }
                    results = out; busy = false; status = "Created ${out.size} archive(s)"
                }
            }) { Text(if (busy) "Packing…" else "Create ZIP") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Hint(status)
        }
        ResultList(results)
    }
}
