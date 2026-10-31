package com.toolkit.privacy

/** Pure network maths, no Android dependencies so it can be unit-tested on a plain JVM. */
object NetMath {
    fun cidr(s: String): String {
        val t = s.trim()
        val ip = t.substringBefore('/'); val pre = t.substringAfter('/', "24").toIntOrNull() ?: return "Use a.b.c.d/nn"
        val p = ip.split('.').map { it.toIntOrNull() ?: return "Bad IP" }
        if (p.size != 4 || p.any { it !in 0..255 } || pre !in 0..32) return "Bad address"
        val v = (p[0].toLong() shl 24) or (p[1].toLong() shl 16) or (p[2].toLong() shl 8) or p[3].toLong()
        val mask = if (pre == 0) 0L else (0xFFFFFFFFL shl (32 - pre)) and 0xFFFFFFFFL
        val net = v and mask; val bc = net or (mask.inv() and 0xFFFFFFFFL)
        fun s4(x: Long) = "${(x shr 24) and 255}.${(x shr 16) and 255}.${(x shr 8) and 255}.${x and 255}"
        val hosts = if (pre >= 31) (1L shl (32 - pre)) else (1L shl (32 - pre)) - 2
        val priv = when { p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) -> "private (RFC1918)"; p[0] == 127 -> "loopback"; p[0] == 169 && p[1] == 254 -> "link-local"; p[0] == 100 && p[1] in 64..127 -> "carrier-grade NAT"; else -> "public" }
        return "Network   ${s4(net)}/$pre\nMask      ${s4(mask)}\nWildcard  ${s4(mask.inv() and 0xFFFFFFFFL)}\nFirst     ${s4(if (pre >= 31) net else net + 1)}\nLast      ${s4(if (pre >= 31) bc else bc - 1)}\nBroadcast ${s4(bc)}\nHosts     $hosts\nType      $priv"
    }

    /** Parses "22,80,8000-8100" into a sorted, de-duplicated list of valid ports (1..65535). */
    fun parsePorts(spec: String): List<Int> = spec.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { t ->
        val r = t.split('-')
        val lo = r[0].toIntOrNull(); val hi = if (r.size > 1) r[1].toIntOrNull() else lo
        if (lo == null || hi == null || lo !in 1..65535 || hi !in 1..65535 || hi < lo) emptyList() else (lo..hi).toList()
    }.distinct().sorted()
}
