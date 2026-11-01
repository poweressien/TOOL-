package com.toolkit.privacy.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.toolkit.privacy.MainActivity
import com.toolkit.privacy.Prefs

/**
 * The user's own home-screen arrangement: which tiles are hidden and in what order they appear.
 * Stored on the phone only. Long-press a tile to change it.
 */
object HomeLayout {
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set
    var order by mutableStateOf<List<String>>(emptyList())
        private set
    private var loaded = false

    fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = Prefs.sp(ctx)
        hidden = sp.getStringSet("home_hidden", emptySet())?.toSet() ?: emptySet()
        order = (sp.getString("home_order", "") ?: "").split("|").filter { it.isNotBlank() }
    }

    private fun save(ctx: Context) {
        Prefs.sp(ctx).edit().putStringSet("home_hidden", hidden.toSet()).putString("home_order", order.joinToString("|")).apply()
    }

    /** All tools in the user's order (tools without a saved position keep their built-in order, after the saved ones). */
    fun ordered(): List<ToolDef> {
        val idx = HashMap<String, Int>()
        order.forEachIndexed { i, n -> idx[n] = i }
        return Catalog.tools.withIndex().sortedBy { idx[it.value.name] ?: (10_000 + it.index) }.map { it.value }
    }

    fun visible(): List<ToolDef> = ordered().filter { it.name !in hidden }
    fun hiddenTools(): List<ToolDef> = ordered().filter { it.name in hidden }

    fun hide(ctx: Context, t: ToolDef) { hidden = hidden + t.name; save(ctx) }
    fun show(ctx: Context, t: ToolDef) { hidden = hidden - t.name; save(ctx) }
    fun reset(ctx: Context) { hidden = emptySet(); order = emptyList(); save(ctx) }

    /** Position of a tile among the visible tiles of its section (1-based) and how many there are. */
    fun position(t: ToolDef): Pair<Int, Int> {
        val v = visible().filter { it.group == t.group }
        return (v.indexOfFirst { it.name == t.name } + 1) to v.size
    }

    /** where: -2 = first, -1 = one step earlier, 1 = one step later, 2 = last (always inside the tile's own section). */
    fun move(ctx: Context, t: ToolDef, where: Int) {
        val full = ordered().map { it.name }.toMutableList()
        val vis = visible().filter { it.group == t.group }.map { it.name }
        val p = vis.indexOf(t.name)
        if (p < 0) return
        val target = when (where) { -2 -> 0; -1 -> p - 1; 1 -> p + 1; else -> vis.size - 1 }.coerceIn(0, vis.size - 1)
        if (target == p) return
        val tn = vis[target]
        full.remove(t.name)
        full.add(full.indexOf(tn) + (if (target > p) 1 else 0), t.name)
        order = full
        save(ctx)
    }
}

private fun blurb(g: Group) = when (g) {
    Group.DEV -> "Run web pages and build apps right on your phone."
    Group.FILES -> "Compress, convert, protect and clean your files and media."
    Group.NET -> "Check and improve your connection, Wi-Fi and signal."
    Group.SEC -> "VPN, DNS filtering, passwords and encrypted storage."
    Group.UTIL -> "Everyday utilities and developer helpers."
    Group.ASSIST -> "Helpers that watch for risky links, scams and background activity."
    Group.HEALTH -> "Steps, heart, sleep and trends from your phone's sensors."
    Group.PRICE -> "Scan, track and compare prices, rates and spending."
    Group.RADIO -> "Radio, sound and environment sensing around you."
    Group.LENS -> "Camera-based recognition and translation."
    Group.GEO -> "Places, trips, parking and location reminders."
    Group.EKPE -> "The EKPE suite tools."
    Group.GUARD -> "Network and device protection tools."
    Group.LABS -> "Experimental tools."
}

private fun toolIntent(ctx: Context, t: ToolDef): Intent {
    val i = if (t.ekpe) Intent(ctx, com.inwekobong.ekpe.MainActivity::class.java).putExtra("tab", t.tab).also { if (t.sub >= 0) it.putExtra("sub", t.sub) }
    else Intent(ctx, MainActivity::class.java).putExtra("tab", t.tab).putExtra("sub", t.sub)
    return i.setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

private fun shortcutBitmap(t: ToolDef): Bitmap {
    val s = 216
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF14151A.toInt()
    c.drawRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), p)
    p.color = (t.group.color and 0x00FFFFFF) or 0x33000000
    c.drawRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), p)
    Glyphs.androidPath(t.icon)?.let { path ->
        val k = s * 0.46f / 24f
        val m = Matrix()
        m.setScale(k, k)
        m.postTranslate((s - 24 * k) / 2f, (s - 24 * k) / 2f)
        path.transform(m)
        p.color = t.group.color
        p.style = Paint.Style.FILL
        c.drawPath(path, p)
    }
    return bmp
}

/** "Export as individual app": a launcher icon on the phone's home screen that opens straight into this tool. */
fun pinToPhone(ctx: Context, t: ToolDef) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        Toast.makeText(ctx, "This phone's launcher can't add shortcuts. Try long-pressing the EKPE icon instead.", Toast.LENGTH_LONG).show()
        return
    }
    val info = ShortcutInfoCompat.Builder(ctx, "tool_" + t.name.replace(" ", "_"))
        .setShortLabel(t.name).setLongLabel(t.name + " · EKPE")
        .setIcon(IconCompat.createWithAdaptiveBitmap(shortcutBitmap(t)))
        .setIntent(toolIntent(ctx, t)).build()
    val ok = try { ShortcutManagerCompat.requestPinShortcut(ctx, info, null) } catch (e: Exception) { false }
    Toast.makeText(ctx, if (ok) "Confirm \"Add\" to put ${t.name} on your home screen." else "Could not add the shortcut.", Toast.LENGTH_LONG).show()
}

@Composable
private fun ActionRow(icon: String, label: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(icon, MaterialTheme.colorScheme.primary, 24.dp)
        Column(Modifier.padding(start = 16.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The menu that pops up when a tile is long-pressed. */
@Composable
fun ToolActionsDialog(t: ToolDef, onDismiss: () -> Unit, onOpen: () -> Unit, onReposition: () -> Unit, onDetails: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(t.icon, androidx.compose.ui.graphics.Color(t.group.color), 30.dp)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(t.name, fontWeight = FontWeight.Bold)
                    Text(t.group.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ActionRow("play_arrow", "Open") { onDismiss(); onOpen() }
                ActionRow("open_with", "Reposition", "Move it earlier or later in its section") { onDismiss(); onReposition() }
                ActionRow("visibility_off", "Hide app", "Remove from home. Bring it back in Settings > Hidden apps") { onDismiss(); HomeLayout.hide(ctx, t) }
                ActionRow("add_to_home_screen", "Add to phone home screen", "Its own launcher icon that opens straight into ${t.name}") { onDismiss(); pinToPhone(ctx, t) }
                ActionRow("info", "More details") { onDismiss(); onDetails() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun RepositionDialog(t: ToolDef, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val (pos, total) = HomeLayout.position(t)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Move ${t.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Position $pos of $total in ${t.group.title}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { HomeLayout.move(ctx, t, -2) }, enabled = pos > 1, modifier = Modifier.weight(1f)) { Text("First") }
                    OutlinedButton(onClick = { HomeLayout.move(ctx, t, -1) }, enabled = pos > 1, modifier = Modifier.weight(1f)) { Text("◀") }
                    OutlinedButton(onClick = { HomeLayout.move(ctx, t, 1) }, enabled = pos < total, modifier = Modifier.weight(1f)) { Text("▶") }
                    OutlinedButton(onClick = { HomeLayout.move(ctx, t, 2) }, enabled = pos < total, modifier = Modifier.weight(1f)) { Text("Last") }
                }
                Text("The tile moves right away behind this box. Tiles stay inside their own section.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } }
    )
}

@Composable
fun ToolDetailsDialog(t: ToolDef, onDismiss: () -> Unit) {
    val (pos, total) = HomeLayout.position(t)
    @Composable fun line(k: String, v: String) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                line("Section", t.group.title + " · " + blurb(t.group))
                line("Opens", if (t.ekpe) "On the EKPE screen, straight onto this tool" else "Inside the main app")
                line("Position", "$pos of $total in ${t.group.title}")
                line("Needs", "The master switch ON. Some tools ask for permissions the first time you use them (location, camera, notifications and so on).")
                line("Phone shortcut", "Long-press > Add to phone home screen gives it its own launcher icon.")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Everything runs on your phone; nothing is uploaded unless the tool says so.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Settings > Hidden apps */
@Composable
fun HiddenAppsPage() {
    val ctx = LocalContext.current
    HomeLayout.ensure(ctx)
    val list = HomeLayout.hiddenTools()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (list.isEmpty()) "No hidden apps. Long-press any tile on the home screen and choose Hide app."
            else "${list.size} hidden. Tap Show to bring one back to the home screen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        list.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(t.icon, androidx.compose.ui.graphics.Color(t.group.color), 28.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(t.name)
                    Text(t.group.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { HomeLayout.show(ctx, t) }) { Text("Show") }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        OutlinedButton(onClick = { HomeLayout.reset(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Reset order and show all apps") }
    }
}
