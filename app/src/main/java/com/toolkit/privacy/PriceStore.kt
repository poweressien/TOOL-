package com.toolkit.privacy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** PriceRadar data layer. All public APIs, no keys, no backend; everything saved on the phone. */
object PriceStore {
    data class Offer(val merchant: String, val price: Double, val cur: String, val link: String, val condition: String)
    data class Product(val code: String, val name: String, val brand: String, val category: String, val size: String, val image: String,
                       val offers: List<Offer>, val low: Double, val high: Double, val cur: String, val source: String)
    data class Coin(val id: String, val symbol: String, val name: String, val price: Double, val ch24: Double?, val ch7: Double?,
                    val cap: Double, val rank: Int, val vol: Double, val hi: Double, val lo: Double, val spark: List<Float>)
    data class Tracked(val id: String, val name: String, val code: String, val cur: String, val target: Double, val points: List<PriceLogic.Point>, val note: String)

    fun localCurrency(): String = try { java.util.Currency.getInstance(Locale.getDefault()).currencyCode } catch (e: Exception) { "USD" }

    // ------------------------------------------------------------------ product lookup

    fun lookup(code: String): Product? {
        val c = code.trim()
        if (c.isEmpty()) return null
        val off = lookupOff(c)
        val up = lookupUpc(c)
        if (off == null && up == null) return null
        val offers = up?.offers.orEmpty()
        val prices = offers.map { it.price }.filter { it > 0 }
        return Product(
            c,
            up?.name?.takeIf { it.isNotBlank() } ?: off?.name.orEmpty(),
            up?.brand?.takeIf { it.isNotBlank() } ?: off?.brand.orEmpty(),
            up?.category?.takeIf { it.isNotBlank() } ?: off?.category.orEmpty(),
            off?.size.orEmpty().ifEmpty { up?.size.orEmpty() },
            up?.image?.takeIf { it.isNotBlank() } ?: off?.image.orEmpty(),
            offers,
            up?.low?.takeIf { it > 0 } ?: prices.minOrNull() ?: 0.0,
            up?.high?.takeIf { it > 0 } ?: prices.maxOrNull() ?: 0.0,
            "USD",
            listOfNotNull(if (off != null) "Open Food Facts" else null, if (up != null) "UPCitemdb" else null).joinToString(" + ")
        )
    }

    private fun lookupOff(code: String): Product? {
        val j = Http.json("https://world.openfoodfacts.org/api/v2/product/${URLEncoder.encode(code, "UTF-8")}.json?fields=product_name,brands,quantity,categories,image_small_url") ?: return null
        if (j.optInt("status") != 1) return null
        val p = j.optJSONObject("product") ?: return null
        val name = p.optString("product_name")
        if (name.isBlank() && p.optString("brands").isBlank()) return null
        return Product(code, name, p.optString("brands"), p.optString("categories").split(",").lastOrNull()?.trim().orEmpty(),
            p.optString("quantity"), p.optString("image_small_url"), emptyList(), 0.0, 0.0, "USD", "Open Food Facts")
    }

    private fun lookupUpc(code: String): Product? {
        val j = Http.json("https://api.upcitemdb.com/prod/trial/lookup?upc=${URLEncoder.encode(code, "UTF-8")}") ?: return null
        val items = j.optJSONArray("items") ?: return null
        val it0 = items.optJSONObject(0) ?: return null
        val offers = ArrayList<Offer>()
        val arr = it0.optJSONArray("offers")
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optDouble("price", 0.0)
            if (p > 0) offers += Offer(o.optString("merchant").ifEmpty { o.optString("domain") }, p, o.optString("currency").ifEmpty { "USD" }, o.optString("link"), o.optString("condition"))
        }
        val img = it0.optJSONArray("images")?.optString(0).orEmpty()
        return Product(code, it0.optString("title"), it0.optString("brand"), it0.optString("category"), it0.optString("size"), img,
            offers.sortedBy { it.price }, it0.optDouble("lowest_recorded_price", 0.0), it0.optDouble("highest_recorded_price", 0.0), "USD", "UPCitemdb")
    }

    // ------------------------------------------------------------------ tracked items

    private const val F_TRACK = "price_items"
    private const val F_SPEND = "price_spend"
    private const val F_FX = "price_fx"

    private fun toTracked(o: JSONObject): Tracked {
        val pts = ArrayList<PriceLogic.Point>()
        val a = o.optJSONArray("pts") ?: JSONArray()
        for (i in 0 until a.length()) { val p = a.optJSONArray(i) ?: continue; pts += PriceLogic.Point(p.optLong(0), p.optDouble(1)) }
        return Tracked(o.optString("id"), o.optString("name"), o.optString("code"), o.optString("cur", "USD"), o.optDouble("target", 0.0), pts, o.optString("note"))
    }

    private fun fromTracked(t: Tracked): JSONObject = JSONObject().put("id", t.id).put("name", t.name).put("code", t.code).put("cur", t.cur)
        .put("target", t.target).put("note", t.note).put("pts", JSONArray().also { a -> t.points.takeLast(400).forEach { a.put(JSONArray().put(it.t).put(it.price)) } })

    fun tracked(ctx: Context): List<Tracked> = JsonStore.list(ctx, F_TRACK).map { toTracked(it) }
    fun saveTracked(ctx: Context, l: List<Tracked>) = JsonStore.saveList(ctx, F_TRACK, l.map { fromTracked(it) })

    fun addTracked(ctx: Context, name: String, code: String, cur: String, target: Double, firstPrice: Double): Tracked {
        val pts = if (firstPrice > 0) listOf(PriceLogic.Point(System.currentTimeMillis(), firstPrice)) else emptyList()
        val t = Tracked(System.currentTimeMillis().toString(36), name, code, cur, target, pts, "")
        saveTracked(ctx, tracked(ctx) + t)
        return t
    }

    fun addPoint(ctx: Context, id: String, price: Double) {
        saveTracked(ctx, tracked(ctx).map { if (it.id == id) it.copy(points = it.points + PriceLogic.Point(System.currentTimeMillis(), price)) else it })
    }

    fun setTarget(ctx: Context, id: String, target: Double) { saveTracked(ctx, tracked(ctx).map { if (it.id == id) it.copy(target = target) else it }) }
    fun deleteTracked(ctx: Context, id: String) { saveTracked(ctx, tracked(ctx).filter { it.id != id }) }

    /** Re-checks every tracked item that has a barcode, records the new lowest online offer, alerts when the target is reached. Returns how many were updated. */
    fun refreshAll(ctx: Context): Int {
        var n = 0
        val rates = cachedRates(ctx).second
        val list = tracked(ctx)
        val upd = list.map { t ->
            if (!PriceLogic.validBarcode(t.code)) return@map t
            val p = lookupUpc(t.code) ?: return@map t
            val best = p.offers.minByOrNull { it.price } ?: return@map t
            val v = PriceLogic.convert(best.price, best.cur, t.cur, rates) ?: if (best.cur.equals(t.cur, true)) best.price else return@map t
            n++
            val prev = t.points.lastOrNull()?.price ?: 0.0
            if (PriceLogic.hitTarget(v, t.target) && !(prev > 0 && PriceLogic.hitTarget(prev, t.target)))
                Notify.alert(ctx, 7100 + (t.id.hashCode() and 0xff), "Price drop: ${t.name}", "${PriceLogic.money(v, t.cur)} at ${best.merchant} (target ${PriceLogic.money(t.target, t.cur)})", false, 7, 1)
            t.copy(points = t.points + PriceLogic.Point(System.currentTimeMillis(), v))
        }
        if (n > 0) saveTracked(ctx, upd)
        return n
    }

    // ------------------------------------------------------------------ spending

    fun spends(ctx: Context): List<PriceLogic.Spend> = JsonStore.list(ctx, F_SPEND).map {
        PriceLogic.Spend(it.optLong("t"), it.optDouble("a"), it.optString("c", "USD"), it.optString("k", "Other"), it.optString("n"))
    }.sortedBy { it.t }

    fun addSpend(ctx: Context, s: PriceLogic.Spend) {
        JsonStore.append(ctx, F_SPEND, JSONObject().put("t", s.t).put("a", s.amount).put("c", s.cur).put("k", s.cat).put("n", s.note), 5000)
    }

    fun deleteSpend(ctx: Context, s: PriceLogic.Spend) {
        var removed = false
        JsonStore.saveList(ctx, F_SPEND, JsonStore.list(ctx, F_SPEND).filter {
            val same = !removed && it.optLong("t") == s.t && it.optDouble("a") == s.amount
            if (same) removed = true
            !same
        })
    }

    val categories = listOf("Food", "Transport", "Bills", "Shopping", "Health", "Fun", "Data/Airtime", "Education", "Savings", "Other")

    fun spendCsv(l: List<PriceLogic.Spend>): String = buildString {
        append("date,amount,currency,category,note\n")
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        l.forEach { append(f.format(java.util.Date(it.t))).append(',').append(it.amount).append(',').append(it.cur).append(',').append(it.cat).append(",\"").append(it.note.replace("\"", "\"\"")).append("\"\n") }
    }

    // ------------------------------------------------------------------ fiat rates

    /** Last good rates (units per 1 USD) and the unix-ms timestamp they were fetched. */
    fun cachedRates(ctx: Context): Pair<Long, Map<String, Double>> {
        val o = JsonStore.readObject(ctx, F_FX)
        val r = o.optJSONObject("rates") ?: return 0L to mapOf("USD" to 1.0)
        val m = HashMap<String, Double>()
        r.keys().forEach { k -> m[k] = r.optDouble(k) }
        return o.optLong("t") to m
    }

    fun fetchRates(ctx: Context): Pair<Long, Map<String, Double>>? {
        val j = Http.json("https://open.er-api.com/v6/latest/USD") ?: return null
        if (j.optString("result") != "success") return null
        val r = j.optJSONObject("rates") ?: return null
        val now = System.currentTimeMillis()
        JsonStore.writeObject(ctx, F_FX, JSONObject().put("t", now).put("rates", r))
        return cachedRates(ctx)
    }

    // ------------------------------------------------------------------ crypto

    fun fetchCoins(category: String, page: Int = 1, perPage: Int = 50): List<Coin>? {
        val u = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$perPage&page=$page&sparkline=true&price_change_percentage=24h,7d" +
            if (category.isNotEmpty()) "&category=${URLEncoder.encode(category, "UTF-8")}" else ""
        val a = Http.jsonArray(u, 12000) ?: return null
        val out = ArrayList<Coin>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val sp = ArrayList<Float>()
            o.optJSONObject("sparkline_in_7d")?.optJSONArray("price")?.let { s -> for (k in 0 until s.length() step 4) sp += s.optDouble(k).toFloat() }
            out += Coin(o.optString("id"), o.optString("symbol").uppercase(), o.optString("name"), o.optDouble("current_price", 0.0),
                o.optDouble("price_change_percentage_24h", Double.NaN).takeIf { !it.isNaN() },
                o.optDouble("price_change_percentage_7d_in_currency", Double.NaN).takeIf { !it.isNaN() },
                o.optDouble("market_cap", 0.0), o.optInt("market_cap_rank", 0), o.optDouble("total_volume", 0.0),
                o.optDouble("high_24h", 0.0), o.optDouble("low_24h", 0.0), sp)
        }
        return out
    }
}
