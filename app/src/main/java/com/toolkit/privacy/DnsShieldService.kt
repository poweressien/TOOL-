package com.toolkit.privacy

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

data class DnsEntry(val time: Long, val domain: String, val blocked: Boolean, val category: String)

object ShieldState {
    val running = MutableStateFlow(false)
    val entries = MutableStateFlow<List<DnsEntry>>(emptyList())
    val total = MutableStateFlow(0)
    val blocked = MutableStateFlow(0)
    val top = MutableStateFlow<Map<String, Int>>(emptyMap())
    fun reset() {
        entries.value = emptyList(); total.value = 0; blocked.value = 0; top.value = emptyMap()
    }
}

object Blocklist {
    @Volatile var custom: Set<String> = emptySet()

    val builtIn: Map<String, String> = mapOf(
        "doubleclick.net" to "Ads", "googlesyndication.com" to "Ads", "googleadservices.com" to "Ads",
        "googletagservices.com" to "Ads", "adservice.google.com" to "Ads", "admob.com" to "Ads",
        "amazon-adsystem.com" to "Ads", "adnxs.com" to "Ads", "adsrvr.org" to "Ads", "criteo.com" to "Ads",
        "criteo.net" to "Ads", "taboola.com" to "Ads", "outbrain.com" to "Ads", "rubiconproject.com" to "Ads",
        "pubmatic.com" to "Ads", "openx.net" to "Ads", "moatads.com" to "Ads", "advertising.com" to "Ads",
        "ads.yahoo.com" to "Ads", "ads.twitter.com" to "Ads", "ads.tiktok.com" to "Ads",
        "applovin.com" to "Ads", "unityads.unity3d.com" to "Ads", "ironsrc.com" to "Ads", "supersonicads.com" to "Ads",
        "mopub.com" to "Ads", "vungle.com" to "Ads", "chartboost.com" to "Ads", "inmobi.com" to "Ads",
        "tapjoy.com" to "Ads", "startappservice.com" to "Ads", "smaato.net" to "Ads", "adcolony.com" to "Ads",
        "google-analytics.com" to "Analytics", "googletagmanager.com" to "Analytics", "app-measurement.com" to "Analytics",
        "analytics.google.com" to "Analytics", "analytics.facebook.com" to "Analytics", "analytics.tiktok.com" to "Analytics",
        "scorecardresearch.com" to "Analytics", "quantserve.com" to "Analytics", "hotjar.com" to "Analytics",
        "mouseflow.com" to "Analytics", "mixpanel.com" to "Analytics", "segment.io" to "Analytics",
        "segment.com" to "Analytics", "amplitude.com" to "Analytics", "flurry.com" to "Analytics",
        "mc.yandex.ru" to "Analytics", "bat.bing.com" to "Analytics", "crashlytics.com" to "Analytics",
        "appsflyer.com" to "Tracking", "adjust.com" to "Tracking", "adjust.io" to "Tracking", "branch.io" to "Tracking",
        "kochava.com" to "Tracking", "singular.net" to "Tracking", "px.ads.linkedin.com" to "Tracking",
        "tr.snapchat.com" to "Tracking", "connect.facebook.net" to "Tracking", "pixel.facebook.com" to "Tracking"
    )

    fun load(ctx: Context) {
        custom = ctx.getSharedPreferences("shield", Context.MODE_PRIVATE).getStringSet("custom", emptySet()) ?: emptySet()
    }

    fun setCustom(ctx: Context, set: Set<String>) {
        custom = set
        ctx.getSharedPreferences("shield", Context.MODE_PRIVATE).edit().putStringSet("custom", set).apply()
    }

    /** Returns the category if the domain (or a parent domain) is blocked, else null. */
    fun match(domain: String): String? {
        var d = domain.lowercase().trimEnd('.')
        while (true) {
            builtIn[d]?.let { return it }
            if (d in custom) return "Custom"
            val i = d.indexOf('.')
            if (i < 0) return null
            d = d.substring(i + 1)
        }
    }
}

/**
 * Local DNS filter. Only the virtual DNS address is routed into the tunnel, so all other
 * traffic keeps flowing normally. Blocked names get an NXDOMAIN answer; the rest is
 * forwarded to the chosen upstream resolver.
 */
class DnsShieldService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    private val writeLock = Any()

    private class Q(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dns: ByteArray, val name: String, val qEnd: Int)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }
        Blocklist.load(this)
        startShield(intent?.getStringExtra("upstream") ?: "1.1.1.1")
        return START_NOT_STICKY
    }

    private fun startShield(upstream: String) {
        cleanup()
        val pfd = Builder()
            .setSession("DNS Shield")
            .addAddress("10.77.0.2", 32)
            .addDnsServer("10.77.0.1")
            .addRoute("10.77.0.1", 32)
            .setBlocking(true)
            .establish()
        if (pfd == null) {
            ShieldState.running.value = false
            stopSelf()
            return
        }
        tun = pfd
        ShieldState.reset()
        ShieldState.running.value = true
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = sc
        sc.launch { loop(pfd, upstream) }
    }

    private suspend fun loop(pfd: ParcelFileDescriptor, upstream: String) = coroutineScope {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (isActive) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            val pkt = buf.copyOf(n)
            launch {
                val q = parse(pkt) ?: return@launch
                val cat = Blocklist.match(q.name)
                val resp = if (cat != null) nxdomain(q) else forward(q.dns, upstream)
                record(DnsEntry(System.currentTimeMillis(), q.name, cat != null, cat ?: ""))
                if (resp != null) {
                    val out = buildUdp(q.dstIp, q.srcIp, 53, q.srcPort, resp)
                    synchronized(writeLock) { try { output.write(out) } catch (_: Exception) {} }
                }
            }
        }
    }

    private fun record(e: DnsEntry) {
        ShieldState.total.update { it + 1 }
        ShieldState.entries.update { (listOf(e) + it).take(300) }
        if (e.blocked) {
            ShieldState.blocked.update { it + 1 }
            ShieldState.top.update { m -> m + (e.domain to ((m[e.domain] ?: 0) + 1)) }
        }
    }

    private fun parse(p: ByteArray): Q? {
        if (p.size < 28) return null
        if (((p[0].toInt() shr 4) and 0xF) != 4) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p[9].toInt() != 17 || p.size < ihl + 8) return null
        val srcPort = u16(p, ihl)
        if (u16(p, ihl + 2) != 53) return null
        val udpLen = u16(p, ihl + 4)
        val start = ihl + 8
        val end = minOf(p.size, ihl + udpLen)
        if (end - start < 17) return null
        val dns = p.copyOfRange(start, end)
        var i = 12
        val sb = StringBuilder()
        while (i < dns.size) {
            val l = dns[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if ((l and 0xC0) != 0 || i + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, l, Charsets.US_ASCII))
            i += 1 + l
        }
        val qEnd = i + 4
        if (qEnd > dns.size || sb.isEmpty()) return null
        return Q(p.copyOfRange(12, 16), p.copyOfRange(16, 20), srcPort, dns, sb.toString(), qEnd)
    }

    private fun nxdomain(q: Q): ByteArray {
        val r = q.dns.copyOfRange(0, q.qEnd)
        r[2] = ((q.dns[2].toInt() and 0x01) or 0x80).toByte()
        r[3] = 0x83.toByte()
        put16(r, 6, 0); put16(r, 8, 0); put16(r, 10, 0)
        return r
    }

    private fun forward(dns: ByteArray, upstream: String): ByteArray? = try {
        DatagramSocket().use { s ->
            protect(s)
            s.soTimeout = 3000
            s.connect(InetSocketAddress(upstream, 53))
            s.send(DatagramPacket(dns, dns.size))
            val rb = ByteArray(4096)
            val pk = DatagramPacket(rb, rb.size)
            s.receive(pk)
            rb.copyOf(pk.length)
        }
    } catch (e: Exception) {
        null
    }

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun buildUdp(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45
        put16(b, 2, total)
        b[6] = 0x40
        b[8] = 64
        b[9] = 17
        System.arraycopy(src, 0, b, 12, 4)
        System.arraycopy(dst, 0, b, 16, 4)
        var sum = 0L
        for (i in 0 until 20 step 2) sum += u16(b, i)
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        put16(b, 10, (sum.inv() and 0xFFFF).toInt())
        put16(b, 20, sp); put16(b, 22, dp); put16(b, 24, 8 + payload.size)
        System.arraycopy(payload, 0, b, 28, payload.size)
        return b
    }

    private fun cleanup() {
        scope?.cancel()
        scope = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        ShieldState.running.value = false
    }

    override fun onRevoke() {
        cleanup()
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.toolkit.privacy.STOP_SHIELD"
    }
}
