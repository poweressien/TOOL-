package com.toolkit.privacy

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import org.json.JSONObject

/** Shared live environment readings, fed by whichever of the screen or the background service is running. */
object EnvLive {
    @Volatile var tempC: Double? = null
    @Volatile var hum: Double? = null
    @Volatile var pres: Double? = null
    @Volatile var light: Double? = null
    @Volatile var tempZ: Double? = null
    val tempHist = ArrayList<Pair<Long, Double>>()
    val humHist = ArrayList<Pair<Long, Double>>()
    val presHist = ArrayList<Pair<Long, Double>>()
    val battHist = ArrayList<Pair<Long, Double>>()
    private val anomaly = EnvMath.Anomaly()
    private var lastT = 0L; private var lastH = 0L; private var lastP = 0L

    private fun add(l: ArrayList<Pair<Long, Double>>, v: Double, now: Long) { synchronized(l) { l.add(now to v); while (l.size > 1500) l.removeAt(0) } }
    fun snapshot(l: ArrayList<Pair<Long, Double>>): List<Pair<Long, Double>> = synchronized(l) { l.toList() }

    fun pushTemp(v: Double) { tempC = v; val n = System.currentTimeMillis(); if (n - lastT > 4000) { lastT = n; tempZ = anomaly.push(v).z; add(tempHist, v, n) } }
    fun pushHum(v: Double) { hum = v; val n = System.currentTimeMillis(); if (n - lastH > 4000) { lastH = n; add(humHist, v, n) } }
    fun pushPres(v: Double) { pres = v; val n = System.currentTimeMillis(); if (n - lastP > 15000) { lastP = n; add(presHist, v, n) } }
    fun pushBatt(v: Double) { add(battHist, v, System.currentTimeMillis()) }
}

/** Registers the ambient sensors this phone really has. */
class EnvSensors(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val have: Map<String, Boolean> get() = mapOf(
        "Ambient temperature" to (sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) != null),
        "Humidity" to (sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY) != null),
        "Barometer" to (sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null),
        "Light" to (sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null)
    )

    fun start() {
        for (t in listOf(Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT))
            sm.getDefaultSensor(t)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() { try { sm.unregisterListener(this) } catch (e: Exception) { } }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values[0].toDouble()
        when (e.sensor.type) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> EnvLive.pushTemp(v)
            Sensor.TYPE_RELATIVE_HUMIDITY -> EnvLive.pushHum(v)
            Sensor.TYPE_PRESSURE -> EnvLive.pushPres(v)
            Sensor.TYPE_LIGHT -> EnvLive.light = v
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

object EnvStore {
    data class Air(val aqi: Int?, val euAqi: Int?, val pm25: Double?, val pm10: Double?, val co: Double?, val no2: Double?, val so2: Double?, val o3: Double?,
                   val uv: Double?, val time: String, val hourly: List<Pair<String, Int>>)
    data class Outdoor(val tempC: Double?, val hum: Double?, val pres: Double?, val wind: Double?, val feels: Double?)

    private fun JSONObject.d(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    fun fetchAir(lat: Double, lon: Double): Air? {
        val u = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,european_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone,uv_index&hourly=us_aqi&forecast_days=1&timezone=auto"
        val j = Http.json(u, 10000) ?: return null
        val c = j.optJSONObject("current") ?: return null
        val hs = ArrayList<Pair<String, Int>>()
        val h = j.optJSONObject("hourly")
        val times = h?.optJSONArray("time"); val vals = h?.optJSONArray("us_aqi")
        if (times != null && vals != null) for (i in 0 until minOf(times.length(), vals.length())) if (!vals.isNull(i)) hs += times.optString(i).takeLast(5) to vals.optInt(i)
        return Air(c.d("us_aqi")?.toInt(), c.d("european_aqi")?.toInt(), c.d("pm2_5"), c.d("pm10"), c.d("carbon_monoxide"), c.d("nitrogen_dioxide"), c.d("sulphur_dioxide"), c.d("ozone"), c.d("uv_index"), c.optString("time"), hs)
    }

    fun fetchOutdoor(lat: Double, lon: Double): Outdoor? {
        val j = Http.json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,surface_pressure,wind_speed_10m", 10000) ?: return null
        val c = j.optJSONObject("current") ?: return null
        return Outdoor(c.d("temperature_2m"), c.d("relative_humidity_2m"), c.d("surface_pressure"), c.d("wind_speed_10m"), c.d("apparent_temperature"))
    }

    fun batteryTempC(ctx: Context): Double? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10.0
    }

    fun logAlert(ctx: Context, id: String, title: String, text: String) {
        JsonStore.append(ctx, "env_alerts", JSONObject().put("t", System.currentTimeMillis()).put("id", id).put("title", title).put("text", text), 300)
    }

    data class Logged(val t: Long, val id: String, val title: String, val text: String)
    fun alerts(ctx: Context): List<Logged> = JsonStore.list(ctx, "env_alerts").map { Logged(it.optLong("t"), it.optString("id"), it.optString("title"), it.optString("text")) }
    fun clearAlerts(ctx: Context) = JsonStore.saveList(ctx, "env_alerts", emptyList())

    /** Last place the user told us about or the phone reported. */
    fun place(ctx: Context): Pair<Double, Double>? {
        val sp = Prefs.sp(ctx)
        if (!sp.contains(Prefs.ENV_LAT)) return null
        return sp.getFloat(Prefs.ENV_LAT, 0f).toDouble() to sp.getFloat(Prefs.ENV_LON, 0f).toDouble()
    }
    fun setPlace(ctx: Context, lat: Double, lon: Double) { Prefs.sp(ctx).edit().putFloat(Prefs.ENV_LAT, lat.toFloat()).putFloat(Prefs.ENV_LON, lon.toFloat()).apply() }
}
