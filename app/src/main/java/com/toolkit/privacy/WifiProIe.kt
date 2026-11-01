package com.toolkit.privacy

import android.net.wifi.ScanResult
import android.os.Build
import java.nio.ByteBuffer

/** Everything an access point announces in its beacon, decoded from the raw information elements. */
data class ApExt(
    val parsed: Boolean = false,
    val stations: Int = -1, val utilPct: Int = -1, val country: String = "",
    val gen: String = "", val k: Boolean = false, val v: Boolean = false, val r: Boolean = false,
    val pmf: String = "", val ciphers: String = "", val akm: String = "",
    val wpsState: String = "", val wpsLocked: Boolean = false,
    val wpsMaker: String = "", val wpsModel: String = "", val wpsModelNo: String = "", val wpsName: String = "",
    val passpoint: Boolean = false, val ftm: Boolean = false, val hiddenSsid: Boolean = false,
    val ieCount: Int = 0, val risks: List<String> = emptyList()
)

object WifiIe {
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u16be(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun bytesOf(bb: ByteBuffer): ByteArray {
        val d = bb.duplicate()
        val arr = ByteArray(d.remaining())
        d.get(arr)
        return arr
    }

    private fun cipher(b: ByteArray, p: Int): String {
        val ouiOk = u8(b, p) == 0x00 && u8(b, p + 1) == 0x0F && u8(b, p + 2) == 0xAC
        if (!ouiOk) return "vendor"
        return when (u8(b, p + 3)) { 1 -> "WEP-40"; 2 -> "TKIP"; 4 -> "CCMP"; 5 -> "WEP-104"; 8 -> "GCMP-128"; 9 -> "GCMP-256"; 10 -> "CCMP-256"; else -> "other" }
    }

    private fun akmName(b: ByteArray, p: Int): String {
        val ouiOk = u8(b, p) == 0x00 && u8(b, p + 1) == 0x0F && u8(b, p + 2) == 0xAC
        if (!ouiOk) return "vendor"
        return when (u8(b, p + 3)) {
            1 -> "802.1X"; 2 -> "PSK"; 3 -> "FT-802.1X"; 4 -> "FT-PSK"; 5 -> "802.1X-SHA256"; 6 -> "PSK-SHA256"
            8 -> "SAE"; 9 -> "FT-SAE"; 11 -> "Suite-B"; 12 -> "Suite-B-192"; 18 -> "OWE"; else -> "other"
        }
    }

    private class Rsn(val ciphers: String, val akm: String, val pmf: String)

    private fun rsn(b: ByteArray): Rsn? {
        try {
            if (b.size < 8) return null
            var p = 2 + 4
            val pc = u16le(b, p); p += 2
            val ciphers = LinkedHashSet<String>()
            for (i in 0 until pc) { if (p + 4 > b.size) return null; ciphers += cipher(b, p); p += 4 }
            if (p + 2 > b.size) return Rsn(ciphers.joinToString("/"), "", "")
            val ac = u16le(b, p); p += 2
            val akms = LinkedHashSet<String>()
            for (i in 0 until ac) { if (p + 4 > b.size) return null; akms += akmName(b, p); p += 4 }
            var pmf = "off"
            if (p + 2 <= b.size) {
                val caps = u16le(b, p)
                pmf = if (caps and 0x40 != 0) "required" else if (caps and 0x80 != 0) "capable" else "off"
            }
            return Rsn(ciphers.joinToString("/"), akms.joinToString("/"), pmf)
        } catch (e: Exception) { return null }
    }

    private fun wps(b: ByteArray, out: MutableMap<String, String>) {
        var p = 4
        while (p + 4 <= b.size) {
            val t = u16be(b, p); val l = u16be(b, p + 2); p += 4
            if (p + l > b.size) break
            val s = String(b, p, l, Charsets.UTF_8).trim { it <= ' ' }
            when (t) {
                0x1021 -> out["maker"] = s
                0x1023 -> out["model"] = s
                0x1024 -> out["modelNo"] = s
                0x1011 -> out["name"] = s
                0x1044 -> if (l >= 1) out["state"] = when (u8(b, p)) { 1 -> "not configured"; 2 -> "configured"; else -> "?" }
                0x1057 -> if (l >= 1 && u8(b, p) == 1) out["locked"] = "1"
            }
            p += l
        }
    }

    fun parse(r: ScanResult, security: String, band: String, ssidBlank: Boolean): ApExt {
        var ext = ApExt(hiddenSsid = ssidBlank)
        if (Build.VERSION.SDK_INT < 30) return finish(ext, security, band, false)
        try {
            val ies = r.informationElements
            if (ies.isNullOrEmpty()) return finish(ext, security, band, false)
            var ht = false; var vht = false; var he = false; var eht = false
            var st = -1; var util = -1; var country = ""
            var k = false; var v = false; var rr = false
            var rs: Rsn? = null
            val w = HashMap<String, String>()
            for (ie in ies) {
                val b = bytesOf(ie.bytes)
                when (ie.id) {
                    7 -> if (b.size >= 2 && b[0] > 32 && b[1] > 32) country = String(b, 0, 2, Charsets.US_ASCII)
                    11 -> if (b.size >= 3) { st = u16le(b, 0); util = (u8(b, 2) * 100 + 127) / 255 }
                    45 -> ht = true
                    191 -> vht = true
                    70 -> k = true
                    54 -> rr = true
                    127 -> if (b.size >= 3 && (u8(b, 2) and 0x08) != 0) v = true
                    48 -> rs = rsn(b)
                    221 -> if (b.size >= 4 && u8(b, 0) == 0x00 && u8(b, 1) == 0x50 && u8(b, 2) == 0xF2 && u8(b, 3) == 4) wps(b, w)
                    255 -> when (ie.idExt) { 35 -> he = true; 108, 106 -> eht = true }
                }
            }
            val gen = when {
                eht -> "Wi-Fi 7 (be)"; he -> "Wi-Fi 6 (ax)"; vht -> "Wi-Fi 5 (ac)"; ht -> "Wi-Fi 4 (n)"; else -> "Legacy (a/b/g)"
            }
            val passpoint = try { Build.VERSION.SDK_INT >= 23 && r.isPasspointNetwork } catch (e: Exception) { false }
            @Suppress("DEPRECATION")
            val ftm = try { Build.VERSION.SDK_INT >= 23 && r.is80211mcResponder } catch (e: Exception) { false }
            ext = ext.copy(
                parsed = true, stations = st, utilPct = util, country = country, gen = gen, k = k, v = v, r = rr,
                pmf = rs?.pmf ?: "", ciphers = rs?.ciphers ?: "", akm = rs?.akm ?: "",
                wpsState = w["state"] ?: "", wpsLocked = w["locked"] == "1", wpsMaker = w["maker"] ?: "", wpsModel = w["model"] ?: "",
                wpsModelNo = w["modelNo"] ?: "", wpsName = w["name"] ?: "", passpoint = passpoint, ftm = ftm, ieCount = ies.size
            )
        } catch (e: Exception) { }
        return finish(ext, security, band, ext.parsed)
    }

    private fun finish(e: ApExt, security: String, band: String, parsed: Boolean): ApExt {
        val risks = mutableListOf<String>()
        if (security == "Open") risks += "No encryption: anyone nearby can read your traffic."
        if (security.startsWith("WEP")) risks += "WEP is broken: crackable in minutes."
        if (security.startsWith("WPA (weak)") || e.ciphers.contains("TKIP")) risks += "Legacy TKIP/WPA cipher in use."
        if (e.wpsState.isNotEmpty() && !e.wpsLocked) risks += "WPS is active and not locked: PIN attacks (Pixie Dust / brute force) are possible."
        else if (e.wpsState.isNotEmpty()) risks += "WPS present but locked."
        if (security.contains("WPA3") && e.pmf == "off") risks += "WPA3 advertised without protected management frames."
        if ((security == "WPA2" || security == "WPA2/WPA3") && e.pmf == "off") risks += "No 802.11w (PMF): deauthentication attacks are easy."
        if (e.utilPct >= 70) risks += "Channel is ${e.utilPct}% busy: expect slow speeds."
        if (e.hiddenSsid) risks += "Hidden SSID: hiding the name adds no real security."
        return e.copy(risks = risks)
    }
}
