package com.toolkit.privacy

import android.content.Context
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.security.cert.X509Certificate

/** Extra network-toolbox tools. Each writes lines through [out]; all run on the caller's (IO) thread. */
object ToolboxPlus {
    val MODES = listOf("My IP", "HTTP", "TLS", "Subnet", "MAC", "Banner", "rDNS", "WoL", "Latency")

    fun run(ctx: Context, mode: String, host: String, extra: String, out: (String) -> Unit) {
        when (mode) {
            "My IP" -> myIp(out)
            "HTTP" -> httpHeaders(host, out)
            "TLS" -> tls(host, extra.toIntOrNull() ?: 443, out)
            "Subnet" -> subnet(host, out)
            "MAC" -> mac(host, out)
            "Banner" -> banner(host, extra.toIntOrNull() ?: 80, out)
            "rDNS" -> rdns(host, out)
            "WoL" -> wol(ctx, host, out)
            "Latency" -> latency(host, extra.toIntOrNull() ?: 443, out)
        }
    }

    fun myIp(out: (String) -> Unit) {
        out("Looking up your public address…")
        val j = Http.json("https://ipwho.is/")
        if (j != null && j.optBoolean("success", true)) {
            out("Public IP : ${j.optString("ip")}")
            out("Type      : ${j.optString("type")}")
            out("Location  : ${j.optString("city")}, ${j.optString("region")}, ${j.optString("country")}")
            val c = j.optJSONObject("connection")
            if (c != null) { out("ISP       : ${c.optString("isp")}"); out("Org       : ${c.optString("org")}"); out("ASN       : AS${c.optString("asn")}") }
            j.optJSONObject("timezone")?.let { out("Time zone : ${it.optString("id")} (UTC${it.optString("utc")})") }
            out("Coordinates: ${j.optDouble("latitude")}, ${j.optDouble("longitude")} (approximate, from your ISP)")
        } else {
            val t = Http.get("https://api.ipify.org?format=json")?.second
            out(if (t != null) "Public IP: " + (try { JSONObject(t).optString("ip") } catch (e: Exception) { t }) else "No internet connection.")
        }
        val v6 = Http.get("https://api64.ipify.org")?.second
        if (v6 != null && v6.contains(':')) out("IPv6      : $v6")
        val doh = Http.get("https://cloudflare-dns.com/cdn-cgi/trace")?.second
        doh?.lines()?.firstOrNull { it.startsWith("warp=") }?.let { out("Cloudflare WARP/VPN flag: ${it.substringAfter('=')}") }
        doh?.lines()?.firstOrNull { it.startsWith("loc=") }?.let { out("Country seen by Cloudflare: ${it.substringAfter('=')}") }
    }

    private val SEC_HEADERS = listOf(
        "Strict-Transport-Security" to "Forces HTTPS", "Content-Security-Policy" to "Blocks injected scripts", "X-Frame-Options" to "Stops click-jacking",
        "X-Content-Type-Options" to "Stops MIME sniffing", "Referrer-Policy" to "Limits referrer leaks", "Permissions-Policy" to "Limits camera/mic/location use",
        "Cross-Origin-Opener-Policy" to "Isolates the page"
    )

    fun httpHeaders(raw: String, out: (String) -> Unit) {
        var url = if (raw.startsWith("http")) raw else "https://$raw"
        var hops = 0
        var lastHeaders: Map<String, List<String>> = emptyMap()
        while (hops < 8) {
            val r = Http.headers(url) ?: run { out("✗ Could not connect to $url"); return }
            out("${hops + 1}. $url -> HTTP ${r.first}")
            lastHeaders = r.second
            val loc = r.second.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
            if (r.first in 300..399 && loc != null) { url = try { URL(URL(url), loc).toString() } catch (e: Exception) { loc }; hops++ } else break
        }
        out("")
        out("Response headers:")
        lastHeaders.forEach { (k, v) -> out("  $k: ${v.joinToString("; ").take(140)}") }
        out("")
        var score = 0
        SEC_HEADERS.forEach { (h, why) ->
            val has = lastHeaders.keys.any { it.equals(h, true) }
            if (has) score++
            out((if (has) "✓ " else "✗ ") + h + "  – " + why)
        }
        val server = lastHeaders.entries.firstOrNull { it.key.equals("Server", true) }?.value?.firstOrNull()
        if (server != null && Regex("\\d").containsMatchIn(server)) out("⚠ Server header reveals version: $server")
        if (lastHeaders.entries.any { it.key.equals("X-Powered-By", true) }) out("⚠ X-Powered-By reveals the technology stack")
        out("Security header grade: ${when { score >= 6 -> "A"; score >= 4 -> "B"; score >= 3 -> "C"; score >= 1 -> "D"; else -> "F" }} ($score/${SEC_HEADERS.size})")
    }

    fun tls(host: String, port: Int, out: (String) -> Unit) {
        try {
            val s = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            s.use {
                it.soTimeout = 6000
                it.connect(InetSocketAddress(host, port), 6000)
                it.startHandshake()
                out("Protocol : ${it.session.protocol}")
                out("Cipher   : ${it.session.cipherSuite}")
                val chain = it.session.peerCertificates.filterIsInstance<X509Certificate>()
                chain.forEachIndexed { i, c ->
                    out("")
                    out(if (i == 0) "Server certificate" else "Chain #$i")
                    out("  Subject : ${c.subjectX500Principal.name}")
                    out("  Issuer  : ${c.issuerX500Principal.name}")
                    out("  Valid   : ${c.notBefore} → ${c.notAfter}")
                    val days = (c.notAfter.time - System.currentTimeMillis()) / 86_400_000L
                    out("  Expires : " + if (days < 0) "EXPIRED ${-days} days ago ⚠" else "in $days days" + if (days < 14) " ⚠ soon" else "")
                    out("  Key     : ${c.publicKey.algorithm}, sig ${c.sigAlgName}")
                    if (i == 0) c.subjectAlternativeNames?.let { sans -> out("  Names   : " + sans.mapNotNull { n -> n.getOrNull(1)?.toString() }.take(12).joinToString(", ")) }
                }
                if (it.session.protocol.contains("1.0") || it.session.protocol.contains("1.1")) out("⚠ Outdated TLS version")
            }
        } catch (e: Exception) { out("✗ TLS failed: ${e.message}") }
    }

    fun subnet(input: String, out: (String) -> Unit) {
        val parts = input.trim().split('/')
        val ip = parts[0]
        val p = parts.getOrNull(1)?.toIntOrNull() ?: 24
        if (!Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").matches(ip) || p !in 0..32) { out("Use CIDR like 192.168.1.10/24"); return }
        val v = NetIntel.ipToInt(ip)
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val net = v and mask
        val bc = net or mask.inv()
        val hosts = if (p >= 31) (1L shl (32 - p)) else (1L shl (32 - p)) - 2
        out("Address   : $ip/$p")
        out("Netmask   : ${NetIntel.intToIp(mask)}")
        out("Wildcard  : ${NetIntel.intToIp(mask.inv())}")
        out("Network   : ${NetIntel.intToIp(net)}")
        out("Broadcast : ${NetIntel.intToIp(bc)}")
        out("Hosts     : $hosts" + if (p < 31) "  (${NetIntel.intToIp(net + 1)} – ${NetIntel.intToIp(bc - 1)})" else "")
        out("Binary    : " + Integer.toBinaryString(v).padStart(32, '0').chunked(8).joinToString("."))
        val c = when { ip.startsWith("10.") || ip.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip) -> "Private (RFC1918)"; ip.startsWith("127.") -> "Loopback"; ip.startsWith("169.254.") -> "Link-local"; ip.startsWith("100.") && (ip.split('.')[1].toInt() in 64..127) -> "Carrier-grade NAT"; else -> "Public" }
        out("Class     : $c")
    }

    fun mac(input: String, out: (String) -> Unit) {
        val m = MacVendor.normalize(input.trim())
        if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(m)) { out("Enter a MAC like AA:BB:CC:DD:EE:FF"); return }
        out("MAC: $m")
        if (MacVendor.isRandom(m)) out("This is a locally administered / randomised address (privacy MAC), no maker can be looked up.")
        else {
            val v = MacVendor.lookup(m)
            out("Maker: ${v ?: "not found"}")
        }
        val first = m.take(2).toInt(16)
        out("Type : " + if (first and 1 == 1) "multicast" else "unicast")
    }

    fun banner(host: String, port: Int, out: (String) -> Unit) {
        out("Connecting to $host:$port …")
        val b = NetIntel.bannerGrab(host, port)
        out(b?.let { "Banner: $it" } ?: "No banner returned (service may wait for a request, or the port is closed).")
        val h = NetIntel.httpProbe(host, port, port == 443 || port == 8443)
        if (h != null) { out("HTTP ${h.status}  server=${h.server ?: "?"}  title=${h.title ?: "-"}" + if (h.login) "  (login required)" else ""); h.redirect?.let { out("Redirects to $it") } }
        PortDb.info(port).let { out("Service: ${it.service} - ${it.note}") }
    }

    fun rdns(host: String, out: (String) -> Unit) {
        try {
            val all = InetAddress.getAllByName(host)
            all.forEach { a -> out("${a.hostAddress}  ->  ${try { a.canonicalHostName } catch (e: Exception) { "?" }}") }
            all.firstOrNull { it.address.size == 4 }?.hostAddress?.let { ip ->
                val ptr = ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"
                val d = LookupNet.doh(ptr, "PTR")
                out("PTR (DNS-over-HTTPS): " + (d?.answers?.joinToString()?.ifBlank { "none" } ?: "lookup failed"))
            }
        } catch (e: Exception) { out("✗ ${e.message}") }
    }

    fun wol(ctx: Context, mac: String, out: (String) -> Unit) {
        val net = NetIntel.localInfo(ctx)
        val bc = net?.let { it.base + ".255" } ?: "255.255.255.255"
        val ok = NetIntel.wakeOnLan(MacVendor.normalize(mac.trim()), bc)
        out(if (ok) "Magic packet sent to $mac via $bc. The PC must have Wake-on-LAN enabled in BIOS/OS." else "✗ Invalid MAC or network error.")
    }

    fun latency(host: String, port: Int, out: (String) -> Unit) {
        out("TCP latency to $host:$port (20 samples)")
        val t = ArrayList<Double>(); var lost = 0
        repeat(20) {
            val t0 = System.nanoTime()
            val ok = try { Socket().use { s -> s.connect(InetSocketAddress(host, port), 2000) }; true } catch (e: Exception) { false }
            if (ok) { val ms = (System.nanoTime() - t0) / 1e6; t += ms; out("%2d  %.1f ms".format(it + 1, ms)) } else { lost++; out("%2d  timeout".format(it + 1)) }
            Thread.sleep(150)
        }
        if (t.isNotEmpty()) {
            val avg = t.average()
            val jitter = t.zipWithNext { a, b -> Math.abs(a - b) }.let { if (it.isEmpty()) 0.0 else it.average() }
            out("")
            out("min %.1f / avg %.1f / max %.1f ms   jitter %.1f ms   loss %d%%".format(t.min(), avg, t.max(), jitter, lost * 100 / 20))
            out(when { lost > 2 -> "Verdict: unstable connection"; avg < 40 -> "Verdict: excellent (gaming/calls fine)"; avg < 100 -> "Verdict: good"; avg < 250 -> "Verdict: slow"; else -> "Verdict: very slow" })
        }
    }

    /** Summarises ping output lines: used by the original Ping tool. */
    fun pingSummary(lines: List<String>): String? {
        val ts = lines.mapNotNull { Regex("time=([\\d.]+) ?ms").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        if (ts.isEmpty()) return null
        val jitter = ts.zipWithNext { a, b -> Math.abs(a - b) }.let { if (it.isEmpty()) 0.0 else it.average() }
        return "Summary: min %.1f / avg %.1f / max %.1f ms · jitter %.1f ms · %d replies".format(ts.min(), ts.average(), ts.max(), jitter, ts.size)
    }
}
