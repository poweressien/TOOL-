package com.inwekobong.ekpe

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Chat and file transfer over Wi-Fi Direct. Both phones need EKPE with this screen open. No internet or mobile data used. */
@SuppressLint("MissingPermission")
class WifiShareSection(private val a: MainActivity) : Tool {
    private val mgr = a.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var ch: WifiP2pManager.Channel? = null
    private lateinit var status: TextView
    private lateinit var peers: LinearLayout
    private lateinit var log: TextView
    private lateinit var msg: EditText
    @Volatile private var sock: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    private var registered = false
    private var server: ServerSocket? = null
    private val PORT = 8988

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> mgr?.requestPeers(ch) { l -> showPeers(l.deviceList.toList()) }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> mgr?.requestConnectionInfo(ch) { info -> if (info.groupFormed) link(info) else if (sock == null) status.text = "Not connected" }
            }
        }
    }

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Phone-to-Phone Share", 20f, C.TXT, true), 0)
        p.add(a.tv("Open this screen on both phones, tap Find phones, then tap the other phone to connect (it will get an accept prompt). Works with no internet; range is roughly 50–100 m in the open. Location must be switched on.", 12f, C.SUB), 4)
        p.add(a.button("Find phones") { discover() }, 10)
        status = a.tv("Not connected", 13f, C.ACC, true); p.add(status, 8)
        peers = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }; p.add(peers, 6)
        log = a.tv("", 13f, C.TXT).apply { setTextIsSelectable(true); background = round(C.CARD, a.dp(12)); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)); minLines = 5 }
        p.add(log, 10)
        msg = a.edit("Message"); p.add(msg, 8)
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(a.button("Send") { sendText() }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(a.button("Send file") { sendFile() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        r.addView(a.button("Disconnect") { disconnect() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = a.dp(6) })
        p.add(r, 6)
        if (mgr == null) status.text = "This phone has no Wi-Fi Direct."
        return p
    }

    private fun discover() {
        val m = mgr ?: return
        GuardPerm.startRadar(a) {
            if (ch == null) ch = m.initialize(a, a.mainLooper, null)
            if (!registered) {
                val f = IntentFilter().apply { addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) }
                if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else a.registerReceiver(rx, f)
                registered = true
            }
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { status.text = "Searching…" }
                override fun onFailure(r: Int) { status.text = "Search failed ($r). Turn on Wi-Fi and Location." }
            })
        }
    }

    private fun showPeers(l: List<WifiP2pDevice>) {
        peers.removeAllViews()
        l.forEach { d ->
            val st = when (d.status) { WifiP2pDevice.CONNECTED -> "connected"; WifiP2pDevice.INVITED -> "invited"; WifiP2pDevice.AVAILABLE -> "available"; else -> "unavailable" }
            peers.add(a.button("${d.deviceName.ifEmpty { d.deviceAddress }} · $st") { connect(d) }, 6)
        }
        peers.applyTypeface(a.tf)
    }

    private fun connect(d: WifiP2pDevice) {
        val cfg = WifiP2pConfig().apply { deviceAddress = d.deviceAddress; wps.setup = WpsInfo.PBC }
        mgr?.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { status.text = "Connecting to ${d.deviceName}… accept on the other phone." }
            override fun onFailure(r: Int) { status.text = "Could not connect ($r)" }
        })
    }

    private fun link(info: WifiP2pInfo) {
        if (sock != null) return
        status.text = "Linked. Opening channel…"
        if (info.isGroupOwner) Thread {
            try { server?.close(); server = ServerSocket(PORT); attach(server!!.accept()) } catch (e: Exception) { a.runOnUiThread { status.text = "Link failed: ${e.message}" } }
        }.start()
        else Thread {
            val host = info.groupOwnerAddress.hostAddress
            for (t in 1..12) {
                try { val s = Socket(); s.connect(InetSocketAddress(host, PORT), 3000); attach(s); return@Thread } catch (e: Exception) { Thread.sleep(1000) }
            }
            a.runOnUiThread { status.text = "Link failed: other phone not ready" }
        }.start()
    }

    private fun say(s: String) = a.runOnUiThread { log.append(s + "\n") }

    private fun attach(s: Socket) {
        sock = s; out = DataOutputStream(s.getOutputStream().buffered())
        a.runOnUiThread { status.text = "Connected ✔" }
        val ins = DataInputStream(s.getInputStream().buffered())
        val dst = File(a.getExternalFilesDir(null) ?: a.filesDir, "Received").also { it.mkdirs() }
        try {
            while (true) {
                when (ins.readByte().toInt()) {
                    1 -> say("Them: " + ins.readUTF())
                    2 -> {
                        val name = File(ins.readUTF()).name; var left = ins.readLong(); val f = File(dst, name); val total = left
                        f.outputStream().use { o -> val b = ByteArray(65536); while (left > 0) { val r = ins.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) throw java.io.EOFException(); o.write(b, 0, r); left -= r } }
                        say("📥 Received $name (${total / 1024} KB) → ${f.path}")
                    }
                }
            }
        } catch (e: Exception) { }
        sock = null; out = null
        a.runOnUiThread { status.text = "Disconnected" }
    }

    private fun sendText() {
        val t = msg.text.toString().trim(); val o = out ?: run { a.toast("Not connected"); return }
        if (t.isEmpty()) return
        Thread { try { synchronized(o) { o.writeByte(1); o.writeUTF(t); o.flush() }; say("Me: $t") } catch (e: Exception) { say("Send failed") } }.start()
        msg.setText("")
    }

    private fun sendFile() {
        val o = out ?: run { a.toast("Not connected"); return }
        a.pickFile { uri ->
            if (uri != null) Thread {
                try {
                    var nm = "file"; var size = -1L
                    a.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { nm = c.getString(it) }; c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) } } }
                    var src: java.io.InputStream = a.contentResolver.openInputStream(uri)!!
                    if (size < 0) { val tmp = File(a.cacheDir, "share.tmp"); src.use { i -> tmp.outputStream().use { i.copyTo(it) } }; size = tmp.length(); src = tmp.inputStream() }
                    say("📤 Sending $nm…")
                    synchronized(o) { o.writeByte(2); o.writeUTF(nm); o.writeLong(size); src.use { i -> val b = ByteArray(65536); while (true) { val r = i.read(b); if (r < 0) break; o.write(b, 0, r) } }; o.flush() }
                    say("✔ Sent $nm")
                } catch (e: Exception) { say("Send failed: ${e.message}") }
            }.start()
        }
    }

    private fun disconnect() {
        runCatching { sock?.close() }; runCatching { server?.close() }; sock = null; out = null
        mgr?.removeGroup(ch, null); status.text = "Disconnected"
    }

    override fun stop() {
        runCatching { sock?.close() }; runCatching { server?.close() }
        if (registered) { runCatching { a.unregisterReceiver(rx) }; registered = false }
        if (ch != null) mgr?.removeGroup(ch, null)
    }
}
