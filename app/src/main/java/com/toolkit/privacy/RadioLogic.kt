package com.toolkit.privacy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure maths and parsers for Radio & Network Intelligence. No Android, so it is unit-tested on the JVM. */

object Rf {
    data class Band(val name: String, val lowMhz: Double, val highMhz: Double, val kind: String, val note: String)

    val bands = listOf(
        Band("NFC / RFID", 13.56, 13.56, "Short range", "13.56 MHz, works within ~4 cm"),
        Band("FM radio", 87.5, 108.0, "Broadcast", "Needs a wired headset as antenna on most phones"),
        Band("Cellular 700-900", 698.0, 960.0, "Cellular", "LTE bands 5/8/12/13/17/20/28, GSM 900"),
        Band("GNSS L5/L2", 1176.0, 1227.6, "Navigation", "Dual-frequency GPS/Galileo/BeiDou"),
        Band("GNSS L1", 1559.0, 1610.0, "Navigation", "GPS 1575.42, GLONASS, Galileo E1, BeiDou B1"),
        Band("Cellular 1800-2100", 1710.0, 2170.0, "Cellular", "LTE bands 1/2/3/4, UMTS, GSM 1800"),
        Band("Cellular 2300-2700", 2300.0, 2690.0, "Cellular", "LTE bands 7/38/40/41"),
        Band("Wi-Fi / Bluetooth 2.4", 2400.0, 2483.5, "Wireless LAN", "Wi-Fi ch 1-13, Bluetooth, Zigbee, microwave ovens"),
        Band("Cellular 3.5 (5G n78)", 3300.0, 3800.0, "Cellular", "5G mid-band"),
        Band("Wi-Fi 5 GHz", 5150.0, 5895.0, "Wireless LAN", "Wi-Fi ch 36-177, radar shares part of it (DFS)"),
        Band("Wi-Fi 6E / 7 (6 GHz)", 5925.0, 7125.0, "Wireless LAN", "Wi-Fi ch 1-233, also UWB overlaps 6-8.5 GHz"),
        Band("UWB", 6240.0, 8240.0, "Short range", "Ultra-wideband ranging (precision finding, digital keys)")
    )

    fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5150..5895 -> (freq - 5000) / 5
        freq in 5925..7125 -> (freq - 5950) / 5
        else -> 0
    }
}

object Dsp {
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** iq: interleaved unsigned 8-bit I,Q (rtl_tcp format). Returns n power values in dB, DC in the middle. */
    fun spectrum(iq: ByteArray, offset: Int, n: Int): DoubleArray {
        val re = DoubleArray(n); val im = DoubleArray(n)
        var wp = 0.0
        for (k in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * k / (n - 1)); wp += w * w
            re[k] = ((iq[offset + 2 * k].toInt() and 0xFF) - 127.5) / 127.5 * w
            im[k] = ((iq[offset + 2 * k + 1].toInt() and 0xFF) - 127.5) / 127.5 * w
        }
        fft(re, im)
        val out = DoubleArray(n)
        for (k in 0 until n) { val p = (re[k] * re[k] + im[k] * im[k]) / (n * wp / n * n); out[(k + n / 2) % n] = 10 * log10(p + 1e-12) }
        return out
    }

    fun average(list: List<DoubleArray>): DoubleArray {
        if (list.isEmpty()) return DoubleArray(0)
        val o = DoubleArray(list[0].size)
        list.forEach { s -> for (i in o.indices) o[i] += s[i] }
        for (i in o.indices) o[i] /= list.size
        return o
    }

    fun peak(s: DoubleArray): Int { var b = 0; for (i in s.indices) if (s[i] > s[b]) b = i; return b }
}

object Ble {
    data class Adv(
        val flags: Int?, val name: String?, val txPower: Int?, val services: List<String>, val mfr: Map<Int, ByteArray>,
        val serviceData: Map<String, ByteArray>, val appearance: Int?, val facts: List<String>, val beacon: String?, val env: String?, val tracker: String?
    )

    val companies = mapOf(
        0x004C to "Apple", 0x0006 to "Microsoft", 0x0075 to "Samsung", 0x00E0 to "Google", 0x0087 to "Garmin", 0x009E to "Bose", 0x012D to "Sony",
        0x038F to "Xiaomi", 0x027D to "Huawei", 0x0059 to "Nordic Semiconductor", 0x000D to "Texas Instruments", 0x0002 to "Intel", 0x000F to "Broadcom",
        0x0171 to "Amazon", 0x02E5 to "Espressif", 0x0499 to "Ruuvi", 0x0157 to "Huami (Amazfit/Zepp)", 0x0001 to "Nokia", 0x0008 to "Motorola",
        0x0131 to "Cypress", 0x0046 to "MediaTek", 0x0030 to "STMicroelectronics", 0x0078 to "Nike"
    )

    val services = mapOf(
        "1800" to "Generic Access", "1801" to "Generic Attribute", "180A" to "Device Information", "180D" to "Heart Rate", "180F" to "Battery",
        "1812" to "Human Interface Device (keyboard/mouse)", "181A" to "Environmental Sensing", "1809" to "Health Thermometer", "1810" to "Blood Pressure",
        "1816" to "Cycling Speed & Cadence", "1818" to "Cycling Power", "1814" to "Running Speed & Cadence", "181D" to "Weight Scale", "1826" to "Fitness Machine",
        "181C" to "User Data", "1803" to "Link Loss", "1802" to "Immediate Alert", "1804" to "Tx Power", "FD6F" to "Exposure Notification", "FE2C" to "Google Fast Pair",
        "FEAA" to "Eddystone beacon", "FEED" to "Tile tracker", "FE95" to "Xiaomi", "FEF3" to "Google", "FE9F" to "Google", "FD5A" to "Samsung SmartTag"
    )

    private val appleTypes = mapOf(
        0x02 to "iBeacon", 0x03 to "AirPrint", 0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity Pairing (AirPods/Beats)", 0x08 to "Hey Siri",
        0x09 to "AirPlay target", 0x0A to "AirPlay source", 0x0B to "Magic Switch", 0x0C to "Handoff", 0x0D to "Tethering target", 0x0E to "Tethering source",
        0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My network"
    )

    private fun hex(b: ByteArray, s: Int = 0, e: Int = b.size) = (s until e).joinToString("") { "%02X".format(b[it]) }
    private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun s16be(b: ByteArray, i: Int) = u16be(b, i).toShort().toInt()
    private fun uuid16(b: ByteArray, i: Int) = "%04X".format(u16le(b, i))
    private fun uuid128(b: ByteArray, s: Int): String {
        val r = (0 until 16).map { "%02X".format(b[s + 15 - it]) }.joinToString("")
        return "${r.substring(0, 8)}-${r.substring(8, 12)}-${r.substring(12, 16)}-${r.substring(16, 20)}-${r.substring(20)}"
    }

    fun parse(raw: ByteArray): Adv {
        var flags: Int? = null; var name: String? = null; var tx: Int? = null; var appearance: Int? = null
        val svc = ArrayList<String>(); val mfr = LinkedHashMap<Int, ByteArray>(); val sd = LinkedHashMap<String, ByteArray>()
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + len >= raw.size + 0 && i + 1 + len > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val s = i + 2; val e = i + 1 + len
            if (e > raw.size) break
            when (type) {
                0x01 -> if (e > s) flags = raw[s].toInt() and 0xFF
                0x02, 0x03 -> { var k = s; while (k + 1 < e) { svc += uuid16(raw, k); k += 2 } }
                0x04, 0x05 -> { var k = s; while (k + 3 < e) { svc += "%08X".format((u16le(raw, k + 2) shl 16) or u16le(raw, k)); k += 4 } }
                0x06, 0x07 -> { var k = s; while (k + 15 < e) { svc += uuid128(raw, k); k += 16 } }
                0x08, 0x09 -> name = String(raw, s, e - s, Charsets.UTF_8)
                0x0A -> if (e > s) tx = raw[s].toInt()
                0x19 -> if (e - s >= 2) appearance = u16le(raw, s)
                0x16 -> if (e - s >= 2) sd[uuid16(raw, s)] = raw.copyOfRange(s + 2, e)
                0xFF -> if (e - s >= 2) mfr[u16le(raw, s)] = raw.copyOfRange(s + 2, e)
            }
            i = e
        }
        val facts = ArrayList<String>()
        var beacon: String? = null; var env: String? = null; var tracker: String? = null
        flags?.let { f -> facts += "Flags: " + listOfNotNull(if (f and 1 != 0) "limited discoverable" else null, if (f and 2 != 0) "general discoverable" else null, if (f and 4 != 0) "BR/EDR not supported (LE only)" else null).joinToString(", ") }
        mfr.forEach { (id, d) ->
            facts += "Manufacturer: ${companies[id] ?: "0x%04X".format(id)} (${d.size} bytes)"
            if (id == 0x004C && d.isNotEmpty()) {
                val t = d[0].toInt() and 0xFF
                facts += "Apple message: ${appleTypes[t] ?: "type 0x%02X".format(t)}"
                if (t == 0x02 && d.size >= 23) beacon = "iBeacon  UUID ${hex(d, 2, 18).chunked(8).let { "${it[0]}-${it[1].take(4)}-${it[1].drop(4)}-${it[2].take(4)}-${it[2].drop(4)}${it[3]}" }}  major ${u16be(d, 18)}  minor ${u16be(d, 20)}  tx@1m ${d[22].toInt()} dBm"
                if (t == 0x12) tracker = "Apple Find My network accessory (AirTag-class tracker, or an Apple device). If it keeps appearing near you and is not yours, check your phone's unknown-tracker alerts."
                if (t == 0x07) facts += "Looks like earbuds with a charging case"
            }
            if (id == 0x0499 && d.size >= 14 && d[0].toInt() == 5) {
                val t = s16be(d, 1) * 0.005; val h = u16be(d, 3) * 0.0025; val p = (u16be(d, 5) + 50000) / 100.0
                env = "Ruuvi tag: ${"%.2f".format(t)} °C, ${"%.1f".format(h)} % RH, ${"%.1f".format(p)} hPa"
            }
        }
        sd.forEach { (u, d) ->
            facts += "Service data ${services[u] ?: u}: ${hex(d).take(40)}"
            if (u == "FEAA" && d.isNotEmpty()) {
                when (d[0].toInt() and 0xFF) {
                    0x00 -> if (d.size >= 18) beacon = "Eddystone-UID  namespace ${hex(d, 2, 12)}  instance ${hex(d, 12, 18)}"
                    0x10 -> if (d.size >= 3) {
                        val schemes = listOf("http://www.", "https://www.", "http://", "https://")
                        val enc = listOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")
                        val sb = StringBuilder(schemes.getOrElse(d[2].toInt()) { "" })
                        for (k in 3 until d.size) { val c = d[k].toInt() and 0xFF; if (c < enc.size) sb.append(enc[c]) else sb.append(c.toChar()) }
                        beacon = "Eddystone-URL  $sb"
                    }
                    0x20 -> beacon = "Eddystone-TLM (battery/temperature telemetry)"
                    0x30 -> beacon = "Eddystone-EID (rotating ID)"
                }
            }
            if (u == "181A" && d.size == 13) {
                val t = s16be(d, 6) / 10.0
                env = "Thermometer (ATC firmware): ${"%.1f".format(t)} °C, ${d[8].toInt() and 0xFF} % RH, battery ${d[9].toInt() and 0xFF} %"
            }
            if (u == "FEED") tracker = "Tile tracker"
            if (u == "FD5A") tracker = "Possibly a Samsung SmartTag"
            if (u == "FD6F") facts += "Exposure-notification beacon (public health apps)"
        }
        svc.forEach { u -> if (u == "FEED" && tracker == null) tracker = "Tile tracker"; if (u == "FD5A" && tracker == null) tracker = "Possibly a Samsung SmartTag" }
        svc.forEach { facts += "Offers: ${Ble.services[it] ?: it}" }
        return Adv(flags, name, tx, svc, mfr, sd, appearance, facts, beacon, env, tracker)
    }

    /** Rough log-distance estimate, metres. refTx is the power at 1 m (iBeacon/Eddystone report it); otherwise -59 dBm is assumed. */
    fun distanceM(rssi: Int, refTx: Int? = null, n: Double = 2.2): Double = 10.0.pow(((refTx ?: -59) - rssi) / (10 * n))
    fun proximity(d: Double): String = when { d < 0.5 -> "Touching / very close"; d < 2 -> "Same room, near"; d < 6 -> "Same room"; d < 15 -> "Nearby (walls likely)"; else -> "Far" }
}

object NoiseMath {
    /** rms is the 16-bit sample RMS normalised to 0..1. offset maps a full-scale-relative level to an approximate SPL; phones differ by +-10 dB, so it is user-calibratable. */
    fun db(rms: Double, offset: Double = 90.0): Double = (20 * log10(rms.coerceAtLeast(1e-5)) + offset).coerceIn(0.0, 140.0)

    fun label(db: Double): String = when {
        db < 30 -> "Very quiet"; db < 45 -> "Quiet"; db < 60 -> "Normal conversation level"; db < 70 -> "Loud: busy street"; db < 85 -> "Very loud: traffic, vacuum cleaner"
        db < 100 -> "Harmful with long exposure: generator, factory"; else -> "Dangerous: concert, power tools, horns"
    }

    /** NIOSH: 85 dBA for 8 h, every +3 dB halves the allowed time. */
    fun safeMinutes(db: Double): Double = 480.0 / 2.0.pow((db - 85.0) / 3.0)

    /** Fraction of a daily allowance used by `seconds` at `db`. 1.0 = 100 %. */
    fun dose(db: Double, seconds: Double): Double = (seconds / 60.0) / safeMinutes(db)

    data class Event(val kind: String, val title: String, val text: String)

    class Tracker(private val limitDb: Double = 85.0, private val sustainSec: Int = 10, private val cooldownSec: Int = 90) {
        private val win = ArrayDeque<Pair<Double, Double>>()   // time, dB
        private val last = HashMap<String, Double>()
        var doseTotal = 0.0; private set
        var peak = 0.0; private set
        private var prevT = Double.NaN
        private var sumPow = 0.0; private var n = 0
        val leq: Double get() = if (n == 0) 0.0 else 10 * log10(sumPow / n)

        fun push(db: Double, t: Double): List<Event> {
            val out = ArrayList<Event>()
            if (!prevT.isNaN()) doseTotal += dose(db, (t - prevT).coerceIn(0.0, 5.0))
            prevT = t
            peak = maxOf(peak, db); sumPow += 10.0.pow(db / 10); n++
            win.addLast(t to db); while (win.isNotEmpty() && t - win.first().first > 60) win.removeFirst()
            fun ok(k: String) = t - (last[k] ?: -1e9) >= cooldownSec
            val recent = win.filter { t - it.first <= sustainSec }
            if (recent.size >= sustainSec / 2 && recent.first().first <= t - sustainSec + 2 && 10 * log10(recent.map { 10.0.pow(it.second / 10) }.average()) >= limitDb && ok("sustained")) {
                last["sustained"] = t; out += Event("sustained", "Aggressive noise", "About ${"%.0f".format(10 * log10(recent.map { 10.0.pow(it.second / 10) }.average()))} dB for ${sustainSec}s. Safe exposure at this level is ${"%.0f".format(safeMinutes(db))} min per day.")
            }
            val base = PriceLogic.median(win.map { it.second })
            if (win.size >= 20 && db >= 80 && db >= base + 25 && ok("spike")) { last["spike"] = t; out += Event("spike", "Sudden loud noise", "${"%.0f".format(db)} dB burst (normal here is ~${"%.0f".format(base)} dB): horn, bang or alarm.") }
            if (doseTotal >= 1.0 && ok("dose")) { last["dose"] = t; out += Event("dose", "Daily noise dose reached", "You have used 100% of a safe daily noise allowance in this session. Move somewhere quieter or use ear protection.") }
            return out
        }
    }
}

object EnvMath {
    fun dewPoint(t: Double, rh: Double): Double { val a = 17.62; val b = 243.12; val g = ln(rh.coerceIn(1.0, 100.0) / 100) + a * t / (b + t); return b * g / (a - g) }

    fun heatIndexC(tC: Double, rh: Double): Double {
        val t = tC * 9 / 5 + 32
        if (t < 80) return tC
        val hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh - 0.00683783 * t * t - 0.05481717 * rh * rh + 0.00122874 * t * t * rh + 0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh
        return (hi - 32) * 5 / 9
    }

    fun altitudeM(hPa: Double, seaLevel: Double = 1013.25): Double = 44330.0 * (1 - (hPa / seaLevel).pow(1 / 5.255))

    /** points: (time ms, hPa). */
    fun pressureTrend(points: List<Pair<Long, Double>>): String {
        if (points.size < 2) return "Collecting data…"
        val hours = (points.last().first - points.first().first) / 3_600_000.0
        if (hours < 0.05) return "Collecting data…"
        val per3h = (points.last().second - points.first().second) / hours * 3
        return when {
            per3h <= -6 -> "Falling very fast: storm likely"
            per3h <= -2 -> "Falling: weather turning wet or windy"
            per3h >= 6 -> "Rising very fast"
            per3h >= 2 -> "Rising: clearing up"
            else -> "Steady"
        }
    }

    class Anomaly(private val alpha: Double = 0.08, private val k: Double = 3.5, private val warmup: Int = 12, private val minSd: Double = 0.25) {
        private var mean = 0.0; private var v = 0.0; private var n = 0
        data class R(val z: Double, val anomalous: Boolean, val mean: Double)
        fun push(x: Double): R {
            if (n == 0) { mean = x; n = 1; return R(0.0, false, mean) }
            val sd = maxOf(sqrt(v), minSd)
            val z = (x - mean) / sd
            val flag = n >= warmup && abs(z) > k
            if (!flag) { val d = x - mean; mean += alpha * d; v = (1 - alpha) * (v + alpha * d * d) }
            n++
            return R(z, flag, mean)
        }
    }

    /** °C per minute from (time ms, value). */
    fun ratePerMin(points: List<Pair<Long, Double>>, windowMs: Long = 300_000): Double {
        if (points.size < 2) return 0.0
        val end = points.last().first
        val w = points.filter { end - it.first <= windowMs }
        if (w.size < 2) return 0.0
        val dt = (w.last().first - w.first().first) / 60000.0
        return if (dt <= 0) 0.0 else (w.last().second - w.first().second) / dt
    }
}

object Aqi {
    data class Cat(val label: String, val color: Long, val advice: String)

    fun category(aqi: Int): Cat = when {
        aqi <= 50 -> Cat("Good", 0xFF34D399, "Air is clean. Enjoy the outdoors.")
        aqi <= 100 -> Cat("Moderate", 0xFFFBBF24, "Acceptable. Unusually sensitive people may notice effects.")
        aqi <= 150 -> Cat("Unhealthy for sensitive groups", 0xFFFB923C, "Children, older adults and people with asthma should limit long outdoor effort.")
        aqi <= 200 -> Cat("Unhealthy", 0xFFEF4444, "Everyone should reduce prolonged outdoor exertion; consider a mask (N95/FFP2).")
        aqi <= 300 -> Cat("Very unhealthy", 0xFFA855F7, "Avoid outdoor activity, keep windows closed, wear a mask outside.")
        else -> Cat("Hazardous", 0xFF9F1239, "Stay indoors with filtered air. Seek medical help if you feel unwell.")
    }

    private fun interp(c: Double, bp: List<Triple<Double, Double, Pair<Int, Int>>>): Int {
        for ((lo, hi, a) in bp) if (c <= hi + 0.0001) return (((a.second - a.first) / (hi - lo)) * (c - lo) + a.first).let { Math.round(it).toInt() }
        return 500
    }

    /** EPA breakpoints (2024 PM2.5 revision). Concentration in µg/m³. */
    fun fromPm25(c: Double): Int = interp(c.coerceAtLeast(0.0), listOf(
        Triple(0.0, 9.0, 0 to 50), Triple(9.1, 35.4, 51 to 100), Triple(35.5, 55.4, 101 to 150), Triple(55.5, 125.4, 151 to 200), Triple(125.5, 225.4, 201 to 300), Triple(225.5, 325.4, 301 to 500)))

    fun fromPm10(c: Double): Int = interp(c.coerceAtLeast(0.0), listOf(
        Triple(0.0, 54.0, 0 to 50), Triple(55.0, 154.0, 51 to 100), Triple(155.0, 254.0, 101 to 150), Triple(255.0, 354.0, 151 to 200), Triple(355.0, 424.0, 201 to 300), Triple(425.0, 604.0, 301 to 500)))
}

object EnvAlerts {
    data class Snapshot(val noiseDb: Double? = null, val tempC: Double? = null, val tempZ: Double? = null, val tempRate: Double? = null, val humidity: Double? = null,
                        val aqi: Int? = null, val pressureTrend: String? = null, val batteryC: Double? = null)

    data class Alert(val id: String, val title: String, val text: String, val urgent: Boolean)

    fun evaluate(s: Snapshot): List<Alert> {
        val out = ArrayList<Alert>()
        s.tempC?.let { t ->
            if (t >= 38) out += Alert("heat", "Dangerous heat", "${"%.1f".format(t)} °C around the phone. Drink water and avoid direct sun.", true)
            if (t <= 2) out += Alert("cold", "Near freezing", "${"%.1f".format(t)} °C: frost risk for people, plants and phone battery.", false)
        }
        if (s.tempZ != null && abs(s.tempZ) > 3.5 && s.tempC != null) out += Alert("tempshift", "Unusual temperature change", "Temperature is ${"%.1f".format(s.tempC)} °C, far from the recent normal (${"%.1f".format(s.tempZ)}σ).", false)
        if (s.tempRate != null && abs(s.tempRate) >= 1.5) out += Alert("temprate", "Temperature changing fast", "${"%+.1f".format(s.tempRate)} °C per minute. A heater, fire, vent or sun exposure may be involved.", abs(s.tempRate) >= 3)
        s.humidity?.let { h -> if (h >= 85) out += Alert("humid", "Very humid", "${h.toInt()}% humidity: mould risk indoors.", false); if (h <= 20) out += Alert("dry", "Very dry air", "${h.toInt()}% humidity: dry skin and static.", false) }
        s.aqi?.let { a -> if (a > 100) out += Alert("aqi", "Poor air quality", "AQI $a: ${Aqi.category(a).label}. ${Aqi.category(a).advice}", a > 150) }
        s.pressureTrend?.let { if (it.startsWith("Falling very fast")) out += Alert("storm", "Storm warning", "Air pressure is dropping very fast.", true) }
        s.noiseDb?.let { n -> if (n >= 85) out += Alert("noise", "Loud environment", "${n.toInt()} dB: ${NoiseMath.label(n)}.", n >= 100) }
        s.batteryC?.let { b -> if (b >= 43) out += Alert("batt", "Phone is overheating", "Battery at ${"%.1f".format(b)} °C. Stop charging/gaming and let it cool.", true) }
        return out
    }
}
