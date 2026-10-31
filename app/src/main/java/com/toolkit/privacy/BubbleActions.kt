package com.toolkit.privacy

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** What the floating bubble's panel can do. Each function is blocking and returns text to show in the panel. */
object BubbleActions {
    class Action(val id: String, val icon: String, val label: String)

    val all = listOf(
        Action("rescan", "🔄", "Re-scan site"),
        Action("clip_link", "📋", "Scan copied link"),
        Action("clip_msg", "💬", "Check copied message"),
        Action("clip_secret", "🔑", "Find secrets in copy"),
        Action("net", "📶", "Network info"),
        Action("ip", "🌍", "Public IP"),
        Action("ping", "🏓", "Ping test"),
        Action("speed", "⚡", "Quick speed"),
        Action("lan", "🕸", "Scan LAN"),
        Action("torch", "🔦", "Flashlight"),
        Action("battery", "🔋", "Battery & storage"),
        Action("park", "🅿", "Save parking spot"),
        Action("qr", "▦", "Open QR scanner"),
        Action("monitor", "🔔", "Device monitor"),
        Action("copy_url", "🔗", "Copy site URL"),
        Action("share_url", "📤", "Share site"),
        Action("open_url", "🌐", "Open site"),
        Action("reports", "🔎", "Scam reports"),
        Action("whois", "🧾", "WHOIS"),
        Action("archive", "🕰", "Web archive"),
        Action("vt", "🦠", "VirusTotal"),
        Action("intel", "🔬", "Full intel"),
        Action("contact", "📞", "Number / email"),
        Action("scamtext", "🧹", "Scam Check"),
        Action("wifipro", "📡", "Wi-Fi Pro"),
        Action("lens", "📷", "Lens"),
        Action("app", "🧰", "Open app"),
        Action("hide", "✖", "Hide bubble")
    )

    private var torchOn = false

    fun network(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return "No internet connection."
        val sb = StringBuilder()
        val kind = when { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"; caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"; else -> "Other" }
        sb.appendLine("Connection: $kind")
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.appendLine("VPN: active")
        sb.appendLine("Down/up estimate: ${caps.linkDownstreamBandwidthKbps / 1000} / ${caps.linkUpstreamBandwidthKbps / 1000} Mbps")
        NetIntel.localInfo(ctx)?.let { sb.appendLine("IP ${it.ip}  router ${it.gateway ?: "?"}"); it.ssid?.let { s -> sb.appendLine("Wi-Fi: $s") } }
        sb.appendLine("Metered: ${if (cm.isActiveNetworkMetered) "yes" else "no"}")
        val t0 = System.nanoTime()
        val dns = try { InetAddress.getByName("www.google.com"); (System.nanoTime() - t0) / 1_000_000 } catch (e: Exception) { -1L }
        sb.append("DNS lookup: ").append(if (dns >= 0) "$dns ms" else "failed")
        return sb.toString()
    }

    fun publicIp(): String {
        val j = Http.json("https://ipwho.is/") ?: return "Could not reach the internet."
        val c = j.optJSONObject("connection")
        return "IP ${j.optString("ip")}\n${j.optString("city")}, ${j.optString("country")}\nISP: ${c?.optString("isp") ?: "?"}"
    }

    fun ping(): String {
        val targets = listOf("1.1.1.1" to 443, "8.8.8.8" to 443, "www.google.com" to 443)
        return targets.joinToString("\n") { (h, p) ->
            val t = ArrayList<Long>()
            repeat(4) {
                val t0 = System.nanoTime()
                try { Socket().use { s -> s.connect(InetSocketAddress(h, p), 2000) }; t += (System.nanoTime() - t0) / 1_000_000 } catch (e: Exception) { }
            }
            "$h: " + if (t.isEmpty()) "no answer" else "avg ${t.average().toInt()} ms (best ${t.min()}, loss ${(4 - t.size) * 25}%)"
        }
    }

    fun speed(): String {
        return try {
            val c = URL("https://speed.cloudflare.com/__down?bytes=10000000").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            val t0 = System.nanoTime(); var n = 0L
            c.inputStream.use { s -> val buf = ByteArray(32768); while (System.nanoTime() - t0 < 6_000_000_000L) { val r = s.read(buf); if (r < 0) break; n += r } }
            val sec = (System.nanoTime() - t0) / 1e9
            "Download ≈ %.1f Mbps (%.1f MB in %.1fs)".format(n * 8 / sec / 1e6, n / 1e6, sec)
        } catch (e: Exception) { "Speed test failed: ${e.message}" }
    }

    fun battery(ctx: Context): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plug = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes; val total = st.totalBytes
        return "Battery ${lvl * 100 / scale}%${if (plug) " (charging)" else ""} · ${"%.1f".format(temp)} °C" +
            (if (temp >= 42) "  ⚠ hot" else "") + "\nStorage free ${fmtSize(free)} of ${fmtSize(total)}"
    }

    fun torch(ctx: Context): String = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id == null) "No flashlight on this phone" else { torchOn = !torchOn; cm.setTorchMode(id, torchOn); if (torchOn) "Flashlight ON" else "Flashlight off" }
    } catch (e: Exception) { torchOn = false; "Flashlight unavailable: ${e.message}" }

    fun scamSummary(text: String): String {
        val v = ScamRules.analyze(text)
        val why = v.findings.filter { it.delta < 0 }.sortedBy { it.delta }.take(3).joinToString("\n") { "• ${it.text}" }
        return "Message safety ${v.score}%: ${v.label}" + if (why.isNotEmpty()) "\n$why" else "\nNo scam patterns found."
    }

    fun secretSummary(text: String): String {
        val r = SensitiveScan.scan(text)
        if (r.hits.isEmpty()) return "Nothing sensitive found in the copied text."
        val top = r.hits.sortedByDescending { it.severity }.take(4).joinToString("\n") { "• ${it.label}: ${it.masked()}" }
        return "${r.level} (${r.hits.size} items)\n$top\nOpen the app → Tools → Sensitive to mask them."
    }

    /** Web lookups for a checked site; [kind] is reports | whois | archive | vt. */
    fun siteWebUrl(kind: String, url: String): String {
        val host = try { java.net.URI(if (url.contains("://")) url else "https://$url").host ?: url } catch (e: Exception) { url }
        val enc = java.net.URLEncoder.encode("\"$host\" scam OR reviews OR fraud", "UTF-8")
        return when (kind) {
            "reports" -> "https://www.google.com/search?q=$enc"
            "whois" -> "https://who.is/whois/$host"
            "archive" -> "https://web.archive.org/web/*/$host"
            else -> "https://www.virustotal.com/gui/domain/$host"
        }
    }

    /** Quick on-device intel summary of the checked site (DNS, TLS, headers, archive age). Blocking. */
    fun intelSummary(url: String): String {
        val host = Intel.hostOf(url)
        if (host.isBlank()) return "No site checked yet."
        val sb = StringBuilder("Intel for $host\n")
        fun add(title: String, l: Lines) { sb.appendLine("— $title"); l.take(6).forEach { sb.appendLine("  ${it.first}: ${it.second}") } }
        try { add("Resolve", Intel.resolve(host)) } catch (e: Exception) { }
        try { add("TLS", Intel.tls(host)) } catch (e: Exception) { }
        try { val h = Intel.securityHeaders(host); sb.appendLine("— Security headers: grade ${h.grade} (${h.score}/100)") } catch (e: Exception) { }
        try { add("Archive", Intel.archiveAge(host)) } catch (e: Exception) { }
        return sb.toString().trim()
    }
}
