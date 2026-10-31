package com.inwekobong.ekpe

import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/** Speed test, ping, traceroute, DNS lookup and port check. Use the port check only on your own network. */
class NetToolkitSection(private val a: MainActivity) : Tool {
    private lateinit var host: EditText
    private lateinit var ports: EditText
    private lateinit var log: TextView
    @Volatile private var cancel = false
    @Volatile private var proc: Process? = null
    @Volatile private var busy = false

    override fun view(): View {
        val p = a.page()
        p.add(a.tv("Network Toolkit", 20f, C.TXT, true), 0)
        p.add(a.tv("The speed test needs internet. Only port-check devices you own.", 12f, C.SUB), 2)
        host = a.edit("Host or IP (e.g. google.com)").apply { setText("google.com"); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        p.add(host, 10)
        ports = a.edit("Ports (blank = common, or 80,443 or 1-1024)")
        p.add(ports, 6)
        fun row(vararg b: Pair<String, () -> Unit>): LinearLayout {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            b.forEachIndexed { i, x -> r.addView(a.button(x.first) { x.second() }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = a.dp(6) }) }
            return r
        }
        p.add(row("Speed" to { speed() }, "Ping" to { ping() }, "Trace" to { trace() }), 8)
        p.add(row("DNS" to { dns() }, "Ports" to { portScan() }, "Router" to { host.setText(LanEngine.gateway(a) ?: "") }), 6)
        p.add(a.button("Cancel") { cancel = true; proc?.destroy() }, 6)
        log = a.tv("", 12f, C.TXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); background = round(C.CARD, a.dp(12)); setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10)) }
        p.add(log, 10)
        return p
    }

    private fun emit(s: String) = a.runOnUiThread { log.append(s + "\n") }
    private fun run(title: String, body: () -> Unit) {
        if (busy) { a.toast("Another test is running"); return }
        busy = true; cancel = false; log.text = ""
        Thread {
            emit("▶ $title")
            try { body() } catch (e: Exception) { emit("Error: ${e.message}") }
            emit(if (cancel) "■ cancelled" else "✔ done"); busy = false
        }.start()
    }
    private fun h() = host.text.toString().trim()

    private fun speed() = run("Speed test (Cloudflare)") {
        var best = Long.MAX_VALUE
        repeat(3) {
            val t = System.nanoTime()
            val c = URL("https://speed.cloudflare.com/__down?bytes=0").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            c.inputStream.use { it.readBytes() }; c.disconnect()
            best = minOf(best, (System.nanoTime() - t) / 1_000_000)
        }
        emit("Latency: $best ms")
        val c = URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        val buf = ByteArray(65536); var n = 0L; val t0 = System.nanoTime()
        c.inputStream.use { ins ->
            while (!cancel && (System.nanoTime() - t0) < 8_000_000_000L) { val r = ins.read(buf); if (r < 0) break; n += r }
        }
        c.disconnect()
        val s = (System.nanoTime() - t0) / 1e9
        emit("Download: ${"%.1f".format(n * 8 / s / 1e6)} Mbps")
        if (cancel) return@run
        val u = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
        u.requestMethod = "POST"; u.doOutput = true; u.connectTimeout = 5000; u.readTimeout = 10000
        val total = 6_000_000L; u.setFixedLengthStreamingMode(total)
        val z = ByteArray(65536); var sent = 0L; val t1 = System.nanoTime()
        u.outputStream.use { os -> while (!cancel && sent < total) { val k = minOf(z.size.toLong(), total - sent).toInt(); os.write(z, 0, k); sent += k } }
        runCatching { u.responseCode }
        val s2 = (System.nanoTime() - t1) / 1e9
        u.disconnect()
        emit("Upload: ${"%.1f".format(sent * 8 / s2 / 1e6)} Mbps")
    }

    private fun ping() = run("Ping ${h()}") {
        val pr = ProcessBuilder("ping", "-c", "4", "-W", "2", h()).redirectErrorStream(true).start(); proc = pr
        pr.inputStream.bufferedReader().forEachLine { emit(it) }
        pr.waitFor()
    }

    private fun trace() = run("Traceroute ${h()}") {
        val dest = InetAddress.getByName(h()).hostAddress
        emit("Target $dest (max 30 hops)")
        for (ttl in 1..30) {
            if (cancel) break
            val pr = ProcessBuilder("ping", "-c", "1", "-t", "$ttl", "-W", "2", dest).redirectErrorStream(true).start(); proc = pr
            val txt = pr.inputStream.bufferedReader().readText(); pr.waitFor()
            val from = Regex("[Ff]rom\\s+([0-9a-fA-F:.]+)").find(txt)?.groupValues?.get(1)?.trimEnd(':')
            val ms = Regex("time=([0-9.]+)").find(txt)?.groupValues?.get(1)
            emit("%2d  %s  %s".format(ttl, from ?: "*", if (ms != null) "$ms ms" else ""))
            if (txt.contains("bytes from") && from == dest) break
        }
    }

    private fun dns() = run("DNS ${h()}") {
        val all = InetAddress.getAllByName(h())
        all.forEach { emit("${if (it.address.size == 4) "A   " else "AAAA"} ${it.hostAddress}") }
        all.firstOrNull()?.let { emit("Reverse: ${it.canonicalHostName}") }
    }

    private fun parsePorts(): List<Int> {
        val t = ports.text.toString().trim()
        if (t.isEmpty()) return listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 554, 993, 3306, 3389, 5555, 8080, 8443, 34567, 37777)
        val out = ArrayList<Int>()
        t.split(',').forEach { s ->
            val r = s.trim().split('-')
            if (r.size == 2) { val lo = r[0].trim().toIntOrNull(); val hi = r[1].trim().toIntOrNull(); if (lo != null && hi != null) for (x in lo..minOf(hi, lo + 1023)) out.add(x) }
            else r[0].trim().toIntOrNull()?.let { out.add(it) }
        }
        return out.filter { it in 1..65535 }.distinct()
    }

    private fun portScan() = run("Port check ${h()}") {
        val ps = parsePorts()
        emit("Checking ${ps.size} ports…")
        val pool = Executors.newFixedThreadPool(24)
        val open = java.util.Collections.synchronizedList(ArrayList<Int>())
        val fs = ps.map { port -> pool.submit { if (!cancel) try { Socket().use { it.connect(InetSocketAddress(h(), port), 600) }; open.add(port) } catch (e: Exception) { } } }
        fs.forEach { runCatching { it.get() } }; pool.shutdown()
        if (open.isEmpty()) emit("No open ports found.") else open.sorted().forEach { emit("OPEN  $it") }
    }

    override fun stop() { cancel = true; proc?.destroy() }
}
