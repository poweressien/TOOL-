package com.toolkit.privacy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileItem(val file: File, val size: Long, val modified: Long)
data class DupGroup(val size: Long, val files: List<File>)

private fun hasStorageAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun sha1(f: File): String {
    val md = MessageDigest.getInstance("SHA-1")
    f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

@Composable
fun CleanerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasStorageAccess(ctx)) }
    var tab by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var big by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var old by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var dups by remember { mutableStateOf<List<DupGroup>>(emptyList()) }
    val selected: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
    var confirm by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM yyyy", Locale.US) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasStorageAccess(ctx) }
    LaunchedEffect(granted) { while (!granted) { delay(1500); granted = hasStorageAccess(ctx) } }

    fun scan() {
        scanning = true; selected.clear(); status = "Listing files…"
        scope.launch {
            withContext(Dispatchers.IO) {
                val root = Environment.getExternalStorageDirectory()
                val all = ArrayList<FileItem>()
                root.walkTopDown().onEnter { d -> !(d.parentFile == root && d.name == "Android") && !d.name.startsWith(".") }
                    .filter { it.isFile }.forEach { all.add(FileItem(it, it.length(), it.lastModified())) }
                big = all.sortedByDescending { it.size }.take(60)
                val cutoff = System.currentTimeMillis() - 60L * 86_400_000
                old = all.filter { it.file.path.contains("/Download") && it.modified < cutoff }.sortedByDescending { it.size }.take(100)
                val cands = all.filter { it.size >= 100_000 }.groupBy { it.size }.filter { it.value.size > 1 }
                val groups = ArrayList<DupGroup>()
                var i = 0
                for ((size, items) in cands) {
                    i++
                    status = "Hashing candidates $i / ${cands.size}…"
                    val byHash = items.groupBy { try { sha1(it.file) } catch (e: Exception) { it.file.path } }
                    byHash.values.filter { it.size > 1 }.forEach { g ->
                        val sorted = g.sortedBy { it.modified }
                        groups += DupGroup(size, sorted.map { it.file })
                        sorted.drop(1).forEach { selected[it.file.path] = true }
                    }
                }
                dups = groups.sortedByDescending { it.size * (it.files.size - 1) }
            }
            status = "Scan complete"
            scanning = false
        }
    }

    val selectedFiles = selected.filter { it.value }.keys.map { File(it) }
    val selectedSize = selectedFiles.sumOf { it.length() }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${selectedFiles.size} file(s)?") },
            text = { Text("${fmtSize(selectedSize)} will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val gone = selectedFiles.filter { it.delete() }.map { it.path }.toSet()
                    big = big.filter { it.file.path !in gone }
                    old = old.filter { it.file.path !in gone }
                    dups = dups.map { g -> DupGroup(g.size, g.files.filter { it.path !in gone }) }.filter { it.files.size > 1 }
                    gone.forEach { selected.remove(it) }
                    status = "Deleted ${gone.size} file(s)"
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item { Text("Storage cleaner", style = MaterialTheme.typography.headlineSmall) }
        item {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            val total = st.totalBytes
            val free = st.availableBytes
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${fmtSize(total - free)} used of ${fmtSize(total)}", fontWeight = FontWeight.Bold)
                    PercentBar(((total - free) * 100 / total.coerceAtLeast(1)).toInt(), Color(0xFF60A5FA), 12)
                    Text("${fmtSize(free)} free", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!granted) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Needs access to your files to find big, duplicate and old files. Nothing leaves your phone.")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 30) {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } else permLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                        }) { Text("Grant file access") }
                    }
                }
            }
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !scanning, onClick = { scan() }) { Text(if (scanning) "Scanning…" else "Scan storage") }
                if (selectedFiles.isNotEmpty()) Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    onClick = { confirm = true }
                ) { Text("Delete ${selectedFiles.size} (${fmtSize(selectedSize)})") }
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Biggest (${big.size})", "Duplicates (${dups.size})", "Old downloads (${old.size})").forEachIndexed { i, l ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(l) })
                }
            }
        }
        if (tab == 2 || tab == 0) {
            items(if (tab == 0) big else old, key = { "${tab}-${it.file.path}" }) { f ->
                FileRow(f.file, f.size, f.modified, fmt.format(Date(f.modified)), selected)
            }
        } else {
            dups.forEachIndexed { gi, g ->
                item(key = "g$gi-${g.files.first().path}") {
                    Text("Group ${gi + 1} · ${g.files.size} copies × ${fmtSize(g.size)} · wasting ${fmtSize(g.size * (g.files.size - 1))}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                items(g.files, key = { "d-" + it.path }) { f -> FileRow(f, g.size, f.lastModified(), fmt.format(Date(f.lastModified())), selected) }
            }
        }
    }
}

@Composable
private fun FileRow(f: File, size: Long, modified: Long, date: String, selected: SnapshotStateMap<String, Boolean>) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected[f.path] == true, onCheckedChange = { selected[f.path] = it })
            Column(Modifier.weight(1f)) {
                Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(f.parentFile?.path?.removePrefix(Environment.getExternalStorageDirectory().path) ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtSize(size), fontWeight = FontWeight.Bold)
                Text(date, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
