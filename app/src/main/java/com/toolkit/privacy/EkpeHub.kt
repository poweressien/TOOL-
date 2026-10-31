package com.toolkit.privacy

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inwekobong.ekpe.MainActivity as EkpeActivity

/**
 * The EKPE suite (Analyzer, Intel, Split, Fonts, Cloud, Clock, Tools, Radar, Guard, Labs) lives in package
 * com.inwekobong.ekpe and is part of this same app. Each card opens the EKPE screen on the matching section.
 * The "Toolkit" tab at the end of the EKPE bar brings you back here. Nothing from either app was removed.
 */
private data class EkpeEntry(val tab: Int, val icon: String, val title: String, val blurb: String)

private val ekpeEntries = listOf(
    EkpeEntry(0, "📊", "Analyzer", "Signal, speed and Wi-Fi analysis with live graphs."),
    EkpeEntry(1, "🕵", "Intel", "SuperSmart entity intelligence: phones, emails, domains, usernames. Runs a Python engine on your phone."),
    EkpeEntry(2, "🪟", "Split", "Launch two apps in split-screen and floating resizable windows."),
    EkpeEntry(3, "🔤", "Fonts", "Fancy font styles and a system-wide font changer."),
    EkpeEntry(4, "☁", "Cloud", "Gallery watcher that backs new photos and videos up to Google Drive."),
    EkpeEntry(5, "🕒", "Clock", "Floating clock overlay with themes."),
    EkpeEntry(6, "🧰", "Tools", "EKPE utility tools."),
    EkpeEntry(7, "📡", "Radar", "Cell towers, Wi-Fi and Bluetooth devices, field meter, background alerts."),
    EkpeEntry(8, "🛡", "Guard", "LAN Guard, Bug Sweep, Device Finder, Network Toolkit, App Privacy Audit, Private Vault, Sound Lab."),
    EkpeEntry(9, "🧪", "Labs", "Coverage Mapper, Tower Locator, Offline AI, Automation, Script Lab, Phone-to-Phone Share, Media Forensics, Money Tracker."),
)

private fun openEkpe(ctx: Context, tab: Int) {
    ctx.startActivity(Intent(ctx, EkpeActivity::class.java).putExtra("tab", tab))
}

@Composable
fun EkpeHubScreen() {
    val ctx = LocalContext.current
    Page("EKPE suite", "Ten more tool groups, built into the same app. Tap one to open it.") {
        ekpeEntries.forEach { e ->
            Card(Modifier.fillMaxWidth().clickable { openEkpe(ctx, e.tab) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.icon, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(e.title, fontWeight = FontWeight.Bold)
                        Text(e.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
