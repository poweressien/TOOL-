package com.toolkit.privacy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class WifiLink(
    val ssid: String, val bssid: String, val rssi: Int, val linkSpeed: Int, val txSpeed: Int, val rxSpeed: Int,
    val freq: Int, val channel: Int, val band: String, val standard: String, val security: String,
    val ip: String, val gateway: String, val netmask: String, val dns: List<String>, val dhcp: String, val leaseSec: Int,
    val domain: String, val mtu: Int, val hiddenSsid: Boolean, val deviceMac: String
)

data class ApDetail(
    val ssid: String, val bssid: String, val rssi: Int, val freq: Int, val channel: Int, val band: String,
    val widthMhz: Int, val centerMhz: Int, val standard: String, val security: String, val caps: String,
    val vendor: String?, val distanceM: Double, val wps: Boolean, val connected: Boolean, val ageSec: Long, val randomBssid: Boolean
)

data class ChannelScore(val channel: Int, val aps: Int, val congestion: Int)
data class SavedWifi(val ssid: String, val password: String?, val security: String, val source: String)

object WifiPro {
    fun channelOf(f: Int) = when {
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5000..5900 -> (f - 5000) / 5
        f > 5900 -> (f - 5950) / 5
        else -> 0
    }

    fun bandOf(f: Int) = when { f > 5900 -> "6 GHz"; f > 4900 -> "5 GHz"; else -> "2.4 GHz" }

    fun secOf(c: String) = when {
        c.contains("SAE") && c.contains("PSK") -> "WPA2/WPA3"
        c.contains("SAE") -> "WPA3"
        c.contains("OWE") -> "Enhanced Open (OWE)"
        c.contains("EAP") -> "WPA2-Enterprise"
        c.contains("WPA2") || c.contains("RSN") -> "WPA2"
        c.contains("WPA-") || c.contains("[WPA]") -> "WPA (weak)"
        c.contains("WEP") -> "WEP (broken)"
        else -> "Open"
    }

    fun widthOf(w: Int) = when (w) { 0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320; else -> 20 }

    fun standardOf(s: Int) = when (s) { 1 -> "Legacy"; 4 -> "Wi-Fi 4 (n)"; 5 -> "Wi-Fi 5 (ac)"; 6 -> "Wi-Fi 6 (ax)"; 7 -> "WiGig (ad)"; 8 -> "Wi-Fi 7 (be)"; else -> "?" }

    fun distance(freqMhz: Int, rssi: Int): Double = 10.0.pow((27.55 - 20 * log10(freqMhz.toDouble()) + abs(rssi)) / 20.0)

    fun ipStr(i: Int): String = if (i == 0) "" else "${i and 255}.${(i shr 8) and 255}.${(i shr 16) and 255}.${(i shr 24) and 255}"

    fun quality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"; rssi >= -60 -> "Very good"; rssi >= -70 -> "Good"; rssi >= -80 -> "Weak"; else -> "Very weak"
    }

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<ApDetail> {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cur = try { wm.connectionInfo?.bssid } catch (e: Exception) { null }
        val results = try { wm.scanResults } catch (e: SecurityException) { emptyList<ScanResult>() }
        val nowUs = android.os.SystemClock.elapsedRealtime() * 1000
        return results.map { r ->
            val width = if (Build.VERSION.SDK_INT >= 23) widthOf(r.channelWidth) else 20
            val center = if (Build.VERSION.SDK_INT >= 23 && r.centerFreq0 > 0 && width > 20) r.centerFreq0 else r.frequency
            val std = if (Build.VERSION.SDK_INT >= 30) standardOf(r.wifiStandard) else "?"
            val caps = r.capabilities ?: ""
            val bssid = r.BSSID ?: ""
            ApDetail(
                ssid = (r.SSID ?: "").ifBlank { "(hidden)" }, bssid = bssid, rssi = r.level, freq = r.frequency,
                channel = channelOf(r.frequency), band = bandOf(r.frequency), widthMhz = width, centerMhz = center, standard = std,
                security = secOf(caps), caps = caps, vendor = Oui.offline(bssid), distanceM = distance(r.frequency, r.level),
                wps = caps.contains("WPS"), connected = bssid == cur, ageSec = max(0L, (nowUs - r.timestamp) / 1_000_000),
                randomBssid = bssid.isNotEmpty() && Oui.isRandom(bssid)
            )
        }.distinctBy { it.bssid }.sortedByDescending { it.rssi }
    }

    @Suppress("DEPRECATION")
    fun connection(ctx: Context, aps: List<ApDetail>): WifiLink? {
        val app = ctx.applicationContext
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val wi = try { wm.connectionInfo } catch (e: Exception) { null } ?: return null
        val dh = try { wm.dhcpInfo } catch (e: Exception) { null }
        val lp = cm.getLinkProperties(cm.activeNetwork)
        val bssid = wi.bssid ?: ""
        val ap = aps.firstOrNull { it.bssid == bssid }
        val std = if (Build.VERSION.SDK_INT >= 30) standardOf(wi.wifiStandard) else ap?.standard ?: "?"
        val tx = if (Build.VERSION.SDK_INT >= 29) wi.txLinkSpeedMbps else -1
        val rx = if (Build.VERSION.SDK_INT >= 29) wi.rxLinkSpeedMbps else -1
        val dnsList = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        return WifiLink(
            ssid = (wi.ssid ?: "").trim('"').ifBlank { "(unknown, grant Location)" }, bssid = bssid, rssi = wi.rssi, linkSpeed = wi.linkSpeed,
            txSpeed = tx, rxSpeed = rx, freq = wi.frequency, channel = channelOf(wi.frequency), band = bandOf(wi.frequency), standard = std,
            security = ap?.security ?: "?", ip = ipStr(wi.ipAddress), gateway = ipStr(dh?.gateway ?: 0), netmask = ipStr(dh?.netmask ?: 0),
            dns = dnsList.ifEmpty { listOf(ipStr(dh?.dns1 ?: 0), ipStr(dh?.dns2 ?: 0)).filter { it.isNotEmpty() } },
            dhcp = ipStr(dh?.serverAddress ?: 0), leaseSec = dh?.leaseDuration ?: 0, domain = lp?.domains.orEmpty(),
            mtu = if (Build.VERSION.SDK_INT >= 29) (lp?.mtu ?: 0) else 0, hiddenSsid = wi.hiddenSSID, deviceMac = wi.macAddress ?: ""
        )
    }

    /** How crowded each channel is, counting overlapping bandwidth weighted by signal strength. */
    fun channelScores(aps: List<ApDetail>, band: String): List<ChannelScore> {
        val inBand = aps.filter { it.band == band }
        val candidates: List<Int> = when (band) {
            "2.4 GHz" -> (1..13).toList()
            "5 GHz" -> listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
            else -> (1..233 step 16).toList()
        }
        return candidates.map { ch ->
            val f = when (band) { "2.4 GHz" -> 2407 + 5 * ch; "5 GHz" -> 5000 + 5 * ch; else -> 5950 + 5 * ch }
            var load = 0.0; var n = 0
            inBand.forEach { ap ->
                val half = max(ap.widthMhz, 20) / 2.0
                val lo = ap.centerMhz - half - (if (band == "2.4 GHz") 1.0 else 0.0)
                val hi = ap.centerMhz + half + (if (band == "2.4 GHz") 1.0 else 0.0)
                val ov = max(0.0, min(hi, f + 10.0) - max(lo, f - 10.0)) / 20.0
                if (ov > 0) {
                    n++
                    val strength = ((ap.rssi + 100).coerceIn(0, 60)) / 60.0
                    load += ov * (0.25 + 0.75 * strength * strength) * 100.0 / 3.0
                }
            }
            ChannelScore(ch, n, min(100.0, load).toInt())
        }
    }

    fun bestChannels(scores: List<ChannelScore>, band: String): List<ChannelScore> {
        val pool = if (band == "2.4 GHz") scores.filter { it.channel in listOf(1, 6, 11) } else scores
        return pool.sortedWith(compareBy({ it.congestion }, { it.aps })).take(3)
    }

    /** Interference classification for the connected channel. */
    fun interference(link: WifiLink?, aps: List<ApDetail>): List<String> {
        val out = mutableListOf<String>()
        if (link == null) return out
        val same = aps.filter { it.channel == link.channel && it.band == link.band && it.bssid != link.bssid }
        val adj = if (link.band == "2.4 GHz") aps.filter { abs(it.channel - link.channel) in 1..4 && it.band == link.band } else emptyList()
        val strongSame = same.count { it.rssi >= -70 }
        if (strongSame >= 3) out += "Heavy co-channel interference: $strongSame strong networks share channel ${link.channel}."
        else if (same.isNotEmpty()) out += "Co-channel interference: ${same.size} other network(s) on channel ${link.channel} (they take turns using the air)."
        if (adj.count { it.rssi >= -70 } >= 2) out += "Adjacent-channel overlap from ${adj.size} network(s): worse than sharing a channel. Move your router to 1, 6 or 11."
        if (link.band == "2.4 GHz" && aps.any { it.band == "5 GHz" } && link.rssi >= -65) out += "You are on 2.4 GHz but 5 GHz networks are visible: 5 GHz is usually faster and less crowded."
        if (link.rssi < -75) out += "Weak signal (${link.rssi} dBm): move closer to the router or add an extender."
        if (out.isEmpty()) out += "No significant interference detected on channel ${link.channel}."
        return out
    }

    /** Evil-twin / weak-security / hidden-network checks across everything in range. */
    fun threats(aps: List<ApDetail>): List<String> {
        val out = mutableListOf<String>()
        aps.filter { it.security == "Open" && it.ssid != "(hidden)" }.take(6).forEach { out += "Open network \"${it.ssid}\" (no encryption) at ${it.rssi} dBm." }
        aps.filter { it.security.startsWith("WEP") }.forEach { out += "\"${it.ssid}\" uses WEP: crackable in minutes." }
        aps.filter { it.security.startsWith("WPA (weak)") }.forEach { out += "\"${it.ssid}\" uses legacy WPA/TKIP." }
        aps.filter { it.wps && it.rssi > -80 }.take(5).forEach { out += "\"${it.ssid}\" has WPS enabled (PIN attacks possible)." }
        aps.groupBy { it.ssid }.filter { it.key != "(hidden)" && it.value.size > 1 }.forEach { (ssid, list) ->
            val secs = list.map { it.security }.distinct()
            if (secs.size > 1) out += "Possible evil twin: \"$ssid\" appears with different security (${secs.joinToString(" / ")}). Do not join the weaker one."
            val vendors = list.map { it.bssid.take(8) }.distinct()
            if (vendors.size > 2 && list.any { it.randomBssid }) out += "\"$ssid\" has access points with randomised MAC addresses: could be a phone hotspot copying the name."
        }
        val hidden = aps.count { it.ssid == "(hidden)" }
        if (hidden > 0) out += "$hidden hidden network(s) nearby."
        val cur = aps.firstOrNull { it.connected }
        if (cur != null && cur.security == "Open") out += "You are connected to an OPEN network. Anyone nearby can read unencrypted traffic: use a VPN."
        if (out.isEmpty()) out += "Nothing suspicious in range."
        return out
    }

    // ---------------------------------------------------------------- saved passwords

    private fun unesc(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Android 8+ WifiConfigStore.xml (needs root to read). */
    fun parseConfigStore(xml: String): List<SavedWifi> {
        val out = mutableListOf<SavedWifi>()
        Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val b = m.groupValues[1]
            val ssid = Regex("<string name=\"SSID\">(.*?)</string>").find(b)?.groupValues?.get(1)?.let { unesc(it).trim('"') } ?: return@forEach
            val psk = Regex("<string name=\"PreSharedKey\">(.*?)</string>").find(b)?.groupValues?.get(1)?.let { unesc(it).trim('"') }
            val key = Regex("<string name=\"ConfigKey\">(.*?)</string>").find(b)?.groupValues?.get(1).orEmpty()
            val sec = when { "SAE" in key -> "WPA3"; "WPA_PSK" in key -> "WPA/WPA2"; "WPA_EAP" in key -> "Enterprise"; "NONE" in key -> "Open"; else -> "?" }
            out += SavedWifi(ssid, psk, sec, "root: WifiConfigStore")
        }
        return out
    }

    /** Older wpa_supplicant.conf format. */
    fun parseWpaConf(txt: String): List<SavedWifi> =
        Regex("network=\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).findAll(txt).mapNotNull { m ->
            val b = m.groupValues[1]
            val ssid = Regex("ssid=\"(.*?)\"").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            val psk = Regex("psk=\"(.*?)\"").find(b)?.groupValues?.get(1)
            SavedWifi(ssid, psk, if (psk == null) "Open/other" else "WPA/WPA2", "root: wpa_supplicant")
        }.toList()

    /** Tries the common config-store locations through `su`. Returns empty when the phone is not rooted. */
    fun readSavedAsRoot(): List<SavedWifi> {
        val paths = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml", "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc/wifi/wpa_supplicant.conf", "/data/wifi/bcm_supp.conf"
        )
        for (p in paths) {
            try {
                val pr = ProcessBuilder("su", "-c", "cat $p").redirectErrorStream(true).start()
                val txt = pr.inputStream.bufferedReader().readText()
                pr.waitFor(4, TimeUnit.SECONDS)
                if (txt.contains("<Network>")) return parseConfigStore(txt)
                if (txt.contains("network={")) return parseWpaConf(txt)
            } catch (e: Exception) { }
        }
        return emptyList()
    }

    /** WIFI:T:WPA;S:name;P:password;; (the QR that Android's Share button creates). */
    fun parseWifiQr(text: String): SavedWifi? {
        if (!text.startsWith("WIFI:", true)) return null
        val body = text.substring(5)
        fun field(k: String): String? {
            val m = Regex("(?:^|;)$k:((?:\\\\.|[^;\\\\])*)").find(body) ?: return null
            return m.groupValues[1].replace(Regex("\\\\(.)"), "$1")
        }
        val ssid = field("S") ?: return null
        val t = field("T") ?: "nopass"
        val p = field("P")
        return SavedWifi(ssid, p, if (t.equals("nopass", true)) "Open" else t, "scanned QR")
    }

    @Suppress("DEPRECATION")
    fun configuredNetworks(ctx: Context): List<String> = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.configuredNetworks?.mapNotNull { it.SSID?.trim('"') } ?: emptyList()
    } catch (e: Exception) { emptyList() }
}

/** Offline router-maker lookup for scan results (no network inside the scan loop). */
object Oui {
    fun offline(bssid: String): String? = if (bssid.isBlank()) null else MacVendor.offlineLookup(bssid)
    fun isRandom(bssid: String): Boolean = MacVendor.isRandom(bssid)
}
