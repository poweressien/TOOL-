package com.toolkit.privacy

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun openUrl(ctx: Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
}

fun shareText(ctx: Context, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun clipboardText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}

/** Round 1-100 gauge, red -> yellow -> green. Same colours as the floating bubble. */
@Composable
fun ScoreGauge(score: Int, diameter: Dp = 140.dp, caption: String? = null) {
    val sweep by animateFloatAsState(targetValue = score.coerceIn(0, 100) / 100f, label = "gauge")
    val color = Color(SiteRules.colorFor(score))
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.width * 0.085f
            val box = Size(this.size.width - stroke, this.size.height - stroke)
            val tl = Offset(stroke / 2, stroke / 2)
            drawArc(Color(0x33888888), -90f, 360f, false, tl, box, style = Stroke(stroke))
            drawArc(color, -90f, 360f * sweep, false, tl, box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = (diameter.value * 0.30f).sp, fontWeight = FontWeight.Bold, color = color)
            Text(caption ?: "out of 100", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun FindingRow(f: Finding) {
    val (mark, c) = when {
        f.delta < 0 -> "▼" to Color(0xFFEF4444)
        f.delta > 0 -> "▲" to Color(0xFF34D399)
        else -> "•" to Color(0xFF8899AA)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, color = c, fontWeight = FontWeight.Bold)
        Text(f.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (f.delta != 0) Text(if (f.delta > 0) "+${f.delta}" else "${f.delta}", color = c, style = MaterialTheme.typography.bodySmall)
    }
}

/** Gauge + verdict + the reasons behind the number. */
@Composable
fun VerdictCard(title: String, label: String, score: Int, findings: List<Finding>, extra: List<String> = emptyList()) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreGauge(score, 110.dp)
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(label, color = Color(SiteRules.colorFor(score)), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    extra.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            val ordered = findings.sortedBy { it.delta }
            ordered.forEach { FindingRow(it) }
        }
    }
}

@Composable
fun LinkList(links: List<WebLink>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Look it up on the web", fontWeight = FontWeight.Bold)
            links.forEach { l ->
                TextButton(onClick = { openUrl(ctx, l.url) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(l.title)
                        if (l.hint.isNotEmpty()) Text(l.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun PermRow(title: String, why: String, ok: Boolean, action: String = "Allow", onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (ok) "✅" else "⚠️")
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) OutlinedButton(onClick = onGrant) { Text(action) }
    }
}

@Composable
fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
