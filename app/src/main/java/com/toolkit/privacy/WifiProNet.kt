package com.toolkit.privacy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

data class DirectPeer(val name: String, val mac: String, val kind: String, val role: String, val status: String, val vendor: String?, val services: Boolean)

/** Wi-Fi Direct (Wi-Fi P2P) discovery: finds phones, TVs, printers, cameras that are advertising for direct connections. */
class WifiDirectScanner(ctx: Context, private val onPeers: (List<DirectPeer>) -> Unit, private val onNote: (String) -> Unit) {
    private val app = ctx.applicationContext
    private val mgr = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var rx: BroadcastReceiver? = null

    companion object {
        fun kindOf(dev: WifiP2pDevice): String {
            val t = dev.primaryDeviceType ?: return "Unknown"
            val cat = t.substringBefore('-').toIntOrNull() ?: return "Unknown"
            val sub = t.substringAfterLast('-').toIntOrNull() ?: 0
            return when (cat) {
                1 -> "Computer"; 2 -> "Input device"; 3 -> "Printer / scanner"; 4 -> "Camera"; 5 -> "Storage"
                6 -> "Network infrastructure"; 7 -> if (sub == 1) "TV" else "Display"; 8 -> "Media player / set-top"
                9 -> "Game console"; 10 -> "Phone"; 11 -> "Audio device"; 12 -> "Docking"; else -> "Other"
            }
        }
        fun statusOf(s: Int) = when (s) { WifiP2pDevice.CONNECTED -> "connected"; WifiP2pDevice.INVITED -> "invited"; WifiP2pDevice.FAILED -> "failed"; WifiP2pDevice.AVAILABLE -> "available"; else -> "unavailable" }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val m = mgr
        if (m == null) { onNote("This phone has no Wi-Fi Direct."); return }
        try {
            val ch = m.initialize(app, app.mainLooper, null)
            channel = ch
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val intent = i ?: return
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> try {
                            m.requestPeers(ch) { list ->
                                onPeers(list.deviceList.map { d ->
                                    DirectPeer(
                                        name = d.deviceName.ifBlank { "(unnamed)" }, mac = d.deviceAddress ?: "", kind = kindOf(d),
                                        role = if (d.isGroupOwner) "group owner (hosting a network)" else "peer",
                                        status = statusOf(d.status), vendor = MacVendor.offlineLookup(d.deviceAddress ?: ""),
                                        services = d.isServiceDiscoveryCapable
                                    )
                                }.sortedBy { it.name })
                            }
                        } catch (e: SecurityException) { onNote("Permission needed for Wi-Fi Direct.") }
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) onNote("Wi-Fi Direct is off (turn Wi-Fi on).")
                        }
                    }
                }
            }
            rx = r
            val f = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
            discover()
        } catch (e: Exception) { onNote("Wi-Fi Direct unavailable: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    fun discover() {
        val ch = channel ?: return
        try {
            mgr?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { onNote("") }
                override fun onFailure(reason: Int) {
                    onNote(when (reason) { WifiP2pManager.BUSY -> "Wi-Fi Direct is busy, retry in a moment."; WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported."; else -> "Wi-Fi Direct discovery failed ($reason). Is Wi-Fi on?" })
                }
            })
        } catch (e: SecurityException) { onNote("Permission needed for Wi-Fi Direct.") }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { rx?.let { app.unregisterReceiver(it) } } catch (e: Exception) { }
        rx = null
        try { channel?.let { mgr?.stopPeerDiscovery(it, null) } } catch (e: Exception) { }
        try { if (Build.VERSION.SDK_INT >= 27) channel?.close() } catch (e: Exception) { }
        channel = null
    }
}

data class LanHost(val ip: String, val host: String, val mac: String, val vendor: String?, val ports: List<Int>, val rttMs: Int, val hint: String)

/** Finds the other devices on the network THIS phone is joined to (ping + TCP probe sweep of the /24). */
object WifiLanSweep {
    private val probe = intArrayOf(80, 443, 22, 53, 139, 445, 554, 62078, 8009, 8008, 9100, 8080, 5555, 1900, 7000, 3689, 8443)

    private fun hintFor(ports: List<Int>, isGateway: Boolean): String = when {
        isGateway -> "Router / gateway"
        62078 in ports -> "Apple iPhone/iPad (lockdown service)"
        7000 in ports || 3689 in ports -> "Apple TV / AirPlay"
        8009 in ports || 8008 in ports -> "Chromecast / Google TV"
        9100 in ports -> "Network printer"
        554 in ports -> "IP camera / DVR (RTSP)"
        5555 in ports -> "Android with ADB open"
        445 in ports || 139 in ports -> "Computer / NAS (file sharing)"
        22 in ports -> "Linux / server / SBC (SSH)"
        80 in ports || 443 in ports || 8080 in ports || 8443 in ports -> "Device with web page"
        else -> "Responds to ping"
    }

    private fun arpTable(): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            File("/proc/net/arp").readLines().drop(1).forEach { l ->
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[3] != "00:00:00:00:00:00" && p[3].contains(":")) out[p[0]] = p[3].uppercase()
            }
        } catch (e: Exception) { }
        return out
    }

    /** [ip] is the phone's own address, e.g. 192.168.1.23 . Only /24 networks are swept. */
    suspend fun sweep(ip: String, gateway: String, onProgress: (Int) -> Unit): List<LanHost> = withContext(Dispatchers.IO) {
        val parts = ip.split(".")
        if (parts.size != 4) return@withContext emptyList<LanHost>()
        val base = parts.take(3).joinToString(".")
        val gate = Semaphore(48)
        val done = AtomicInteger(0)
        val found = coroutineScope {
            (1..254).map { n ->
                async {
                    gate.withPermit {
                        val addr = "$base.$n"
                        var res: LanHost? = null
                        if (addr != ip) {
                            val t0 = System.nanoTime()
                            var alive = try { InetAddress.getByName(addr).isReachable(350) } catch (e: Exception) { false }
                            val openPorts = mutableListOf<Int>()
                            for (p in probe) {
                                if (openPorts.size >= 6) break
                                try { Socket().use { s -> s.connect(InetSocketAddress(addr, p), if (alive) 120 else 90); openPorts += p; alive = true } } catch (e: Exception) { }
                            }
                            if (alive) {
                                val rtt = ((System.nanoTime() - t0) / 1_000_000).toInt()
                                val name = try { InetAddress.getByName(addr).canonicalHostName.takeIf { it != addr } ?: "" } catch (e: Exception) { "" }
                                res = LanHost(addr, name, "", null, openPorts, rtt, hintFor(openPorts, addr == gateway))
                            }
                        }
                        val d = done.incrementAndGet()
                        if (d % 8 == 0 || d == 254) onProgress(d * 100 / 254)
                        res
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val arp = arpTable()
        found.map { h ->
            val mac = arp[h.ip] ?: ""
            h.copy(mac = mac, vendor = if (mac.isNotEmpty()) MacVendor.offlineLookup(mac) else null)
        }.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }
}
