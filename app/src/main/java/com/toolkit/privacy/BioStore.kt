package com.toolkit.privacy

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------------
// Step maths (pure)
// ---------------------------------------------------------------------------------------------------

data class StepState(val day: String, val base: Float, val last: Float, val carry: Int)
data class StepUpdate(val state: StepState, val today: Int, val finalizedDay: String?, val finalizedSteps: Int)

object StepMath {
    /** Turns the phone's always-increasing step counter into per-day totals, surviving reboots (counter resets to 0). */
    fun update(prev: StepState?, raw: Float, today: String): StepUpdate {
        if (prev == null) return StepUpdate(StepState(today, raw, raw, 0), 0, null, 0)
        var st = prev
        var fin: String? = null; var finSteps = 0
        if (st.day != today) {
            fin = st.day; finSteps = st.carry + (st.last - st.base).toInt().coerceAtLeast(0)
            st = StepState(today, if (raw < st.last) 0f else st.last, st.last, 0)
        }
        if (raw < st.last) st = st.copy(carry = st.carry + (st.last - st.base).toInt().coerceAtLeast(0), base = 0f)
        val today2 = (st.carry + (raw - st.base).toInt()).coerceAtLeast(0)
        return StepUpdate(st.copy(last = raw), today2, fin, finSteps)
    }

    fun distanceKm(steps: Int, heightCm: Int) = steps * (heightCm * 0.415) / 100000.0
    fun kcal(steps: Int, heightCm: Int, kg: Double) = 0.57 * kg * distanceKm(steps, heightCm)
}

// ---------------------------------------------------------------------------------------------------
// Heart rate from a fingertip over the camera (PPG)
// ---------------------------------------------------------------------------------------------------

object Ppg {
    data class Result(val bpm: Int, val rmssd: Int, val quality: Int, val beats: Int)

    fun process(tMs: LongArray, v: DoubleArray): Result? {
        if (tMs.size < 60) return null
        val dur = (tMs.last() - tMs.first()) / 1000.0
        if (dur < 8) return null
        val fs = 30.0
        val n = (dur * fs).toInt()
        val x = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = tMs.first() + (i / fs * 1000).toLong()
            while (j < tMs.size - 2 && tMs[j + 1] < t) j++
            val span = (tMs[j + 1] - tMs[j]).coerceAtLeast(1)
            val f = ((t - tMs[j]).toDouble() / span).coerceIn(0.0, 1.0)
            x[i] = v[j] * (1 - f) + v[j + 1] * f
        }
        // high-pass (remove slow drift) then low-pass (remove noise)
        val w = (fs * 0.8).toInt()
        val hp = DoubleArray(n)
        for (i in 0 until n) { var s = 0.0; var c = 0; for (k in maxOf(0, i - w)..minOf(n - 1, i + w)) { s += x[k]; c++ }; hp[i] = x[i] - s / c }
        val lp = DoubleArray(n)
        for (i in 0 until n) { var s = 0.0; var c = 0; for (k in maxOf(0, i - 2)..minOf(n - 1, i + 2)) { s += hp[k]; c++ }; lp[i] = s / c }
        val skip = (fs * 1.5).toInt()
        val sig = lp.copyOfRange(skip, n)
        val sd = sqrt(sig.map { it * it }.average())
        if (sd < 1e-6) return null
        val minDist = (fs * 0.33).toInt()
        val peaks = ArrayList<Int>()
        for (i in 2 until sig.size - 2) {
            if (sig[i] > 0.25 * sd && sig[i] >= sig[i - 1] && sig[i] >= sig[i + 1] && sig[i] > sig[i - 2] && sig[i] > sig[i + 2]) {
                if (peaks.isEmpty() || i - peaks.last() >= minDist) peaks += i
                else if (sig[i] > sig[peaks.last()]) peaks[peaks.size - 1] = i
            }
        }
        if (peaks.size < 6) return null
        val ibi = peaks.zipWithNext { a, b -> (b - a) / fs }.filter { it in 0.33..1.5 }
        if (ibi.size < 5) return null
        val med = ibi.sorted()[ibi.size / 2]
        val good = ibi.filter { abs(it - med) < 0.2 * med }
        if (good.size < 4) return null
        val bpm = (60.0 / good.average()).toInt()
        val diffs = good.zipWithNext { a, b -> (b - a) * 1000 }
        val rmssd = if (diffs.isEmpty()) 0 else sqrt(diffs.map { it * it }.average()).toInt()
        val q = (good.size * 100 / ibi.size) * minOf(1.0, good.size / 12.0)
        return Result(bpm.coerceIn(30, 220), rmssd, q.toInt().coerceIn(0, 100), good.size + 1)
    }
}

// ---------------------------------------------------------------------------------------------------
// Sleep analysis
// ---------------------------------------------------------------------------------------------------

data class SleepResult(
    val startMs: Long, val endMs: Long, val inBedMin: Int, val asleepMin: Int, val onsetMin: Int, val awakenings: Int,
    val efficiency: Int, val deepMin: Int, val lightMin: Int, val score: Int, val stages: List<Int>, val method: String = "motion"
) {
    val hours: Double get() = asleepMin / 60.0
}

object SleepAnalyzer {
    private fun smooth(m: List<Double>): DoubleArray {
        val n = m.size; val o = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0; var w = 0.0
            for (k in -3..3) { val idx = i + k; if (idx in 0 until n) { val wt = when (abs(k)) { 0 -> 1.0; 1 -> 0.6; 2 -> 0.3; else -> 0.15 }; s += m[idx] * wt; w += wt } }
            o[i] = s / w
        }
        return o
    }

    /** [motion]: one movement value per minute (higher = more movement), starting at [startMs]. */
    fun analyze(startMs: Long, motion: List<Double>): SleepResult? {
        if (motion.size < 20) return null
        val sm = smooth(motion)
        val sorted = sm.sorted()
        val low = sorted.take((sorted.size * 0.4).toInt().coerceAtLeast(1))
        val baseline = low[low.size / 2]
        val thr = (baseline * 3.0).coerceIn(0.04, 0.25)
        val awake = BooleanArray(sm.size) { sm[it] > thr }
        var onset = -1
        for (i in 0..sm.size - 10) if ((i until i + 10).none { awake[it] }) { onset = i; break }
        if (onset < 0) return SleepResult(startMs, startMs + motion.size * 60_000L, motion.size, 0, motion.size, 0, 0, 0, 0, 0, List(motion.size) { 0 })
        var end = sm.size - 1
        while (end > onset && awake[end]) end--
        var awakenings = 0; var run = 0
        for (i in onset..end) { if (awake[i]) run++ else { if (run >= 3) awakenings++; run = 0 } }
        val stages = IntArray(sm.size)
        var deep = 0; var light = 0; var asleep = 0
        var i = onset
        while (i <= end) {
            if (awake[i]) { stages[i] = 0; i++; continue }
            var j = i
            while (j <= end && !awake[j]) j++
            val len = j - i
            for (k in i until j) {
                asleep++
                val quiet = sm[k] < minOf(baseline * 1.3 + 0.01, 0.03)
                // only long quiet stretches count as deep
                if (quiet && len >= 15) { stages[k] = 2; deep++ } else { stages[k] = 1; light++ }
            }
            i = j
        }
        val span = end - onset + 1
        val eff = (asleep * 100 / span).coerceIn(0, 100)
        val hrs = asleep / 60.0
        val dScore = when { hrs >= 7 && hrs <= 9.5 -> 100.0; hrs < 7 -> hrs / 7 * 100; else -> (100 - (hrs - 9.5) * 15).coerceAtLeast(40.0) }
        val aScore = (100 - awakenings * 12).coerceAtLeast(0)
        val deepPct = if (asleep > 0) deep * 100.0 / asleep else 0.0
        val score = (dScore * 0.5 + eff * 0.3 + aScore * 0.1 + minOf(100.0, deepPct * 4) * 0.1).toInt().coerceIn(0, 100)
        return SleepResult(startMs, startMs + motion.size * 60_000L, motion.size, asleep, onset, awakenings, eff, deep, light, score, stages.toList())
    }

    data class Night(val date: String, val startMs: Long, val endMs: Long) { val hours get() = (endMs - startMs) / 3_600_000.0 }

    /** From screen on/off events (true = screen turned on): the longest overnight gap with the screen off is probably sleep. */
    fun inferNights(events: List<Pair<Long, Boolean>>, nowMs: Long = System.currentTimeMillis()): List<Night> {
        val ev = events.sortedBy { it.first }
        val gaps = ArrayList<Pair<Long, Long>>()
        var offAt: Long? = null
        for ((t, on) in ev) {
            if (!on) { if (offAt == null) offAt = t }
            else if (offAt != null) { gaps += offAt to t; offAt = null }
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val byNight = HashMap<String, Pair<Long, Long>>()
        for ((s, e) in gaps) {
            if (e - s < 3 * 3_600_000L) continue
            val c = Calendar.getInstance().apply { timeInMillis = s }
            val h = c.get(Calendar.HOUR_OF_DAY)
            if (h in 5..18) continue // started in daytime, not a night's sleep
            if (h < 5) c.add(Calendar.DAY_OF_YEAR, -1)
            val key = fmt.format(c.time)
            val old = byNight[key]
            if (old == null || (e - s) > (old.second - old.first)) byNight[key] = s to e
        }
        return byNight.map { (k, v) -> Night(k, v.first + 10 * 60_000L, v.second) }.sortedBy { it.date }
    }
}

// ---------------------------------------------------------------------------------------------------
// Persistence + sensors
// ---------------------------------------------------------------------------------------------------

object BioStore {
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = dayFmt.format(Date())
    fun dayAgo(n: Int): String = dayFmt.format(Date(System.currentTimeMillis() - n * 86_400_000L))

    // --- steps
    fun hasActivityPerm(ctx: Context) = Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun hasStepSensor(ctx: Context) = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun readCounter(ctx: Context, timeoutMs: Long = 2500): Float? {
        if (!hasActivityPerm(ctx)) return null
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1); var v: Float? = null
        val ht = HandlerThread("bio-step").also { it.start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { v = e.values[0]; latch.countDown() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try { sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, Handler(ht.looper)); latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: Exception) { }
        sm.unregisterListener(l); ht.quitSafely()
        return v
    }

    @Synchronized
    fun syncSteps(ctx: Context): Int? {
        val raw = readCounter(ctx) ?: return null
        val o = JsonStore.readObject(ctx, "bio_step_state")
        val prev = if (o.has("day")) StepState(o.getString("day"), o.getDouble("base").toFloat(), o.getDouble("last").toFloat(), o.getInt("carry")) else null
        val up = StepMath.update(prev, raw, today())
        JsonStore.writeObject(ctx, "bio_step_state", JSONObject().put("day", up.state.day).put("base", up.state.base.toDouble()).put("last", up.state.last.toDouble()).put("carry", up.state.carry))
        val log = JsonStore.readObject(ctx, "bio_steps")
        if (up.finalizedDay != null) log.put(up.finalizedDay, up.finalizedSteps)
        log.put(today(), up.today)
        JsonStore.writeObject(ctx, "bio_steps", log)
        return up.today
    }

    fun stepHistory(ctx: Context, days: Int): List<Pair<String, Int>> {
        val log = JsonStore.readObject(ctx, "bio_steps")
        return (days - 1 downTo 0).map { dayAgo(it).let { d -> d to log.optInt(d, 0) } }
    }

    // --- heart rate
    data class Hr(val t: Long, val bpm: Int, val src: String, val hrv: Int)
    fun addHr(ctx: Context, bpm: Int, src: String, hrv: Int = 0) = JsonStore.append(ctx, "bio_hr", JSONObject().put("t", System.currentTimeMillis()).put("b", bpm).put("s", src).put("v", hrv), 1500)
    fun hrLog(ctx: Context): List<Hr> = JsonStore.list(ctx, "bio_hr").map { Hr(it.optLong("t"), it.optInt("b"), it.optString("s"), it.optInt("v")) }
    fun clearHr(ctx: Context) = JsonStore.writeArray(ctx, "bio_hr", JSONArray())

    // --- weight
    fun addWeight(ctx: Context, kg: Double) = JsonStore.append(ctx, "bio_weight", JSONObject().put("t", System.currentTimeMillis()).put("kg", kg), 800)
    fun weights(ctx: Context): List<Pair<Long, Double>> = JsonStore.list(ctx, "bio_weight").map { it.optLong("t") to it.optDouble("kg") }

    // --- sleep
    fun addSleep(ctx: Context, r: SleepResult) = JsonStore.append(ctx, "bio_sleep", JSONObject().put("s", r.startMs).put("e", r.endMs).put("asleep", r.asleepMin).put("bed", r.inBedMin).put("onset", r.onsetMin).put("aw", r.awakenings)
        .put("eff", r.efficiency).put("deep", r.deepMin).put("light", r.lightMin).put("score", r.score).put("st", JSONArray(r.stages)).put("m", r.method), 400)
    fun sleeps(ctx: Context): List<SleepResult> = JsonStore.list(ctx, "bio_sleep").map {
        val st = it.optJSONArray("st"); SleepResult(it.optLong("s"), it.optLong("e"), it.optInt("bed"), it.optInt("asleep"), it.optInt("onset"), it.optInt("aw"), it.optInt("eff"), it.optInt("deep"), it.optInt("light"), it.optInt("score"),
            if (st == null) emptyList() else (0 until st.length()).map { i -> st.getInt(i) }, it.optString("m", "motion"))
    }.sortedBy { it.startMs }
    fun deleteSleep(ctx: Context, start: Long) { JsonStore.saveList(ctx, "bio_sleep", JsonStore.list(ctx, "bio_sleep").filter { it.optLong("s") != start }) }

    fun sleepByDay(ctx: Context, days: Int): List<Pair<String, Double>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val map = HashMap<String, Double>()
        sleeps(ctx).forEach { r -> val c = Calendar.getInstance().apply { timeInMillis = r.endMs }; map[fmt.format(c.time)] = (map[fmt.format(c.time)] ?: 0.0) + r.hours }
        return (days - 1 downTo 0).map { dayAgo(it).let { d -> d to (map[d] ?: 0.0) } }
    }

    /** Needs the "usage access" permission the Data tab already asks for. */
    fun screenEvents(ctx: Context, days: Int = 7): List<Pair<Long, Boolean>> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        return try {
            val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val ev = um.queryEvents(System.currentTimeMillis() - days * 86_400_000L, System.currentTimeMillis())
            val out = ArrayList<Pair<Long, Boolean>>()
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == 15) out += e.timeStamp to true else if (e.eventType == 16) out += e.timeStamp to false }
            out
        } catch (ex: Exception) { emptyList() }
    }

    fun profile(ctx: Context): Pair<Int, Double> { val sp = Prefs.sp(ctx); return sp.getInt(Prefs.BIO_HEIGHT, 170) to sp.getFloat(Prefs.BIO_WEIGHT, 70f).toDouble() }

    // --- daily rollover so yesterday's steps are recorded even if the app is closed
    fun scheduleDaily(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 55); set(Calendar.SECOND, 0); if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        val pi = PendingIntent.getBroadcast(ctx, 7701, Intent(ctx, BioAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi) } catch (e: Exception) { }
    }
}

class BioAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { BioStore.syncSteps(ctx.applicationContext) } catch (e: Exception) { }
            BioStore.scheduleDaily(ctx.applicationContext)
            pending.finish()
        }.start()
    }
}

// ---------------------------------------------------------------------------------------------------
// Insights
// ---------------------------------------------------------------------------------------------------

object BioInsights {
    fun generate(steps: List<Pair<String, Int>>, sleep: List<Pair<String, Double>>, hr: List<BioStore.Hr>, stepGoal: Int): List<String> {
        val out = ArrayList<String>()
        val s7 = steps.takeLast(7).map { it.second }; val sPrev = steps.dropLast(7).takeLast(7).map { it.second }
        if (s7.any { it > 0 }) {
            val avg = s7.average()
            out += "Average ${avg.toInt()} steps/day this week" + if (sPrev.any { it > 0 }) { val p = sPrev.average(); val ch = ((avg - p) / p * 100).toInt(); " (${if (ch >= 0) "+" else ""}$ch% vs last week)" } else ""
            val hit = s7.count { it >= stepGoal }
            out += "You reached your $stepGoal-step goal on $hit of 7 days." + if (avg < stepGoal * 0.6) " Short walks after meals add up fast." else ""
        }
        val sl = sleep.takeLast(7).map { it.second }.filter { it > 0 }
        if (sl.isNotEmpty()) {
            val a = sl.average()
            out += "Sleep averages ${"%.1f".format(a)} h." + when { a < 6 -> " That is well under the 7-9 h adults need: protect a fixed bedtime."; a < 7 -> " A little short of the 7-9 h target."; a > 9.5 -> " That is long; consistently oversleeping can also signal poor sleep quality."; else -> " In the healthy range." }
            if (sl.size >= 4) { val sd = sqrt(sl.map { (it - a) * (it - a) }.average()); if (sd > 1.3) out += "Your sleep length varies by about ${"%.1f".format(sd)} h night to night. A steady schedule improves sleep quality." }
        }
        val rest = hr.filter { it.src != "workout" }.takeLast(30).map { it.bpm }
        if (rest.size >= 5) {
            val first = rest.take(rest.size / 2).average(); val second = rest.drop(rest.size / 2).average()
            val base = rest.sorted().take((rest.size / 4).coerceAtLeast(1)).average()
            out += "Resting heart rate is around ${base.toInt()} bpm" + if (second - first > 5) " and trending up lately (stress, poor sleep, illness or caffeine can do that)." else if (first - second > 5) " and trending down, which is a good sign of fitness." else "."
        }
        if (out.isEmpty()) out += "Start tracking: enable steps, record a heart rate and start a sleep session tonight. Insights appear after a few days."
        return out
    }

    fun bmi(kg: Double, cm: Int): Double = kg / ((cm / 100.0) * (cm / 100.0))
    fun bmiLabel(b: Double) = when { b < 18.5 -> "Underweight"; b < 25 -> "Healthy range"; b < 30 -> "Overweight"; else -> "Obese range" }
    fun hrLabel(b: Int) = when { b < 50 -> "Very low (athletes) / check if dizzy"; b < 60 -> "Low, often athletic"; b <= 100 -> "Normal resting"; else -> "High for resting" }
}
