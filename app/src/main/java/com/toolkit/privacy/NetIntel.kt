package com.toolkit.privacy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ---------------------------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------------------------

data class PortInfo(val port: Int, val service: String, val risk: Int, val note: String, val banner: String? = null)

data class LanDevice(
    val ip: String,
    val me: Boolean = false,
    val gateway: Boolean = false,
    val mac: String? = null,
    val vendor: String? = null,
    val randomMac: Boolean = false,
    val hostname: String? = null,
    val netbios: String? = null,
    val upnp: String? = null,
    val pingMs: Long = -1,
    val ports: List<PortInfo> = emptyList(),
    val services: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val httpTitle: String? = null,
    val httpServer: String? = null,
    val tls: String? = null,
    val risk: Int = 0,
    val warnings: List<String> = emptyList(),
    val advice: List<String> = emptyList(),
    val osGuess: String? = null,
    val isNew: Boolean = false,
    val nickname: String? = null
) {
    val title: String get() = nickname ?: upnp ?: hostname ?: netbios ?: vendor?.let { "$it device" } ?: ip
    val riskLabel: String get() = when { risk >= 60 -> "High risk"; risk >= 30 -> "Medium risk"; risk >= 10 -> "Low risk"; else -> "Looks clean" }
    fun key(): String = mac ?: hostname ?: netbios ?: upnp ?: ip
}

class LocalInfo(val ip: String, val prefix: Int, val gateway: String?, val dns: List<String>, val ssid: String?, val iface: String?) {
    val base: String get() = ip.substringBeforeLast('.')
    fun hostIps(): List<String> {
        val ipInt = NetIntel.ipToInt(ip)
        val p = if (prefix < 22) 24 else prefix.coerceAtMost(30)
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val net = ipInt and mask
        val count = (1 shl (32 - p)) - 2
        return (1..count).map { NetIntel.intToIp(net + it) }
    }
}

enum class ScanDepth(val label: String) { QUICK("Quick"), STANDARD("Standard"), DEEP("Deep"), FULL("Full (all 65535)") }

// ---------------------------------------------------------------------------------------------
// Port knowledge
// ---------------------------------------------------------------------------------------------

object PortDb {
    /** risk: 0 = normal, 1 = worth knowing, 2 = risky if exposed, 3 = dangerous / plaintext admin */
    private val rows: List<Triple<Int, String, Pair<Int, String>>> = listOf(
        Triple(7, "Echo", 1 to "Old test service, rarely needed."),
        Triple(9, "Discard / WoL", 0 to "Wake-on-LAN listens here."),
        Triple(20, "FTP data", 2 to "File transfer, plaintext."),
        Triple(21, "FTP", 3 to "Unencrypted file transfer. Passwords cross the network in clear text."),
        Triple(22, "SSH", 1 to "Encrypted remote shell. Fine if you use keys / strong passwords."),
        Triple(23, "Telnet", 3 to "Unencrypted remote login. Disable it, use SSH."),
        Triple(25, "SMTP", 2 to "Mail relay. Should not be open on a home device."),
        Triple(37, "Time", 1 to "Legacy time service."),
        Triple(53, "DNS", 1 to "Name server. Normal on routers."),
        Triple(67, "DHCP", 0 to "Hands out IP addresses (router)."),
        Triple(69, "TFTP", 3 to "Trivial FTP: no authentication at all."),
        Triple(79, "Finger", 2 to "Leaks user info, obsolete."),
        Triple(80, "HTTP", 1 to "Web page / admin panel without encryption."),
        Triple(81, "HTTP-alt", 1 to "Alternate web interface."),
        Triple(88, "Kerberos", 1 to "Windows domain authentication."),
        Triple(110, "POP3", 2 to "Plaintext mail download."),
        Triple(111, "RPCbind", 2 to "Sun RPC portmapper, often abused."),
        Triple(119, "NNTP", 1 to "Usenet."),
        Triple(123, "NTP", 0 to "Time sync."),
        Triple(135, "MS-RPC", 2 to "Windows RPC. Do not expose."),
        Triple(137, "NetBIOS-NS", 1 to "Windows name service."),
        Triple(139, "NetBIOS", 2 to "Windows file sharing (legacy)."),
        Triple(143, "IMAP", 2 to "Plaintext mail access."),
        Triple(161, "SNMP", 2 to "Network management. Default 'public' community leaks device info."),
        Triple(179, "BGP", 1 to "Routing protocol."),
        Triple(389, "LDAP", 2 to "Directory service, plaintext."),
        Triple(443, "HTTPS", 0 to "Encrypted web interface."),
        Triple(445, "SMB", 2 to "Windows/Samba file sharing. Keep patched, never expose to the internet."),
        Triple(465, "SMTPS", 1 to "Encrypted mail submission."),
        Triple(500, "IKE", 1 to "VPN key exchange."),
        Triple(515, "LPD", 2 to "Old printer protocol."),
        Triple(548, "AFP", 1 to "Apple file sharing."),
        Triple(554, "RTSP", 2 to "Camera / media stream. Often has weak or default passwords."),
        Triple(587, "SMTP-sub", 1 to "Mail submission."),
        Triple(631, "IPP", 1 to "Network printing (CUPS)."),
        Triple(636, "LDAPS", 0 to "Encrypted directory."),
        Triple(873, "rsync", 2 to "File sync; may allow anonymous read."),
        Triple(993, "IMAPS", 0 to "Encrypted mail."),
        Triple(995, "POP3S", 0 to "Encrypted mail."),
        Triple(1080, "SOCKS", 2 to "Proxy. Open proxies get abused."),
        Triple(1433, "MS-SQL", 3 to "Database exposed on the network."),
        Triple(1521, "Oracle", 3 to "Database exposed on the network."),
        Triple(1723, "PPTP", 2 to "Old VPN, weak crypto."),
        Triple(1883, "MQTT", 2 to "IoT message broker, often with no auth."),
        Triple(1900, "UPnP", 2 to "Lets devices open router ports automatically."),
        Triple(2049, "NFS", 2 to "Network file system."),
        Triple(2375, "Docker API", 3 to "Unauthenticated Docker control = full takeover."),
        Triple(3000, "Dev server", 1 to "Node/Grafana style dev service."),
        Triple(3306, "MySQL", 3 to "Database exposed on the network."),
        Triple(3389, "RDP", 3 to "Windows Remote Desktop. Top target for attackers."),
        Triple(3689, "DAAP", 0 to "iTunes / Music sharing."),
        Triple(4444, "Backdoor?", 3 to "Commonly used by Metasploit / backdoors."),
        Triple(5000, "UPnP/Flask", 1 to "Synology / dev server / UPnP."),
        Triple(5001, "Synology HTTPS", 1 to "NAS web interface."),
        Triple(5060, "SIP", 1 to "VoIP signalling."),
        Triple(5353, "mDNS", 0 to "Bonjour / zero-conf discovery."),
        Triple(5432, "PostgreSQL", 3 to "Database exposed on the network."),
        Triple(5555, "ADB", 3 to "Android Debug Bridge over network = full device control."),
        Triple(5601, "Kibana", 2 to "Analytics dashboard."),
        Triple(5900, "VNC", 3 to "Remote screen control, often weak passwords."),
        Triple(5985, "WinRM", 2 to "Windows remote management."),
        Triple(6379, "Redis", 3 to "In-memory DB usually without password."),
        Triple(6666, "IRC/Backdoor?", 2 to "Often unwanted."),
        Triple(7000, "AirPlay", 1 to "Apple AirPlay receiver."),
        Triple(7547, "TR-069", 2 to "ISP remote management of routers."),
        Triple(8000, "HTTP-alt", 1 to "Alternate web server."),
        Triple(8008, "Cast HTTP", 0 to "Google Cast device."),
        Triple(8009, "Cast", 0 to "Google Cast control."),
        Triple(8080, "HTTP-alt", 1 to "Alternate web interface / proxy."),
        Triple(8081, "HTTP-alt", 1 to "Alternate web interface."),
        Triple(8086, "InfluxDB", 2 to "Time-series DB."),
        Triple(8088, "HTTP-alt", 1 to "Alternate web interface."),
        Triple(8123, "Home Assistant", 1 to "Smart-home hub."),
        Triple(8200, "DLNA", 1 to "Media server."),
        Triple(8291, "MikroTik Winbox", 2 to "Router admin port."),
        Triple(8443, "HTTPS-alt", 0 to "Alternate encrypted web interface."),
        Triple(8554, "RTSP-alt", 2 to "Camera stream."),
        Triple(8728, "MikroTik API", 2 to "Router admin API."),
        Triple(8888, "HTTP-alt", 1 to "Jupyter / alt web."),
        Triple(9000, "HTTP-alt", 1 to "Portainer / SonarQube / PHP-FPM."),
        Triple(9090, "HTTP-alt", 1 to "Prometheus / admin panel."),
        Triple(9100, "JetDirect", 1 to "Raw printing: anyone on the LAN can print."),
        Triple(9200, "Elasticsearch", 3 to "Search DB, often unauthenticated."),
        Triple(10000, "Webmin", 2 to "Server admin panel."),
        Triple(11211, "Memcached", 3 to "Cache server with no authentication."),
        Triple(27017, "MongoDB", 3 to "Database exposed on the network."),
        Triple(32400, "Plex", 1 to "Media server."),
        Triple(37777, "Dahua DVR", 2 to "Camera recorder."),
        Triple(49152, "UPnP", 1 to "UPnP device description."),
        Triple(62078, "iOS lockdown", 0 to "iPhone / iPad sync service."),
        Triple(34567, "DVR", 2 to "Chinese DVR / IP camera service.")
    )
    private val map: Map<Int, PortInfo> = rows.associate { it.first to PortInfo(it.first, it.second, it.third.first, it.third.second) }

    /** Extra service names for ports without a risk row (kept so no scan result is ever just "unknown"). */
    private val extraNames: Map<Int, String> = mapOf(20 to "FTP-data", 21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 67 to "DHCP", 69 to "TFTP", 80 to "HTTP", 81 to "HTTP-alt", 82 to "HTTP-alt", 88 to "Kerberos", 110 to "POP3", 111 to "RPC", 123 to "NTP", 135 to "MS-RPC", 137 to "NetBIOS-NS", 138 to "NetBIOS-DG", 139 to "NetBIOS", 143 to "IMAP", 161 to "SNMP", 389 to "LDAP", 443 to "HTTPS", 445 to "SMB", 465 to "SMTPS", 514 to "Syslog", 515 to "LPD printer", 548 to "AFP", 554 to "RTSP camera", 587 to "SMTP-submit", 631 to "IPP printer", 636 to "LDAPS", 873 to "rsync", 993 to "IMAPS", 995 to "POP3S", 1080 to "SOCKS proxy", 1433 to "MS-SQL", 1521 to "Oracle", 1723 to "PPTP VPN", 1883 to "MQTT", 1900 to "UPnP", 1935 to "RTMP", 2049 to "NFS", 2375 to "Docker API", 3000 to "Dev server", 3260 to "iSCSI", 3306 to "MySQL", 3389 to "RDP", 3689 to "DAAP", 4443 to "HTTPS-alt", 5000 to "UPnP/NAS web", 5001 to "NAS web (TLS)", 5038 to "Asterisk", 5060 to "SIP", 5353 to "mDNS", 5357 to "WSD", 5432 to "PostgreSQL", 5555 to "ADB", 5601 to "Kibana", 5672 to "AMQP", 5900 to "VNC", 5985 to "WinRM", 6379 to "Redis", 6443 to "Kubernetes API", 7000 to "AirPlay", 7547 to "TR-069 (ISP remote mgmt)", 8000 to "HTTP-alt", 8008 to "Chromecast", 8009 to "Chromecast", 8080 to "HTTP-proxy/alt", 8081 to "HTTP-alt", 8291 to "MikroTik Winbox", 8443 to "HTTPS-alt", 8554 to "RTSP-alt", 8728 to "MikroTik API", 8883 to "MQTT-TLS", 8888 to "HTTP-alt", 9000 to "Web/Portainer", 9090 to "Web admin", 9100 to "JetDirect printer", 9200 to "Elasticsearch", 10000 to "Webmin", 27017 to "MongoDB", 32400 to "Plex", 34567 to "DVR (XMeye)", 37777 to "Dahua DVR", 49152 to "UPnP", 62078 to "iOS sync")
    fun info(port: Int): PortInfo = map[port] ?: extraNames[port]?.let { PortInfo(port, it, 0, "Recognised service name only.") } ?: PortInfo(port, "unknown", 0, "Unrecognised service.")
    fun name(port: Int): String = map[port]?.service ?: extraNames[port] ?: "tcp/$port"
    fun full(): List<Int> = (1..65535).toList()
    /** Parses "22,80,8000-8100" into a sorted, de-duplicated list of valid ports. */
    fun parse(spec: String): List<Int> = NetMath.parsePorts(spec)

    val quick = listOf(21, 22, 23, 53, 80, 139, 443, 445, 554, 631, 1883, 3389, 5555, 5900, 8080, 8443, 9100, 62078)
    val standard: List<Int> = rows.map { it.first }.filter { it != 5353 && it != 67 && it != 123 && it != 137 && it != 161 && it != 500 && it != 1900 }
    fun deep(): List<Int> = ((1..1024) + standard + listOf(1025, 1026, 2000, 2222, 4443, 5037, 5800, 6000, 7070, 8889, 49153, 50000, 55443)).distinct()
}

// ---------------------------------------------------------------------------------------------
// MAC vendors (offline hints + online lookup)
// ---------------------------------------------------------------------------------------------

object MacVendor {
    private val offline = mapOf(
        "B8:27:EB" to "Raspberry Pi", "DC:A6:32" to "Raspberry Pi", "E4:5F:01" to "Raspberry Pi", "D8:3A:DD" to "Raspberry Pi",
        "24:0A:C4" to "Espressif (ESP32/ESP8266)", "30:AE:A4" to "Espressif (ESP32/ESP8266)", "3C:71:BF" to "Espressif (ESP32/ESP8266)",
        "00:0C:29" to "VMware", "00:50:56" to "VMware", "08:00:27" to "VirtualBox", "52:54:00" to "QEMU/KVM"
    )

    fun isRandom(mac: String): Boolean {
        val first = mac.take(2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }

    fun normalize(mac: String): String = mac.uppercase().replace('-', ':')

    fun offlineLookup(mac: String): String? = offline[normalize(mac).take(8)]

    fun lookup(mac: String): String? {
        val m = normalize(mac)
        if (isRandom(m)) return null
        offlineLookup(m)?.let { return it }
        val r = Http.get("https://api.macvendors.com/" + m.take(8), 4000) ?: return null
        return if (r.first == 200 && r.second.isNotBlank() && r.second.length < 80) r.second.trim() else null
    }
}

// ---------------------------------------------------------------------------------------------
// Parsers (pure, unit-tested on the JVM)
// ---------------------------------------------------------------------------------------------

class DnsRec(val name: String, val type: Int, val text: String)

object DnsWire {
    fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var p = start
        var next = -1
        var hops = 0
        while (p < b.size) {
            val len = b[p].toInt() and 0xFF
            if (len == 0) { p++; break }
            if (len and 0xC0 == 0xC0) {
                if (p + 1 >= b.size) break
                val ptr = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                if (next < 0) next = p + 2
                p = ptr
                if (++hops > 20) break
                continue
            }
            if (p + 1 + len > b.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, p + 1, len, Charsets.UTF_8))
            p += 1 + len
        }
        return sb.toString() to (if (next >= 0) next else p)
    }

    fun query(names: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, (names.size shr 8).toByte(), names.size.toByte(), 0, 0, 0, 0, 0, 0))
        for (n in names) {
            n.split('.').filter { it.isNotEmpty() }.forEach { l -> val bs = l.toByteArray(); out.write(bs.size); out.write(bs) }
            out.write(0)
            out.write(byteArrayOf(0, 12, 0x80.toByte(), 1)) // PTR, IN + unicast-response bit
        }
        return out.toByteArray()
    }

    fun parse(b: ByteArray, len: Int = b.size): List<DnsRec> {
        val out = ArrayList<DnsRec>()
        if (len < 12) return out
        fun u16(p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        val qd = u16(4); val an = u16(6) + u16(8) + u16(10)
        var p = 12
        try {
            repeat(qd) { p = readName(b, p).second + 4 }
            repeat(an) {
                val (name, np) = readName(b, p)
                if (np + 10 > len) return out
                val type = u16(np); val rdlen = u16(np + 8)
                val d = np + 10
                if (d + rdlen > len) return out
                val text = when (type) {
                    12 -> readName(b, d).first
                    1 -> if (rdlen == 4) "${b[d].toInt() and 0xFF}.${b[d + 1].toInt() and 0xFF}.${b[d + 2].toInt() and 0xFF}.${b[d + 3].toInt() and 0xFF}" else ""
                    33 -> "${u16(d + 4)} ${readName(b, d + 6).first}"
                    16 -> {
                        val parts = ArrayList<String>(); var q = d
                        while (q < d + rdlen) { val l = b[q].toInt() and 0xFF; if (q + 1 + l > d + rdlen) break; parts += String(b, q + 1, l, Charsets.UTF_8); q += 1 + l }
                        parts.joinToString("|")
                    }
                    else -> ""
                }
                out += DnsRec(name, type, text)
                p = d + rdlen
            }
        } catch (e: Exception) { }
        return out
    }

    private val FRIENDLY = mapOf(
        "_googlecast" to "Google Cast", "_airplay" to "AirPlay", "_raop" to "AirPlay audio", "_ipp" to "Printer (IPP)", "_ipps" to "Printer (IPP)",
        "_printer" to "Printer", "_pdl-datastream" to "Printer", "_smb" to "File sharing (SMB)", "_afpovertcp" to "Apple file sharing",
        "_ssh" to "SSH", "_sftp-ssh" to "SFTP", "_http" to "Web server", "_https" to "Secure web server", "_homekit" to "HomeKit", "_hap" to "HomeKit accessory",
        "_spotify-connect" to "Spotify Connect", "_companion-link" to "Apple device", "_workstation" to "Linux workstation", "_adb" to "Android debug",
        "_adb-tls-connect" to "Android wireless debug", "_scanner" to "Scanner", "_daap" to "iTunes sharing", "_nfs" to "NFS share", "_rfb" to "VNC screen share",
        "_device-info" to "Device info", "_androidtvremote2" to "Android TV remote", "_matter" to "Matter smart home", "_meshcop" to "Thread border router",
        "_sleep-proxy" to "Apple sleep proxy", "_mediaremotetv" to "Apple TV", "_xbmc-jsonrpc" to "Kodi", "_plexmediasvr" to "Plex"
    )

    fun friendlyType(full: String): String {
        val t = full.substringBefore("._tcp").substringBefore("._udp").substringAfterLast('.')
        return FRIENDLY[t] ?: t.removePrefix("_")
    }
}

object NetBios {
    val REQUEST: ByteArray = byteArrayOf(
        0x13, 0x37, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x20,
        0x43, 0x4B, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
        0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0, 0, 0x21, 0, 1
    )

    /** Returns (primary name, mac) from a node-status reply. */
    fun parse(b: ByteArray, len: Int): Pair<String?, String?> {
        if (len < 57) return null to null
        val n = b[56].toInt() and 0xFF
        var p = 57
        var name: String? = null
        for (i in 0 until n) {
            if (p + 18 > len) break
            val nm = String(b, p, 15, Charsets.US_ASCII).trim()
            val suffix = b[p + 15].toInt() and 0xFF
            val flags = ((b[p + 16].toInt() and 0xFF) shl 8) or (b[p + 17].toInt() and 0xFF)
            val group = flags and 0x8000 != 0
            if (name == null && !group && suffix == 0x00 && nm.isNotEmpty()) name = nm
            p += 18
        }
        val mac = if (p + 6 <= len) (0 until 6).joinToString(":") { "%02X".format(b[p + it].toInt() and 0xFF) } else null
        return name to mac?.takeIf { it != "00:00:00:00:00:00" }
    }
}

object SsdpParse {
    fun headers(resp: String): Map<String, String> =
        resp.lines().drop(1).mapNotNull { l -> val i = l.indexOf(':'); if (i > 0) l.substring(0, i).trim().uppercase() to l.substring(i + 1).trim() else null }.toMap()

    fun xmlField(xml: String, tag: String): String? =
        Regex("<$tag>([^<]{1,120})</$tag>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun describe(xml: String): String? {
        val fn = xmlField(xml, "friendlyName"); val mf = xmlField(xml, "manufacturer"); val md = xmlField(xml, "modelName")
        val parts = listOfNotNull(fn, listOfNotNull(mf, md).joinToString(" ").takeIf { it.isNotBlank() && it != fn })
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }
    }
}

object Arp {
    /** /proc/net/arp lines -> ip to mac. Android 11+ usually blocks this; that's fine, other methods fill the gap. */
    fun parse(text: String): Map<String, String> = text.lines().drop(1).mapNotNull { l ->
        val p = l.trim().split(Regex("\\s+"))
        if (p.size >= 4 && p[3] != "00:00:00:00:00:00" && p[3].contains(':')) p[0] to p[3].uppercase() else null
    }.toMap()

    fun read(): Map<String, String> {
        val out = HashMap<String, String>()
        try { val f = File("/proc/net/arp"); if (f.canRead()) out += parse(f.readText()) } catch (e: Exception) { }
        if (out.isEmpty()) try {
            val p = ProcessBuilder("ip", "neigh").redirectErrorStream(true).start()
            val txt = p.inputStream.bufferedReader().readText(); p.waitFor()
            Regex("(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+dev\\s+\\S+\\s+lladdr\\s+([0-9a-fA-F:]{17})").findAll(txt).forEach { out[it.groupValues[1]] = it.groupValues[2].uppercase() }
        } catch (e: Exception) { }
        return out
    }
}

// ---------------------------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------------------------

object NetIntel {
    fun ipToInt(ip: String): Int = ip.split('.').fold(0) { a, s -> (a shl 8) or (s.toInt() and 0xFF) }
    fun intToIp(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    fun localInfo(ctx: Context): LocalInfo? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val net = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        val ip = addr.address.hostAddress ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val dns = lp.dnsServers.mapNotNull { it.hostAddress }
        val ssid = try {
            @Suppress("DEPRECATION")
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" && it.isNotBlank() }
        } catch (e: Exception) { null }
        return LocalInfo(ip, addr.prefixLength, gw, dns, ssid, lp.interfaceName)
    }

    /** 1 = open, 0 = refused (host up), -1 = no answer */
    fun probe(ip: String, port: Int, timeout: Int): Int = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }
        1
    } catch (e: ConnectException) {
        if (e.message?.contains("ECONNREFUSED") == true) 0 else -1
    } catch (e: Exception) { -1 }

    private val trustAll: Array<TrustManager> = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    })

    private fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }

    fun bannerGrab(ip: String, port: Int): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), 700); s.soTimeout = 800
            if (port in listOf(80, 8080, 8000, 8008, 8081, 8088, 81)) s.getOutputStream().write("HEAD / HTTP/1.0\r\n\r\n".toByteArray())
            val buf = ByteArray(160)
            val n = try { s.getInputStream().read(buf) } catch (e: SocketTimeoutException) { -1 }
            if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1).replace(Regex("[^\\x20-\\x7E]"), " ").trim().take(110) else null
        }
    } catch (e: Exception) { null }

    class HttpProbe(val title: String?, val server: String?, val status: Int, val login: Boolean, val redirect: String?)

    fun httpProbe(ip: String, port: Int, tls: Boolean): HttpProbe? = try {
        val url = URL((if (tls) "https" else "http") + "://$ip:$port/")
        val c = url.openConnection() as java.net.HttpURLConnection
        if (c is HttpsURLConnection) { c.sslSocketFactory = sslContext().socketFactory; c.hostnameVerifier = HostnameVerifier { _, _ -> true } }
        c.connectTimeout = 1500; c.readTimeout = 1800; c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", Http.UA)
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { s ->
            val buf = ByteArray(6000); val n = s.read(buf); if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
        } ?: ""
        val title = Regex("<title[^>]*>([^<]{1,100})</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
        val login = c.getHeaderField("WWW-Authenticate") != null || Regex("type=[\"']?password", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val res = HttpProbe(title?.takeIf { it.isNotBlank() }, c.getHeaderField("Server"), code, login, c.getHeaderField("Location"))
        c.disconnect(); res
    } catch (e: Exception) { null }

    fun tlsInfo(ip: String, port: Int): String? = try {
        val s = sslContext().socketFactory.createSocket() as SSLSocket
        s.use {
            it.soTimeout = 2000
            it.connect(InetSocketAddress(ip, port), 1500)
            it.startHandshake()
            val cert = it.session.peerCertificates.firstOrNull() as? X509Certificate
            if (cert == null) null else {
                val self = cert.subjectX500Principal == cert.issuerX500Principal
                val cn = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)?.groupValues?.get(1) ?: "?"
                val left = (cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L
                "${it.session.protocol} · CN=$cn" + (if (self) " · self-signed" else "") + (if (left < 0) " · EXPIRED" else " · ${left}d left")
            }
        }
    } catch (e: Exception) { null }

    // ---------------- discovery protocols

    suspend fun mdns(ctx: Context, ms: Long = 2500): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val result = HashMap<String, MutableList<String>>()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = try { wm.createMulticastLock("pt-mdns").apply { setReferenceCounted(false); acquire() } } catch (e: Exception) { null }
        try {
            val types = listOf("_services._dns-sd._udp.local", "_googlecast._tcp.local", "_airplay._tcp.local", "_raop._tcp.local", "_ipp._tcp.local", "_printer._tcp.local",
                "_smb._tcp.local", "_http._tcp.local", "_https._tcp.local", "_ssh._tcp.local", "_homekit._tcp.local", "_hap._tcp.local", "_spotify-connect._tcp.local",
                "_companion-link._tcp.local", "_workstation._tcp.local", "_device-info._tcp.local", "_adb-tls-connect._tcp.local", "_androidtvremote2._tcp.local",
                "_matter._tcp.local", "_rfb._tcp.local", "_nfs._tcp.local", "_afpovertcp._tcp.local", "_daap._tcp.local", "_scanner._tcp.local", "_mediaremotetv._tcp.local")
            DatagramSocket().use { s ->
                s.soTimeout = 400
                val group = InetAddress.getByName("224.0.0.251")
                for (chunk in types.chunked(6)) {
                    val q = DnsWire.query(chunk)
                    s.send(DatagramPacket(q, q.size, group, 5353))
                }
                val end = System.currentTimeMillis() + ms
                val buf = ByteArray(4096)
                val hosts = HashMap<String, String>() // hostname -> ip
                val pending = ArrayList<Pair<String, DnsRec>>()
                while (System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (e: SocketTimeoutException) { continue }
                    val from = pkt.address.hostAddress ?: continue
                    val recs = DnsWire.parse(pkt.data, pkt.length)
                    recs.filter { it.type == 1 }.forEach { hosts[it.name.lowercase()] = it.text }
                    val txt = recs.filter { it.type == 16 }.associate { it.name to it.text }
                    val list = result.getOrPut(from) { ArrayList() }
                    for (r in recs) {
                        if (r.type == 12 && r.name != "_services._dns-sd._udp.local" && r.text.contains("._")) {
                            val inst = r.text.substringBefore("._")
                            val kind = DnsWire.friendlyType(r.text.removeSuffix(".local"))
                            val t = txt[r.text]?.split('|')?.firstOrNull { it.startsWith("fn=") || it.startsWith("md=") || it.startsWith("model=") }?.substringAfter('=')
                            val s2 = "$kind: $inst" + (t?.let { " ($it)" } ?: "")
                            if (s2 !in list) list += s2
                        } else if (r.type == 33 || r.type == 16) pending += from to r
                    }
                    if (list.isEmpty()) result.remove(from)
                }
                // devices that only sent A records: label them by hostname
                hosts.forEach { (h, ip) -> val l = result.getOrPut(ip) { ArrayList() }; val n = "Name: " + h.removeSuffix(".local"); if (l.none { it.startsWith("Name:") }) l += n }
            }
        } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
        result
    }

    suspend fun ssdp(ms: Long = 2500): Map<String, String> = withContext(Dispatchers.IO) {
        val found = HashMap<String, MutableMap<String, String>>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 400
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                val g = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, g, 1900)) }
                val end = System.currentTimeMillis() + ms
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (e: SocketTimeoutException) { continue }
                    val ip = pkt.address.hostAddress ?: continue
                    val h = SsdpParse.headers(String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1))
                    val m = found.getOrPut(ip) { HashMap() }
                    h["LOCATION"]?.let { m["loc"] = it }
                    h["SERVER"]?.let { m["server"] = it }
                }
            }
        } catch (e: Exception) { }
        val out = HashMap<String, String>()
        for ((ip, m) in found) {
            var d: String? = null
            m["loc"]?.let { loc -> Http.get(loc, 2000, maxBytes = 40_000)?.let { r -> if (r.first == 200) d = SsdpParse.describe(r.second) } }
            out[ip] = d ?: m["server"]?.take(60) ?: "UPnP device"
        }
        out
    }

    fun netbiosQuery(ip: String): Pair<String?, String?> = try {
        DatagramSocket().use { s ->
            s.soTimeout = 700
            s.send(DatagramPacket(NetBios.REQUEST, NetBios.REQUEST.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024); val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            NetBios.parse(buf, p.length)
        }
    } catch (e: Exception) { null to null }

    fun wakeOnLan(mac: String, broadcast: String): Boolean = try {
        val m = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        require(m.size == 6)
        val pkt = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { m.toList() }.toByteArray()
        DatagramSocket().use { s -> s.broadcast = true; s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(broadcast), 9)) }
        true
    } catch (e: Exception) { false }

    // ---------------- analysis

    fun analyze(d: LanDevice): LanDevice {
        val tags = ArrayList<String>(); val warn = ArrayList<String>(); val advice = ArrayList<String>()
        val open = d.ports.map { it.port }.toSet()
        val svc = d.services.joinToString(" ").lowercase()
        val all = (d.services + listOfNotNull(d.upnp, d.hostname, d.netbios, d.httpTitle, d.httpServer, d.vendor)).joinToString(" ").lowercase()
        if (d.gateway) tags += "Router / gateway"
        if (d.me) tags += "This phone"
        if (62078 in open || "apple" in all || "airplay" in svc || "companion-link" in svc || "homekit" in svc) tags += "Apple device"
        if ("cast" in svc || 8009 in open || 8008 in open || "chromecast" in all) tags += "Chromecast / Google TV"
        if ("androidtv" in svc || "android tv" in all) tags += "Android TV"
        if ("samsung" in all && ("tv" in all || 8001 in open)) tags += "Samsung TV"
        if (554 in open || 8554 in open || 37777 in open || 34567 in open || "camera" in all || "dvr" in all || "hikvision" in all || "dahua" in all) tags += "Camera / DVR"
        if (9100 in open || 631 in open || 515 in open || "printer" in svc || "ipp" in svc || "printer" in all) tags += "Printer"
        if (445 in open || 139 in open || "smb" in svc) tags += "File sharing (SMB)"
        if (3389 in open || "windows" in all) tags += "Windows PC"
        if (22 in open && 3389 !in open) tags += "SSH host (Linux / NAS / Pi)"
        if (1883 in open) tags += "IoT hub (MQTT)"
        if (8123 in open) tags += "Home Assistant"
        if (32400 in open || "plex" in all) tags += "Media server"
        if (5000 in open || 5001 in open || "synology" in all || "qnap" in all) tags += "NAS"
        if ("raspberry" in all) tags += "Raspberry Pi"
        if ("espressif" in all) tags += "ESP IoT device"
        if (d.httpTitle != null && d.gateway) tags += "Router admin page"
        if (d.randomMac) tags += "Private (randomised) MAC"
        d.ports.forEach { p ->
            if (p.risk >= 3) { warn += "${p.service} (${p.port}) open: ${p.note}"; advice += adviceFor(p.port) }
            else if (p.risk == 2) warn += "${p.service} (${p.port}) open: ${p.note}"
        }
        if (d.tls?.contains("EXPIRED") == true) warn += "TLS certificate on this device is expired"
        if (d.tls?.contains("self-signed") == true) advice += "Self-signed certificate: normal for routers/IoT, but do not trust it over public Wi-Fi."
        if (d.ports.any { it.port == 80 } && d.ports.none { it.port == 443 }) advice += "Web admin runs on plain HTTP: passwords are visible to anyone on this Wi-Fi."
        val os = when {
            3389 in open || "windows" in all || 135 in open -> "Windows"
            62078 in open || "apple" in all -> "iOS / macOS"
            "android" in all || 5555 in open || "adb" in svc -> "Android"
            22 in open && ("ubuntu" in all || "debian" in all || "openssh" in all) -> "Linux"
            else -> null
        }
        var risk = d.ports.map { when (it.risk) { 3 -> 30; 2 -> 12; 1 -> 3; else -> 0 } }.sum()
        if (d.tls?.contains("EXPIRED") == true) risk += 8
        risk = risk.coerceIn(0, 100)
        return d.copy(tags = tags.distinct(), warnings = warn, advice = advice.distinct(), risk = risk, osGuess = os)
    }

    fun adviceFor(port: Int): String = when (port) {
        21 -> "Turn off FTP, use SFTP/SSH instead."
        23 -> "Disable Telnet in the device settings; use SSH."
        3389 -> "Block RDP at the firewall or put it behind a VPN; enable Network Level Authentication."
        5555 -> "Turn off 'ADB over network' / developer options on that device."
        5900 -> "Set a strong VNC password or tunnel it over SSH/VPN."
        445 -> "Install Windows updates, disable SMBv1, do not forward port 445 on the router."
        1433, 3306, 5432, 27017, 6379, 9200, 11211 -> "Databases should listen on localhost only, or require passwords and a firewall."
        2375 -> "Never expose the Docker API; bind it to a unix socket."
        69 -> "Disable TFTP unless you are provisioning devices."
        4444 -> "Investigate this device: this port is commonly a backdoor."
        else -> "Close this port if you do not use it."
    }

    // ---------------- scanner

    suspend fun scan(ctx: Context, depth: ScanDepth, known: Map<String, JSONObject>, customPorts: List<Int>? = null, onStage: (String, Float) -> Unit, onDevice: (LanDevice) -> Unit): String? = withContext(Dispatchers.IO) {
        val info = localInfo(ctx) ?: return@withContext "Not connected to Wi-Fi"
        val gate = Semaphore(if (depth == ScanDepth.FULL) 220 else 90)
        val ips = info.hostIps()
        val alive = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val aliveProbe = listOf(80, 443, 22, 445, 62078, 8080, 53, 139, 5555, 554, 9100, 23, 8009, 7000, 1900, 5353, 8008)

        onStage("Finding devices (ping sweep, ARP, Bonjour, UPnP)…", 0.02f)
        val mdnsJob = async { mdns(ctx) }
        val ssdpJob = async { ssdp() }
        val arpMap = Arp.read()
        arpMap.keys.forEach { alive.putIfAbsent(it, 1L) }
        val done = AtomicInteger(0)
        coroutineScope {
            ips.map { ip ->
                async {
                    gate.withPermit {
                        for (p in aliveProbe) {
                            val t0 = System.nanoTime()
                            if (probe(ip, p, 300) >= 0) { alive.putIfAbsent(ip, (System.nanoTime() - t0) / 1_000_000); break }
                        }
                        // ICMP / echo fallback (works when root or on some ROMs)
                        if (!alive.containsKey(ip) && ip == info.gateway) try { if (InetAddress.getByName(ip).isReachable(300)) alive[ip] = 1L } catch (e: Exception) { }
                    }
                    val d = done.incrementAndGet()
                    if (d % 8 == 0) onStage("Finding devices…", 0.02f + d / ips.size.toFloat() * 0.38f)
                }
            }.awaitAll()
        }
        val mdnsMap = withTimeoutOrNull(6000) { mdnsJob.await() } ?: emptyMap()
        val ssdpMap = withTimeoutOrNull(8000) { ssdpJob.await() } ?: emptyMap()
        (mdnsMap.keys + ssdpMap.keys).filter { it.startsWith(info.base + ".") || it.substringBeforeLast('.') == info.base }.forEach { alive.putIfAbsent(it, 2L) }
        alive.putIfAbsent(info.ip, 0L)
        info.gateway?.let { alive.putIfAbsent(it, 1L) }
        if (alive.size <= 1 && mdnsMap.isEmpty()) return@withContext "No devices answered"

        onStage("Inspecting ${alive.size} devices…", 0.42f)
        val ports = customPorts?.takeIf { it.isNotEmpty() } ?: when (depth) { ScanDepth.QUICK -> PortDb.quick; ScanDepth.STANDARD -> PortDb.standard; ScanDepth.DEEP -> PortDb.deep(); ScanDepth.FULL -> PortDb.full() }
        val doneHosts = AtomicInteger(0)
        coroutineScope {
            alive.entries.map { (ip, ms) ->
                async {
                    val openPorts = ArrayList<Int>()
                    val tmo = when (depth) { ScanDepth.FULL -> 220; ScanDepth.DEEP -> 320; else -> 450 }
                    // Probe in chunks so a 65535-port scan never creates tens of thousands of coroutines at once.
                    for (chunk in ports.chunked(600)) {
                        coroutineScope {
                            chunk.map { p -> async { gate.withPermit { if (probe(ip, p, tmo) == 1) p else null } } }.awaitAll().filterNotNull()
                        }.let { openPorts += it }
                    }
                    val host = withTimeoutOrNull(1500) { InetAddress.getByName(ip).canonicalHostName }?.takeIf { it != ip }
                    var (nb, nbMac) = if (depth == ScanDepth.QUICK) null to null else netbiosQuery(ip)
                    val mac = arpMap[ip] ?: nbMac
                    var vendor: String? = null
                    val rnd = mac?.let { MacVendor.isRandom(it) } ?: false
                    if (mac != null && depth != ScanDepth.QUICK) vendor = MacVendor.lookup(mac) ?: MacVendor.offlineLookup(mac)
                    // per-port detail
                    var http: HttpProbe? = null; var tls: String? = null
                    val portInfos = ArrayList<PortInfo>()
                    for (p in openPorts) {
                        var pi = PortDb.info(p)
                        if (depth != ScanDepth.QUICK) {
                            val banner = if (p in listOf(21, 22, 23, 25, 110, 143, 3306, 5900, 1883, 80, 8080, 8000, 8008, 8081, 81, 8088)) bannerGrab(ip, p) else null
                            if (banner != null) pi = pi.copy(banner = banner)
                            if (http == null && p in listOf(80, 8080, 8000, 8008, 8081, 81, 8088, 9000, 5000)) http = httpProbe(ip, p, false)
                            if (p in listOf(443, 8443, 5001, 4443)) {
                                if (http == null) http = httpProbe(ip, p, true)
                                if (tls == null) tls = tlsInfo(ip, p)
                            }
                        }
                        portInfos += pi
                    }
                    val key = mac ?: host ?: nb ?: ip
                    val kn = known[key] ?: known[ip]
                    val dev = analyze(LanDevice(
                        ip = ip, me = ip == info.ip, gateway = ip == info.gateway, mac = mac, vendor = vendor, randomMac = rnd, hostname = host, netbios = nb,
                        upnp = ssdpMap[ip], pingMs = ms, ports = portInfos, services = mdnsMap[ip].orEmpty(),
                        httpTitle = http?.title, httpServer = http?.server, tls = tls,
                        isNew = kn == null && ip != info.ip, nickname = kn?.optString("name")?.takeIf { it.isNotBlank() }
                    ))
                    val d = doneHosts.incrementAndGet()
                    onStage("Inspecting devices… $d/${alive.size}", 0.42f + d / alive.size.toFloat() * 0.58f)
                    onDevice(dev)
                }
            }.awaitAll()
        }
        null
    }
}

// ---------------------------------------------------------------------------------------------
// Known devices (new-device alerts) and Wi-Fi QR helpers
// ---------------------------------------------------------------------------------------------

object KnownDevices {
    private const val FILE = "lan_known"
    fun load(ctx: Context): Map<String, JSONObject> {
        val o = JsonStore.readObject(ctx, FILE)
        return o.keys().asSequence().associateWith { o.getJSONObject(it) }
    }
    fun remember(ctx: Context, d: LanDevice, name: String? = null) {
        val o = JsonStore.readObject(ctx, FILE)
        val prev = o.optJSONObject(d.key())
        o.put(d.key(), JSONObject().put("name", name ?: prev?.optString("name") ?: "").put("first", prev?.optLong("first") ?: System.currentTimeMillis()).put("last", System.currentTimeMillis()).put("ip", d.ip))
        JsonStore.writeObject(ctx, FILE, o)
    }
    fun forget(ctx: Context, d: LanDevice) { val o = JsonStore.readObject(ctx, FILE); o.remove(d.key()); JsonStore.writeObject(ctx, FILE, o) }
}

data class WifiCred(val ssid: String, val password: String, val security: String, val hidden: Boolean)

object WifiQr {
    /** WIFI:T:WPA;S:name;P:pass;H:false;; with \ escapes */
    fun parse(raw: String): WifiCred? {
        if (!raw.startsWith("WIFI:", true)) return null
        val body = raw.substring(5)
        val fields = HashMap<String, String>()
        var key = StringBuilder(); var cur = StringBuilder(); var inKey = true; var esc = false
        for (ch in body) {
            if (esc) { (if (inKey) key else cur).append(ch); esc = false; continue }
            when {
                ch == '\\' -> esc = true
                inKey && ch == ':' -> inKey = false
                !inKey && ch == ';' -> { fields[key.toString().uppercase()] = cur.toString(); key = StringBuilder(); cur = StringBuilder(); inKey = true }
                inKey && ch == ';' -> { key = StringBuilder() }
                else -> (if (inKey) key else cur).append(ch)
            }
        }
        val ssid = fields["S"] ?: return null
        return WifiCred(ssid, fields["P"] ?: "", fields["T"] ?: "nopass", fields["H"].equals("true", true))
    }

    /** Root-only: read the saved networks from the Wi-Fi config store. Empty list on non-rooted phones. */
    fun readSavedAsRoot(): List<WifiCred> {
        val paths = listOf("/data/misc/wifi/WifiConfigStore.xml", "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml", "/data/misc/wifi/wpa_supplicant.conf")
        for (path in paths) {
            try {
                val p = ProcessBuilder("su", "-c", "cat $path").redirectErrorStream(true).start()
                val txt = p.inputStream.bufferedReader().readText(); p.waitFor()
                if (txt.length < 40) continue
                val list = parseStore(txt)
                if (list.isNotEmpty()) return list
            } catch (e: Exception) { }
        }
        return emptyList()
    }

    fun parseStore(txt: String): List<WifiCred> {
        val out = ArrayList<WifiCred>()
        if (txt.contains("<Network>")) {
            Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL).findAll(txt).forEach { m ->
                val b = m.groupValues[1]
                fun f(n: String) = Regex("<string name=\"$n\">([^<]*)</string>").find(b)?.groupValues?.get(1)?.replace("&quot;", "").orEmpty().replace("&amp;", "&")
                val ssid = f("SSID")
                if (ssid.isNotBlank()) out += WifiCred(ssid, f("PreSharedKey"), if (f("PreSharedKey").isEmpty()) "nopass" else "WPA", false)
            }
        } else {
            Regex("network=\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).findAll(txt).forEach { m ->
                val b = m.groupValues[1]
                val ssid = Regex("ssid=\"([^\"]*)\"").find(b)?.groupValues?.get(1).orEmpty()
                val psk = Regex("psk=\"([^\"]*)\"").find(b)?.groupValues?.get(1).orEmpty()
                if (ssid.isNotBlank()) out += WifiCred(ssid, psk, if (psk.isEmpty()) "nopass" else "WPA", false)
            }
        }
        return out
    }

    fun toQr(c: WifiCred): String {
        val bs = "\\"
        fun e(s: String) = s.replace(bs, bs + bs).replace(";", bs + ";").replace(",", bs + ",").replace(":", bs + ":").replace("\"", bs + "\"")
        return "WIFI:T:${if (c.security == "nopass") "nopass" else c.security};S:${e(c.ssid)};P:${e(c.password)};H:${c.hidden};;"
    }
}
