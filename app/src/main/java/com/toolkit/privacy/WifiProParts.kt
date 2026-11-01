package com.toolkit.privacy

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

fun apSigColor(rssi: Int) = if (rssi >= -60) Color(0xFF34D399) else if (rssi >= -75) Color(0xFFFBBF24) else Color(0xFFEF4444)

private fun yn(b: Boolean) = if (b) "yes" else "no"

/** Full breakdown of one access point (connected or not) as announced in its beacon. */
@Composable
fun ApDetailDialog(ap: ApDetail, ctx: Context, onDismiss: () -> Unit) {
    val e = ap.ext
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                copyText(ctx, "${ap.ssid}\nBSSID ${ap.bssid}\n${ap.band} ch ${ap.channel} ${ap.widthMhz} MHz\n${ap.standard}\n${ap.security}\n${ap.rssi} dBm")
            }) { Text("Copy") }
        },
        title = { Text(ap.ssid + if (ap.connected) "  (connected)" else "", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${ap.rssi} dBm · ${WifiPro.quality(ap.rssi)} · about ${"%.1f".format(ap.distanceM)} m", color = apSigColor(ap.rssi), fontWeight = FontWeight.Bold)
                Note("Distance is estimated from signal strength, so walls and antennas can make it off by a lot.")
                Kv("BSSID", ap.bssid, true)
                Kv("Maker", ap.vendor ?: if (ap.randomBssid) "Private/random address (phone hotspot?)" else "Unknown")
                Kv("Band · channel", "${ap.band} · ch ${ap.channel} (${ap.freq} MHz)")
                Kv("Width", "${ap.widthMhz} MHz (centre ${ap.centerMhz} MHz)")
                Kv("Wi-Fi generation", ap.standard)
                Kv("Security", ap.security)
                if (e.akm.isNotEmpty()) Kv("Key management", e.akm)
                if (e.ciphers.isNotEmpty()) Kv("Ciphers", e.ciphers)
                if (e.pmf.isNotEmpty()) Kv("Protected mgmt frames (11w)", e.pmf)
                if (e.parsed) {
                    Kv("Fast roaming 11r", yn(e.r)); Kv("Neighbour reports 11k", yn(e.k)); Kv("BSS transition 11v", yn(e.v))
                    if (e.stations >= 0) Kv("Devices connected to it", "${e.stations}")
                    if (e.utilPct >= 0) Kv("Channel busy", "${e.utilPct}%")
                    if (e.country.isNotEmpty()) Kv("Country code", e.country)
                    if (e.wpsState.isNotEmpty()) Kv("WPS", e.wpsState + if (e.wpsLocked) ", locked" else ", NOT locked")
                    if (e.wpsMaker.isNotEmpty()) Kv("Manufacturer (WPS)", e.wpsMaker)
                    if (e.wpsModel.isNotEmpty()) Kv("Model", e.wpsModel + if (e.wpsModelNo.isNotEmpty()) " (${e.wpsModelNo})" else "")
                    if (e.wpsName.isNotEmpty()) Kv("Device name", e.wpsName)
                    if (e.passpoint) Kv("Hotspot 2.0", "Passpoint network")
                    if (e.ftm) Kv("Ranging (802.11mc)", "supports Wi-Fi RTT")
                    Kv("Beacon elements read", "${e.ieCount}")
                } else Note("Detailed beacon data needs Android 11+ and Location on.")
                Kv("Hidden name", yn(ap.ssid == "(hidden)"))
                Kv("Last seen", "${ap.ageSec}s ago")
                Kv("Raw capabilities", ap.caps, true)
                if (e.risks.isNotEmpty()) {
                    Text("Security notes", fontWeight = FontWeight.Bold)
                    e.risks.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF59E0B)) }
                }
            }
        }
    )
}

/** Short badges shown on each list row. */
fun apBadges(ap: ApDetail): String {
    val e = ap.ext
    val b = mutableListOf<String>()
    if (e.stations >= 0) b += "${e.stations} device${if (e.stations == 1) "" else "s"}"
    if (e.utilPct >= 0) b += "${e.utilPct}% busy"
    if (e.wpsState.isNotEmpty()) b += if (e.wpsLocked) "WPS locked" else "WPS open"
    if (e.pmf == "required") b += "PMF"
    if (e.r) b += "11r"; if (e.k) b += "11k"; if (e.v) b += "11v"
    if (ap.passpoint) b += "Passpoint"
    if (ap.ssid == "(hidden)") b += "hidden"
    if (e.country.isNotEmpty()) b += e.country
    return b.joinToString(" · ")
}
