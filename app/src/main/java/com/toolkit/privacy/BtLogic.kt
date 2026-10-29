package com.toolkit.privacy

import kotlin.math.pow

data class BtDevice(
    val address: String,
    var name: String? = null,
    var rssi: Int? = null,
    var txPower: Int? = null,
    var classInt: Int = 0,
    var type: String = "Unknown",       // Classic / LE / Dual / Unknown
    var bonded: Boolean = false,
    var connected: Boolean = false,
    var connectable: Boolean? = null,
    val mfr: MutableMap<Int, ByteArray> = HashMap(),
    val services: MutableSet<String> = LinkedHashSet(),
    var lastSeen: Long = 0L,
    var seenCount: Int = 0
)

object BtLogic {
    private val COMPANIES = mapOf(
        0x004C to "Apple", 0x0006 to "Microsoft", 0x0075 to "Samsung", 0x00E0 to "Google", 0x0087 to "Garmin", 0x0059 to "Nordic Semiconductor",
        0x0002 to "Intel", 0x000F to "Broadcom", 0x000D to "Texas Instruments", 0x012D to "Sony", 0x009E to "Bose", 0x0057 to "Harman (JBL)",
        0x027D to "Huawei", 0x038F to "Xiaomi", 0x000A to "Qualcomm (CSR)", 0x005D to "Realtek", 0x0067 to "GN (Jabra)", 0x0171 to "Amazon",
        0x01DA to "Logitech", 0x006B to "Polar", 0x0046 to "MediaTek", 0x0131 to "Cypress", 0x00D2 to "Dialog Semiconductor", 0x0499 to "Ruuvi"
    )

    private val SERVICES = mapOf(
        "1800" to "Generic Access", "1801" to "Generic Attribute", "1802" to "Immediate Alert", "1803" to "Link Loss", "1804" to "TX Power",
        "1809" to "Thermometer", "180A" to "Device Information", "180D" to "Heart Rate", "180F" to "Battery", "1812" to "Keyboard / mouse (HID)",
        "1816" to "Cycling Speed", "1818" to "Cycling Power", "1819" to "Location & Navigation", "181A" to "Environmental Sensor",
        "181D" to "Weight Scale", "110B" to "Audio sink (A2DP)", "110A" to "Audio source", "111E" to "Hands-free", "1108" to "Headset",
        "FE2C" to "Google Fast Pair", "FEF3" to "Google", "FE9F" to "Google", "FD6F" to "Exposure Notification", "FEAA" to "Eddystone beacon",
        "FEED" to "Tile tracker", "FEEC" to "Tile tracker", "FD5A" to "Samsung SmartTag", "FE65" to "Chipolo tracker", "FE59" to "Nordic DFU (firmware update)",
        "FE95" to "Xiaomi", "FDAB" to "Xiaomi", "FEBE" to "Bose"
    )

    fun company(id: Int) = COMPANIES[id] ?: "Company 0x%04X".format(id)

    fun shortUuid(u: String): String {
        val s = u.uppercase()
        return if (s.length == 36 && s.endsWith("-0000-1000-8000-00805F9B34FB") && s.startsWith("0000")) s.substring(4, 8) else s
    }

    fun serviceName(u: String) = SERVICES[shortUuid(u)]

    fun majorClass(c: Int): String? = when ((c shr 8) and 0x1F) {
        1 -> "Computer"; 2 -> "Phone"; 3 -> "Network"; 4 -> "Audio / video"; 5 -> "Input device"; 6 -> "Imaging"
        7 -> "Wearable"; 8 -> "Toy"; 9 -> "Health device"; else -> null
    }

    fun minorAudio(c: Int): String? = when ((c shr 2) and 0x3F) {
        1 -> "Headset"; 2 -> "Hands-free"; 4 -> "Microphone"; 5 -> "Speaker"; 6 -> "Headphones"; 7 -> "Portable audio"
        8 -> "Car audio"; 10 -> "HiFi audio"; 11 -> "VCR"; 12 -> "Camera"; 13 -> "Camcorder"; 14 -> "Monitor"; 15 -> "TV"; else -> null
    }

    fun distanceM(rssi: Int, txPower: Int? = null): Double = 10.0.pow(((txPower ?: -59) - rssi) / 20.0)

    fun distanceLabel(rssi: Int?, txPower: Int? = null): String {
        if (rssi == null) return "unknown"
        val d = distanceM(rssi, txPower)
        return when {
            d < 1.0 -> "very close (<1 m)"
            d < 3.0 -> "near (~${"%.0f".format(d)} m)"
            d < 10.0 -> "same room (~${"%.0f".format(d)} m)"
            else -> "far (~${"%.0f".format(d)} m+)"
        }
    }

    fun signalPct(rssi: Int?) = if (rssi == null) 0 else ((rssi + 100) * 100 / 60).coerceIn(0, 100)

    /** Detects item trackers from advertising data. Returns a label or null. */
    fun tracker(d: BtDevice): String? {
        val svc = d.services.map { shortUuid(it) }
        if ("FEED" in svc || "FEEC" in svc) return "Tile tracker"
        if ("FD5A" in svc) return "Samsung SmartTag"
        if ("FE65" in svc) return "Chipolo tracker"
        d.mfr[0x004C]?.let { b -> if (b.isNotEmpty() && (b[0].toInt() and 0xFF) == 0x12) return "Apple Find My item (AirTag or similar)" }
        return null
    }

    fun appleKind(d: BtDevice): String? {
        val b = d.mfr[0x004C] ?: return null
        if (b.isEmpty()) return "Apple device"
        return when (b[0].toInt() and 0xFF) {
            0x07 -> "AirPods / Beats (pairing beacon)"; 0x0C -> "Apple device (Handoff)"; 0x10 -> "Apple device (iPhone / iPad / Mac / Watch)"
            0x0F -> "Apple device (Nearby action)"; 0x09 -> "Apple TV / AirPlay"; 0x05 -> "Apple AirDrop"; 0x12 -> "Apple Find My item"; else -> "Apple device"
        }
    }

    /** Best guess at what the thing is. */
    fun kind(d: BtDevice): String {
        tracker(d)?.let { return it }
        appleKind(d)?.let { return it }
        val svc = d.services.map { shortUuid(it) }
        majorClass(d.classInt)?.let { m -> if (m == "Audio / video") return minorAudio(d.classInt) ?: m else return m }
        return when {
            "1812" in svc -> "Keyboard / mouse / controller"
            "180D" in svc -> "Heart-rate sensor / fitness band"
            "110B" in svc || "110A" in svc -> "Audio device"
            "FD6F" in svc -> "Phone (exposure notification)"
            "FEAA" in svc -> "Beacon"
            "FE2C" in svc -> "Earbuds / accessory (Fast Pair)"
            "181A" in svc -> "Environment sensor"
            d.mfr.containsKey(0x0075) -> "Samsung device"
            d.mfr.containsKey(0x0006) -> "Windows / Microsoft device"
            else -> "Unknown device"
        }
    }

    fun icon(d: BtDevice): String {
        val k = kind(d).lowercase()
        return when {
            "tracker" in k || "smarttag" in k || "find my" in k -> "📍"
            "airpod" in k || "headset" in k || "headphone" in k || "earbud" in k || "audio" in k || "speaker" in k -> "🎧"
            "phone" in k -> "📱"
            "computer" in k || "windows" in k || "mac" in k -> "💻"
            "watch" in k || "wearable" in k || "heart" in k || "fitness" in k -> "⌚"
            "keyboard" in k || "mouse" in k || "input" in k || "controller" in k -> "🖱"
            "tv" in k || "video" in k -> "📺"
            "beacon" in k || "sensor" in k -> "📡"
            else -> "🔷"
        }
    }

    fun title(d: BtDevice) = d.name?.takeIf { it.isNotBlank() } ?: "Unnamed ${kind(d).takeIf { it != "Unknown device" } ?: "device"}"

    fun describe(d: BtDevice): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "Address" to d.address
        out += "Looks like" to kind(d)
        out += "Link type" to d.type
        majorClass(d.classInt)?.let { out += "Class" to (it + (minorAudio(d.classInt)?.let { m -> " / $m" } ?: "")) }
        d.rssi?.let { out += "Signal" to "$it dBm, ${distanceLabel(it, d.txPower)}" }
        d.txPower?.let { out += "TX power" to "$it dBm" }
        d.connectable?.let { out += "Connectable" to if (it) "yes" else "no (broadcast only)" }
        out += "Paired" to if (d.bonded) "yes" else "no"
        if (d.connected) out += "Connected to this phone" to "yes"
        if (d.mfr.isNotEmpty()) out += "Made by" to d.mfr.keys.joinToString(", ") { company(it) }
        if (d.services.isNotEmpty()) out += "Services" to d.services.map { shortUuid(it) }.distinct().joinToString(", ") { s -> SERVICES[s]?.let { "$it ($s)" } ?: s }
        if (d.seenCount > 1) out += "Seen" to "${d.seenCount} times this scan"
        return out
    }

    /** Random (privacy) addresses have the top two bits of the first byte set or cleared in specific ways. Returns a note. */
    fun addressNote(addr: String): String? {
        val first = addr.take(2).toIntOrNull(16) ?: return null
        return when (first shr 6) {
            3 -> "Random static address (the device hides its real MAC)"
            1 -> "Rotating private address (changes every ~15 min, so you can't track it by MAC)"
            else -> null
        }
    }
}
