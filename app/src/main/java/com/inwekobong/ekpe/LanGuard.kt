package com.inwekobong.ekpe

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object LanEngine {
    data class Dev(val ip: String, val mac: String, val host: String, val brand: String, val me: Boolean, val gw: Boolean) {
        val key: String get() = if (mac.isNotEmpty()) mac else ip
        val label: String get() = when { me -> "This phone"; gw -> "Router" + (if (host.isNotEmpty()) " ($host)" else ""); host.isNotEmpty() -> host; else -> "Unknown device" }
    }

    private val PORTS = intArrayOf(80, 443, 445, 62078, 22, 8080, 53, 5555)

    // A small, partial vendor table. Many phones use random MACs, which can't be matched.
    private val OUI = mapOf(
        "3C22FB" to "Apple", "F01898" to "Apple", "A483E7" to "Apple", "00A040" to "Apple", "000393" to "Apple", "000A95" to "Apple", "3C0754" to "Apple", "F099BF" to "Apple",
        "001247" to "Samsung", "8C7712" to "Samsung", "3C5AB4" to "Google", "F4F5D8" to "Google",
        "B827EB" to "Raspberry Pi", "DCA632" to "Raspberry Pi", "E45F01" to "Raspberry Pi",
        "50C7BF" to "TP-Link", "F4F26D" to "TP-Link", "00E0FC" to "Huawei", "640980" to "Xiaomi", "34CE00" to "Xiaomi",
        "44650D" to "Amazon", "74C246" to "Amazon", "240AC4" to "Espressif (IoT)", "30AEA4" to "Espressif (IoT)", "84CCA8" to "Espressif (IoT)",
        "00D0B7" to "Intel", "001B21" to "Intel", "3C970E" to "Intel", "001422" to "Dell", "00095B" to "Netgear", "00146C" to "Netgear",
        "00000C" to "Cisco", "001C62" to "LG", "00E091" to "LG", "0013A9" to "Sony", "FC0FE6" to "Sony", "005050" to "Microsoft", "281878" to "Microsoft"
    )

    private val HOST_HINTS = listOf(
        "iphone" to "Apple iPhone", "ipad" to "Apple iPad", "macbook" to "Apple Mac", "apple" to "Apple", "galaxy" to "Samsung", "samsung" to "Samsung",
        "tecno" to "Tecno", "infinix" to "Infinix", "itel" to "itel", "xiaomi" to "Xiaomi", "redmi" to "Xiaomi", "huawei" to "Huawei", "honor" to "Honor",
        "oppo" to "Oppo", "vivo" to "Vivo", "realme" to "Realme", "oneplus" to "OnePlus", "pixel" to "Google Pixel", "android" to "Android device",
        "roku" to "Roku", "chromecast" to "Google Chromecast", "echo" to "Amazon Echo", "firetv" to "Amazon Fire TV", "epson" to "Epson", "canon" to "Canon",
        "brother" to "Brother", "hp" to "HP", "tp-link" to "TP-Link", "tplink" to "TP-Link", "mikrotik" to "MikroTik", "esp" to "Espressif (IoT)", "lg" to "LG", "sony" to "Sony"
    )

    fun net(ctx: Context): Triple<String, Int, String?>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(n) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val lp = cm.getLinkProperties(n) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        return Triple(la.address.hostAddress ?: return null, la.prefixLength, gw)
    }

    fun gateway(ctx: Context): String? = net(ctx)?.third

    private fun toInt(ip: String): Int = InetAddress.getByName(ip).address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    private fun toIp(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    /** -1 = no answer, 0 = alive, 1 = alive and looks like an iPhone/iPad (port 62078). */
    private fun probe(ip: String): Int {
        try { if (InetAddress.getByName(ip).isReachable(250)) return 0 } catch (e: Exception) { }
        var alive = -1
        for (p in PORTS) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, p), 200) }
                if (p == 62078) return 1
                alive = 0
            } catch (e: ConnectException) {
                // "connection refused" means a machine is there but the port is closed
                if ((e.message ?: "").contains("refused", true)) alive = maxOf(alive, 0)
            } catch (e: Exception) { }
        }
        return alive
    }

    private fun readArp(): Map<String, String> {
        val m = HashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { l ->
                val f = l.trim().split(Regex("\\s+"))
                if (f.size >= 4 && f[3] != "00:00:00:00:00:00" && f[3].contains(":")) m[f[0]] = f[3].uppercase()
            }
        } catch (e: Exception) { }
        return m
    }

    private fun brandOf(mac: String, host: String, ios: Boolean): String {
        if (mac.length >= 8) {
            val first = mac.substring(0, 2).toIntOrNull(16) ?: 0
            if (first and 2 != 0) return "Private / random MAC (usually a phone)"
            OUI[mac.replace(":", "").take(6)]?.let { return it }
        }
        val h = host.lowercase()
        HOST_HINTS.firstOrNull { h.contains(it.first) }?.let { return it.second }
        if (ios) return "Apple (iPhone/iPad)"
        return "Unknown brand"
    }

    fun scan(ctx: Context, progress: (Int, Int) -> Unit): List<Dev> {
        val (me, prefix, gw) = net(ctx) ?: return emptyList()
        val p = maxOf(prefix, 22)
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val base = toInt(me) and mask
        val last = base or mask.inv()
        val ips = (base + 1 until last).map { toIp(it) }
        val found = ConcurrentHashMap<String, Int>()
        val hosts = ConcurrentHashMap<String, String>()
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(48)
        val fs = ips.map { ip ->
            pool.submit {
                try {
                    val r = if (ip == me) 0 else probe(ip)
                    if (r >= 0) {
                        found[ip] = r
                        hosts[ip] = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrDefault(ip).takeIf { it != ip } ?: ""
                    }
                } finally { progress(done.incrementAndGet(), ips.size) }
            }
        }
        fs.forEach { runCatching { it.get() } }
        pool.shutdown()
        val arp = readArp()
        return found.keys.sortedBy { toInt(it) }.map { ip ->
            val mac = arp[ip] ?: ""
            val host = hosts[ip] ?: ""
            Dev(ip, mac, host, brandOf(mac, host, found[ip] == 1), ip == me, ip == gw)
        }
    }

    // ---- trusted devices ----
    fun known(ctx: Context): JSONObject = runCatching { JSONObject(ctx.prefs().getString("lan_known", "{}")!!) }.getOrDefault(JSONObject())
    fun trust(ctx: Context, devs: List<Dev>) {
        val k = known(ctx)
        devs.forEach { k.put(it.key, it.label + " · " + it.brand) }
        ctx.prefs().edit().putString("lan_known", k.toString()).apply()
    }

    private val alerted = HashSet<String>()

    /** One background pass: scan and notify about devices that are not trusted yet. */
    fun watchOnce(ctx: Context) {
        val devs = scan(ctx) { _, _ -> }
        if (devs.isEmpty()) return
        val k = known(ctx)
        if (k.length() == 0) { trust(ctx, devs); return }
        for (d in devs) {
            if (!k.has(d.key) && alerted.add(d.key)) {
                GuardNotify.post(ctx, 7000 + (alerted.size % 500), "New device on your Wi-Fi", "${d.label} · ${d.ip} · ${d.brand}")
            }
        }
    }
}

class LanGuardService : Service() {
    @Volatile private var running = false
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        GuardNotify.channels(this)
        val n = Notification.Builder(this, "guard_svc").setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("LAN Guard is watching").setContentText("Checking your Wi-Fi for new devices").setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(61, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(61, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            val ctx = applicationContext
            Thread {
                while (running) {
                    try { LanEngine.watchOnce(ctx) } catch (e: Exception) { }
                    try { Thread.sleep(120000) } catch (e: InterruptedException) { break }
                }
            }.apply { isDaemon = true }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}

class LanGuardSection(private val a: MainActivity) : Tool {
    private lateinit var status: TextView
    private lateinit var out: LinearLayout
    private var devs = listOf<LanEngine.Dev>()
    private var scanning = false

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("LAN Guard", 20f, C.TXT, true), 0)
        p.add(a.tv("Android blocks reading the router's device table, so devices are found by pinging the network. It is slower, and some devices won't answer.", 12f, C.SUB), 4)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(a.button("Scan now") { scan() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(a.button("Trust all") {
            LanEngine.trust(a, devs); a.toast("All ${devs.size} devices trusted"); render()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(row, 10)
        p.add(a.sw("Watch in background and alert me", a.prefs().getBoolean("lan_bg", false)) { on -> background(on) }, 8)
        status = a.tv("Tap Scan now.", 12f, C.SUB)
        p.add(status, 8)
        out = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        p.add(out, 8)
        return p
    }

    private fun background(on: Boolean) {
        a.prefs().edit().putBoolean("lan_bg", on).apply()
        val svc = Intent(a, LanGuardService::class.java)
        if (!on) { a.stopService(svc); return }
        val go = { try { a.startForegroundService(svc) } catch (e: Exception) { a.toast("Could not start background watch") } }
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            a.ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { go() } else go()
    }

    private fun scan() {
        if (scanning) return
        if (LanEngine.net(a) == null) { status.text = "Connect to a Wi-Fi network first."; return }
        scanning = true; status.text = "Scanning…"
        Thread {
            val res = LanEngine.scan(a) { d, t -> if (d % 10 == 0) a.runOnUiThread { status.text = "Scanning… $d / $t" } }
            a.runOnUiThread {
                scanning = false; devs = res
                if (LanEngine.known(a).length() == 0 && res.isNotEmpty()) { LanEngine.trust(a, res); status.text = "First scan: these ${res.size} devices are now your trusted baseline." }
                else status.text = "${res.size} devices found."
                render()
            }
        }.start()
    }

    private fun render() {
        val k = LanEngine.known(a)
        val rows = ArrayList<Row>()
        val fresh = devs.count { !k.has(it.key) }
        rows += Row("ON YOUR NETWORK (${devs.size})" + if (fresh > 0) " · $fresh NEW" else "", header = true)
        devs.forEach { d ->
            val tag = if (!k.has(d.key)) "🆕 NEW · " else ""
            rows += Row(d.ip, tag + d.label + "\n" + d.brand + (if (d.mac.isNotEmpty()) "\n" + d.mac else ""))
        }
        out.removeAllViews(); out.addView(a.table(rows)); out.applyTypeface(a.tf)
    }
}
