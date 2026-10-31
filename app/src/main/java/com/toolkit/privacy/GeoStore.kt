package com.toolkit.privacy

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPlace(val id: Long, val name: String, val lat: Double, val lon: Double, val radius: Int, val onEnter: Boolean, val onExit: Boolean, val note: String = "")
data class GeoReminder(val id: Long, val placeId: Long, val text: String, val trigger: String, val repeat: Boolean, val done: Boolean)
data class GeoEvent(val time: Long, val place: String, val kind: String)
data class TripPoint(val lat: Double, val lon: Double, val t: Long, val speed: Float, val acc: Float, val alt: Double)
data class TripMeta(val id: Long, val name: String, val start: Long, val end: Long, val distM: Double, val points: Int)
data class TripStats(val distM: Double, val durationS: Long, val avgKmh: Double, val maxKmh: Double, val climbM: Double, val movingS: Long)
data class Parking(val lat: Double, val lon: Double, val time: Long, val acc: Float, val note: String, val expiry: Long)

object GeoMath {
    private const val R = 6371000.0
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees 0..360 from point 1 to point 2. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun compass(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

    fun fmtDist(m: Double): String = if (m < 1000) "${m.toInt()} m" else "%.2f km".format(m / 1000)

    fun fmtDur(s: Long): String = when { s >= 3600 -> "${s / 3600}h ${s % 3600 / 60}m"; s >= 60 -> "${s / 60}m ${s % 60}s"; else -> "${s}s" }

    fun stats(pts: List<TripPoint>): TripStats {
        if (pts.size < 2) return TripStats(0.0, 0, 0.0, 0.0, 0.0, 0)
        var d = 0.0; var climb = 0.0; var maxMs = 0.0; var moving = 0L
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val seg = distance(a.lat, a.lon, b.lat, b.lon)
            val dt = ((b.t - a.t) / 1000.0).coerceAtLeast(0.001)
            val v = seg / dt
            if (seg > 1.0 && v < 90) { d += seg; if (v > maxMs) maxMs = v; if (v > 0.5) moving += dt.toLong() }
            if (a.alt != 0.0 && b.alt != 0.0 && b.alt > a.alt + 1.0 && b.acc < 30) climb += b.alt - a.alt
        }
        val dur = (pts.last().t - pts.first().t) / 1000
        val avg = if (moving > 0) d / moving * 3.6 else 0.0
        return TripStats(d, dur, avg, maxMs * 3.6, climb, moving)
    }

    fun gpx(name: String, pts: List<TripPoint>): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"EKPE\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>")
        sb.append(name.replace("&", "&amp;").replace("<", "&lt;")).append("</name><trkseg>\n")
        pts.forEach { sb.append("<trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"><ele>${it.alt}</ele><time>${f.format(Date(it.t))}</time></trkpt>\n") }
        return sb.append("</trkseg></trk></gpx>").toString()
    }

    /** Pulls "lat,lon" out of a maps link or plain text. */
    fun parseCoords(s: String): Pair<Double, Double>? {
        val m = Regex("(-?\\d{1,2}\\.\\d{3,})\\s*[,; ]\\s*(-?\\d{1,3}\\.\\d{3,})").find(s) ?: return null
        val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
        return if (a in -90.0..90.0 && b in -180.0..180.0) a to b else null
    }
}

object GeoStore {
    private const val PLACES = "geo_places"; private const val REMS = "geo_reminders"; private const val EVENTS = "geo_events"; private const val TRIPS = "geo_trips"; private const val PARK = "geo_parking"

    fun places(ctx: Context): List<GeoPlace> = JsonStore.list(ctx, PLACES).map {
        GeoPlace(it.optLong("id"), it.optString("name"), it.optDouble("lat"), it.optDouble("lon"), it.optInt("r", 150), it.optBoolean("in", true), it.optBoolean("out", true), it.optString("note"))
    }
    fun savePlaces(ctx: Context, l: List<GeoPlace>) = JsonStore.saveList(ctx, PLACES, l.map { JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("r", it.radius).put("in", it.onEnter).put("out", it.onExit).put("note", it.note) })
    fun addPlace(ctx: Context, p: GeoPlace) = savePlaces(ctx, places(ctx) + p)
    fun removePlace(ctx: Context, id: Long) { savePlaces(ctx, places(ctx).filterNot { it.id == id }); saveReminders(ctx, reminders(ctx).filterNot { it.placeId == id }) }

    fun reminders(ctx: Context): List<GeoReminder> = JsonStore.list(ctx, REMS).map { GeoReminder(it.optLong("id"), it.optLong("place"), it.optString("text"), it.optString("trig", "enter"), it.optBoolean("rep"), it.optBoolean("done")) }
    fun saveReminders(ctx: Context, l: List<GeoReminder>) = JsonStore.saveList(ctx, REMS, l.map { JSONObject().put("id", it.id).put("place", it.placeId).put("text", it.text).put("trig", it.trigger).put("rep", it.repeat).put("done", it.done) })

    fun logEvent(ctx: Context, place: String, kind: String) = JsonStore.append(ctx, EVENTS, JSONObject().put("t", System.currentTimeMillis()).put("p", place).put("k", kind), 200)
    fun events(ctx: Context): List<GeoEvent> = JsonStore.list(ctx, EVENTS).map { GeoEvent(it.optLong("t"), it.optString("p"), it.optString("k")) }.reversed()

    // trips
    fun trips(ctx: Context): List<TripMeta> = JsonStore.list(ctx, TRIPS).map { TripMeta(it.optLong("id"), it.optString("name"), it.optLong("start"), it.optLong("end"), it.optDouble("dist"), it.optInt("n")) }.sortedByDescending { it.start }
    fun saveTrips(ctx: Context, l: List<TripMeta>) = JsonStore.saveList(ctx, TRIPS, l.map { JSONObject().put("id", it.id).put("name", it.name).put("start", it.start).put("end", it.end).put("dist", it.distM).put("n", it.points) })

    fun startTrip(ctx: Context): Long {
        val id = System.currentTimeMillis()
        val name = "Trip " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(id))
        saveTrips(ctx, trips(ctx) + TripMeta(id, name, id, 0L, 0.0, 0))
        Prefs.sp(ctx).edit().putLong(Prefs.GEO_REC, id).apply()
        return id
    }

    fun appendPoint(ctx: Context, id: Long, p: TripPoint) {
        JsonStore.append(ctx, "geo_trip_$id", JSONArray().put(p.lat).put(p.lon).put(p.t).put(p.speed.toDouble()).put(p.acc.toDouble()).put(p.alt).let { JSONObject().put("p", it) }, 20000)
    }

    fun points(ctx: Context, id: Long): List<TripPoint> = JsonStore.list(ctx, "geo_trip_$id").mapNotNull { o ->
        val a = o.optJSONArray("p") ?: return@mapNotNull null
        TripPoint(a.getDouble(0), a.getDouble(1), a.getLong(2), a.getDouble(3).toFloat(), a.getDouble(4).toFloat(), a.getDouble(5))
    }

    fun finishTrip(ctx: Context): TripMeta? {
        val id = Prefs.sp(ctx).getLong(Prefs.GEO_REC, 0L)
        Prefs.sp(ctx).edit().putLong(Prefs.GEO_REC, 0L).apply()
        if (id == 0L) return null
        val pts = points(ctx, id)
        val st = GeoMath.stats(pts)
        val all = trips(ctx)
        val old = all.firstOrNull { it.id == id } ?: return null
        val upd = old.copy(end = System.currentTimeMillis(), distM = st.distM, points = pts.size)
        if (pts.size < 2) saveTrips(ctx, all.filterNot { it.id == id }) else saveTrips(ctx, all.map { if (it.id == id) upd else it })
        return upd
    }

    fun deleteTrip(ctx: Context, id: Long) { saveTrips(ctx, trips(ctx).filterNot { it.id == id }); JsonStore.writeArray(ctx, "geo_trip_$id", JSONArray()) }
    fun renameTrip(ctx: Context, id: Long, name: String) = saveTrips(ctx, trips(ctx).map { if (it.id == id) it.copy(name = name) else it })

    // parking
    fun parking(ctx: Context): Parking? {
        val o = JsonStore.readObject(ctx, PARK)
        return if (o.has("lat")) Parking(o.getDouble("lat"), o.getDouble("lon"), o.optLong("t"), o.optDouble("acc").toFloat(), o.optString("note"), o.optLong("exp")) else null
    }
    fun saveParking(ctx: Context, p: Parking) = JsonStore.writeObject(ctx, PARK, JSONObject().put("lat", p.lat).put("lon", p.lon).put("t", p.time).put("acc", p.acc.toDouble()).put("note", p.note).put("exp", p.expiry))
    fun clearParking(ctx: Context) = JsonStore.writeObject(ctx, PARK, JSONObject())

    /** Blocks (call off the main thread) until a fix arrives or the timeout passes. */
    @SuppressLint("MissingPermission")
    fun currentLocation(ctx: Context, timeoutMs: Long = 12000): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        GeoService.last?.let { if (System.currentTimeMillis() - it.time < 20000 && it.accuracy < 60) return it }
        val latch = CountDownLatch(1)
        var got: Location? = null
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { if (got == null || loc.accuracy < got!!.accuracy) got = loc; if (loc.accuracy < 40) latch.countDown() }
            @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            for (pv in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) if (lm.isProviderEnabled(pv)) lm.requestLocationUpdates(pv, 0L, 0f, l, Looper.getMainLooper())
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) { }
        try { lm.removeUpdates(l) } catch (e: Exception) { }
        if (got != null) return got
        return try { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } } catch (e: Exception) { null }
    }

    fun saveParkingNow(ctx: Context): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Location permission is off. Open Geo → Parking to allow it."
        val loc = currentLocation(ctx) ?: return "Could not get a GPS fix. Step outside and try again."
        saveParking(ctx, Parking(loc.latitude, loc.longitude, System.currentTimeMillis(), loc.accuracy, "", 0L))
        return "🅿 Parking saved (±${loc.accuracy.toInt()} m). Open Geo → Parking to navigate back."
    }

    /** Nominatim (OpenStreetMap) address search. No key needed; light personal use only. */
    fun search(q: String): List<Triple<String, Double, Double>> {
        val arr = Http.jsonArray("https://nominatim.openstreetmap.org/search?format=json&limit=6&q=" + java.net.URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> val o = arr.optJSONObject(i) ?: return@mapNotNull null; Triple(o.optString("display_name").take(90), o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null, o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null) }
    }
}
