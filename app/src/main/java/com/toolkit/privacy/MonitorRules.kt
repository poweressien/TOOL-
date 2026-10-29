package com.toolkit.privacy

data class Alert(val key: String, val title: String, val text: String, val cooldownMin: Int = 60, val urgent: Boolean = false)

data class MissedCall(val who: String, val whenMs: Long)
data class SoonEvent(val id: Long, val title: String, val startMs: Long, val place: String?)

/** Turns raw measurements into human alerts. No Android here so it can be tested. */
object MonitorRules {
    fun mb(bytes: Long) = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        else -> "${bytes / 1048576} MB"
    }

    private fun names(list: List<String>, max: Int = 3): String {
        val head = list.take(max).joinToString(", ")
        return if (list.size > max) "$head +${list.size - max} more" else head
    }

    fun background(apps: List<String>, minApps: Int = 3): Alert? {
        if (apps.size < minApps) return null
        return Alert("bg", "${apps.size} apps are running in the background", names(apps) + ". Open Network > Data to see who's using data.", 90)
    }

    /** top = (label, bytes) in the last windowMin minutes, any order. */
    fun data(top: List<Pair<String, Long>>, thresholdBytes: Long, windowMin: Int, wifi: Boolean): Alert? {
        val worst = top.filter { it.second >= thresholdBytes }.sortedByDescending { it.second }
        if (worst.isEmpty()) return null
        val first = worst.first()
        val net = if (wifi) "Wi-Fi" else "mobile data"
        val span = if (windowMin >= 60) "${windowMin / 60} hour" + (if (windowMin >= 120) "s" else "") else "$windowMin min"
        val more = if (worst.size > 1) " Also: ${names(worst.drop(1).map { "${it.first} ${mb(it.second)}" }, 2)}." else ""
        return Alert(if (wifi) "data_wifi" else "data_mobile", "${first.first} is draining your $net",
            "${mb(first.second)} used in the last $span.$more", 60, urgent = first.second >= thresholdBytes * 3)
    }

    /** kind: "wifi" | "cell" | "none". level 0..4, rssi in dBm (wifi) or null, pingMs null = failed. */
    fun network(kind: String, level: Int?, rssi: Int?, pingMs: Long?, badStreak: Int): Alert? {
        val bad = when (kind) {
            "none" -> "No internet connection"
            "wifi" -> when {
                rssi != null && rssi <= -80 -> "Weak Wi-Fi signal (${rssi} dBm)"
                pingMs == null -> "Wi-Fi is connected but there's no internet"
                pingMs > 900 -> "Wi-Fi is very slow (${pingMs} ms lag)"
                else -> null
            }
            "cell" -> when {
                level != null && level <= 1 -> "Poor mobile network (${level} of 4 bars)"
                pingMs == null -> "Mobile data isn't getting through"
                pingMs > 1200 -> "Mobile network is very slow (${pingMs} ms lag)"
                else -> null
            }
            else -> null
        } ?: return null
        if (badStreak < 2) return null // wait for two bad readings in a row, avoids lift/tunnel false alarms
        val tip = when (kind) { "wifi" -> "Move closer to the router or switch to mobile data."; "cell" -> "Try moving near a window or outside, or switch SIM/network."; else -> "Check data balance, airplane mode and Wi-Fi." }
        return Alert("net_$kind", bad, tip, 30)
    }

    fun missedCalls(calls: List<MissedCall>): Alert? {
        if (calls.isEmpty()) return null
        val grouped = calls.groupBy { it.who }.map { (w, c) -> if (c.size > 1) "$w (${c.size})" else w }
        return Alert("calls_${calls.maxOf { it.whenMs }}", "${calls.size} missed call${if (calls.size > 1) "s" else ""}", names(grouped, 4), 24 * 60, urgent = true)
    }

    fun calendar(events: List<SoonEvent>, nowMs: Long): List<Alert> = events.map { e ->
        val mins = ((e.startMs - nowMs) / 60000).coerceAtLeast(0)
        val whenTxt = if (mins <= 1) "starting now" else if (mins < 60) "in $mins min" else "in ${mins / 60}h ${mins % 60}m"
        Alert("cal_${e.id}_${e.startMs}", e.title.ifBlank { "Calendar event" } + " " + whenTxt, e.place?.takeIf { it.isNotBlank() }?.let { "At $it" } ?: "Don't forget it", 24 * 60, urgent = mins <= 15)
    }

    fun battery(pct: Int, charging: Boolean): Alert? =
        if (!charging && pct in 0..15) Alert("battery", "Battery at $pct%", "Plug in soon. Background apps keep running and will drain it faster.", 120, urgent = pct <= 8) else null

    fun storage(freeBytes: Long, totalBytes: Long): Alert? {
        if (totalBytes <= 0) return null
        val pct = freeBytes * 100 / totalBytes
        return if (freeBytes < (1500L shl 20) || pct < 8)
            Alert("storage", "Phone storage almost full", "${mb(freeBytes)} left ($pct%). Open Files > Cleaner to free space.", 12 * 60) else null
    }
}

/** Cooldown + dedupe. `store` maps alert key -> last fired time (persist it however you like). */
class AlertGate(private val store: MutableMap<String, Long>) {
    fun allow(a: Alert, nowMs: Long): Boolean {
        val last = store[a.key]
        if (last != null && nowMs - last < a.cooldownMin * 60_000L) return false
        store[a.key] = nowMs
        return true
    }
}
