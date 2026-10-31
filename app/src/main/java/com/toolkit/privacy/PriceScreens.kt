package com.toolkit.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun d(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t))
private fun pct(v: Double?) = if (v == null) "–" else "${if (v >= 0) "+" else ""}${"%.1f".format(v)}%"
private fun pctColor(v: Double?) = when { v == null -> Color.Unspecified; v >= 0 -> Good; else -> Bad }
private val numKb = KeyboardOptions(keyboardType = KeyboardType.Decimal)

@Composable
private fun StoreLinks(query: String) {
    val ctx = LocalContext.current
    val regions = listOf("All") + PriceLogic.stores.map { it.region }.distinct()
    var region by remember { mutableStateOf("All") }
    Section("Compare in online stores", "Opens a search for “$query” in each shop, so you see live prices from the shop itself.") {
        ChipRow(regions, region) { region = it }
        PriceLogic.stores.filter { region == "All" || it.region == region }.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.weight(1f)); Text(s.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openUrl(ctx, PriceLogic.storeLink(s, query)) }) { Text("Open") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ scan

@Composable
fun PriceScanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var product by remember { mutableStateOf<PriceStore.Product?>(null) }
    var quoted by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf(PriceStore.localCurrency()) }
    var target by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(false) }
    var textQ by remember { mutableStateOf("") }

    fun lookup(c: String) {
        code = c.trim(); product = null; added = false
        if (code.isEmpty()) return
        busy = true; msg = ""
        scope.launch {
            val p = withContext(Dispatchers.IO) { PriceStore.lookup(code) }
            busy = false; product = p
            if (p == null) msg = if (PriceLogic.validBarcode(code)) "Valid barcode, but no public database knows this product. Search it by name below." else "That is not a valid EAN/UPC barcode (check digit fails): possible misprint or counterfeit label."
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("PriceRadar", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Scan a barcode", "Reads EAN / UPC barcodes with the camera, then pulls product details and online offers. Public databases only, nothing is sent to us.") {
                Button(onClick = {
                    msg = ""
                    val o = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128).build()
                    GmsBarcodeScanning.getClient(ctx, o).startScan()
                        .addOnSuccessListener { lookup(it.rawValue ?: "") }
                        .addOnFailureListener { msg = "Scanner unavailable: ${it.message}. Type the number instead." }
                }) { Text("📷 Scan barcode") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(code, { code = it.filter { c -> c.isDigit() } }, Modifier.weight(1f), label = { Text("or type the number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(enabled = code.length >= 8 && !busy, onClick = { lookup(code) }) { Text("Look up") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = Warn)
            }
        }
        product?.let { p ->
            item {
                Section(p.name.ifEmpty { "Unnamed product" }, listOf(p.brand, p.size, p.category).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }) {
                    KV("Barcode", "${p.code} ${if (PriceLogic.validBarcode(p.code)) "✔ valid" else "✖ invalid check digit"}")
                    KV("Source", p.source)
                    if (p.low > 0) { KV("Lowest recorded", "${"%.2f".format(p.low)} USD"); KV("Highest recorded", "${"%.2f".format(p.high)} USD") }
                    if (p.offers.isEmpty()) Hint("No live store offers listed for this barcode. Use the store links below.")
                }
            }
            if (p.offers.isNotEmpty()) item {
                Section("Online offers (${p.offers.size})", "Cheapest first. Prices are as listed by the stores at lookup time.") {
                    val max = p.offers.maxOf { it.price }
                    p.offers.take(15).forEachIndexed { i, o ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(o.merchant.ifEmpty { "Store" } + if (i == 0) "  ★ cheapest" else "", fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
                                if (o.condition.isNotBlank()) Text(o.condition, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(PriceLogic.money(o.price, o.cur), fontWeight = FontWeight.Medium, color = if (i == 0) Good else Color.Unspecified)
                            if (o.link.isNotBlank()) TextButton(onClick = { openUrl(ctx, o.link) }) { Text("Open") }
                        }
                        PercentBar((o.price / max * 100).toInt(), if (i == 0) Good else Info, 4)
                    }
                    if (p.offers.size >= 3) {
                        val v = PriceLogic.judge(p.offers.first().price, p.offers.map { it.price })
                        Hint("Spread: cheapest is ${((1 - p.offers.first().price / PriceLogic.median(p.offers.map { it.price })) * 100).toInt()}% below the median offer.")
                    }
                }
            }
            item {
                Section("Is this deal real?", "Enter the price you were quoted to check it against the offers above.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(quoted, { quoted = it }, Modifier.weight(1f), label = { Text("Quoted price (USD)") }, singleLine = true, keyboardOptions = numKb)
                    }
                    val q = quoted.toDoubleOrNull()
                    if (q != null && q > 0) {
                        val comps = p.offers.map { it.price } + listOf(p.low, p.high).filter { it > 0 }
                        val v = PriceLogic.judge(q, comps)
                        VerdictLine(v)
                    }
                }
            }
            item {
                Section("Track this price") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(cur, { cur = it.uppercase().take(3) }, Modifier.width(90.dp), label = { Text("Cur") }, singleLine = true)
                        OutlinedTextField(target, { target = it }, Modifier.weight(1f), label = { Text("Alert me at or below") }, singleLine = true, keyboardOptions = numKb)
                    }
                    Button(enabled = !added, onClick = {
                        val rates = PriceStore.cachedRates(ctx).second
                        val first = p.offers.firstOrNull()?.let { PriceLogic.convert(it.price, it.cur, cur, rates) ?: if (it.cur.equals(cur, true)) it.price else null } ?: 0.0
                        PriceStore.addTracked(ctx, p.name.ifEmpty { p.code }, p.code, cur, target.toDoubleOrNull() ?: 0.0, first); added = true
                    }) { Text(if (added) "✔ Tracking: see Track tab" else "Start tracking") }
                }
            }
            item { StoreLinks(listOf(p.brand, p.name).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { p.code }) }
        }
        item {
            Section("No barcode? Search by name") {
                OutlinedTextField(textQ, { textQ = it }, Modifier.fillMaxWidth(), label = { Text("Product name / model") }, singleLine = true)
            }
        }
        if (textQ.isNotBlank()) item { StoreLinks(textQ.trim()) }
    }
}

@Composable
private fun VerdictLine(v: PriceLogic.Verdict) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ScoreGauge(v.score, 96.dp, "safety")
        Column {
            Text(v.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(SiteRules.colorFor(v.score)))
            v.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ track

@Composable
fun PriceTrackScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(PriceStore.tracked(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var tgt by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val cur = remember { PriceStore.localCurrency() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Price tracker", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Watch list", "Items with a barcode are re-checked online each time you tap Refresh. You can also log prices by hand as you see them in shops.") {
                Button(enabled = !busy && items.any { PriceLogic.validBarcode(it.code) }, onClick = {
                    busy = true; msg = ""
                    scope.launch {
                        withContext(Dispatchers.IO) { PriceStore.fetchRates(ctx) }
                        val n = withContext(Dispatchers.IO) { PriceStore.refreshAll(ctx) }
                        items = PriceStore.tracked(ctx); busy = false; msg = "$n item(s) updated"
                    }
                }) { Text(if (busy) "Checking…" else "↻ Refresh online prices") }
                if (msg.isNotEmpty()) Hint(msg)
            }
        }
        items(items, key = { it.id }) { t ->
            var newP by remember { mutableStateOf("") }
            var newT by remember { mutableStateOf(if (t.target > 0) t.target.toString() else "") }
            val last = t.points.lastOrNull()?.price
            Section(t.name, "${t.cur}${if (t.code.isNotBlank()) " · barcode ${t.code}" else ""}") {
                if (last != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        BigStat("now", PriceLogic.money(last, t.cur), if (PriceLogic.hitTarget(last, t.target)) Good else Color.Unspecified)
                        if (t.target > 0) BigStat("target", PriceLogic.money(t.target, t.cur))
                        BigStat("lowest", PriceLogic.money(t.points.minOf { it.price }, t.cur))
                    }
                    if (t.points.size >= 2) LineChart(t.points.map { it.price.toFloat() }, Info, 80)
                    Hint(PriceLogic.priceTrend(t.points) + " · last logged ${d(t.points.last().t)}")
                } else Hint("No price logged yet")
                if (PriceLogic.hitTarget(last ?: 0.0, t.target)) Text("🎯 Target reached: good time to buy", color = Good, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newP, { newP = it }, Modifier.weight(1f), label = { Text("Log price seen") }, singleLine = true, keyboardOptions = numKb)
                    Button(enabled = (newP.toDoubleOrNull() ?: 0.0) > 0, onClick = { PriceStore.addPoint(ctx, t.id, newP.toDouble()); items = PriceStore.tracked(ctx); newP = "" }) { Text("Add") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newT, { newT = it }, Modifier.weight(1f), label = { Text("Target price") }, singleLine = true, keyboardOptions = numKb)
                    OutlinedButton(onClick = { PriceStore.setTarget(ctx, t.id, newT.toDoubleOrNull() ?: 0.0); items = PriceStore.tracked(ctx) }) { Text("Set") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openUrl(ctx, PriceLogic.storeLink(PriceLogic.stores[0], t.name)) }) { Text("Search stores") }
                    TextButton(onClick = { PriceStore.deleteTracked(ctx, t.id); items = PriceStore.tracked(ctx) }) { Text("Remove", color = Bad) }
                }
            }
        }
        item {
            Section("Add an item by hand") {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(price, { price = it }, Modifier.weight(1f), label = { Text("Price now ($cur)") }, singleLine = true, keyboardOptions = numKb)
                    OutlinedTextField(tgt, { tgt = it }, Modifier.weight(1f), label = { Text("Target") }, singleLine = true, keyboardOptions = numKb)
                }
                OutlinedTextField(code, { code = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), label = { Text("Barcode (optional, enables auto-refresh)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Button(enabled = name.isNotBlank(), onClick = {
                    PriceStore.addTracked(ctx, name.trim(), code, cur, tgt.toDoubleOrNull() ?: 0.0, price.toDoubleOrNull() ?: 0.0)
                    items = PriceStore.tracked(ctx); name = ""; price = ""; tgt = ""; code = ""
                }) { Text("Add to watch list") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ check

@Composable
fun PriceCheckScreen() {
    val ctx = LocalContext.current
    var price by remember { mutableStateOf("") }
    var comps by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val p = price.toDoubleOrNull()
    val list = Regex("[0-9]+(?:[.,][0-9]+)?").findAll(comps.replace(",", " ").replace("  ", " ")).mapNotNull { it.value.toDoubleOrNull() }.toList()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Suspicious listing check", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Describe the listing", "Compares the price with what similar items cost, and scans the seller's wording for scam patterns. Works fully offline.") {
                OutlinedTextField(price, { price = it }, Modifier.fillMaxWidth(), label = { Text("Price you are being asked") }, singleLine = true, keyboardOptions = numKb)
                OutlinedTextField(comps, { comps = it }, Modifier.fillMaxWidth(), label = { Text("Prices of the same item elsewhere (space separated)") }, minLines = 2)
                Hint("Tip: get 3+ prices from the Scan tab store links, Jiji, Jumia, Konga or Amazon.")
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Listing text / chat messages (optional)") }, minLines = 3)
                OutlinedButton(onClick = { text = clipboardText(ctx) }) { Text("Paste from clipboard") }
            }
        }
        if (p != null && p > 0) item { Section("Verdict") { VerdictLine(PriceLogic.judge(p, list, text)) } }
        item {
            Section("Safe-buying checklist") {
                listOf("Meet in a public place and inspect before paying.", "Never pay a 'deposit' or 'delivery fee' to someone you have not met.", "Reverse-image-search the photos: stolen photos are a top scam sign.",
                    "Prefer payment-on-delivery or escrow on the platform.", "Check the serial / IMEI before buying phones.").forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ spending

@Composable
fun PriceSpendScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(PriceStore.spends(ctx)) }
    val (_, rates) = remember { PriceStore.cachedRates(ctx) }
    var amount by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf(PriceStore.localCurrency()) }
    var cat by remember { mutableStateOf("Food") }
    var note by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30 days") }
    val n = days.substringBefore(' ').toInt()
    val pat = PriceLogic.pattern(list, n, cur, rates)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Spending patterns", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Log a purchase") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(amount, { amount = it }, Modifier.weight(1f), label = { Text("Amount") }, singleLine = true, keyboardOptions = numKb)
                    OutlinedTextField(cur, { cur = it.uppercase().take(3) }, Modifier.width(90.dp), label = { Text("Cur") }, singleLine = true)
                }
                ChipRow(PriceStore.categories, cat) { cat = it }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, singleLine = true)
                Button(enabled = (amount.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                    PriceStore.addSpend(ctx, PriceLogic.Spend(System.currentTimeMillis(), amount.toDouble(), cur, cat, note.trim())); list = PriceStore.spends(ctx); amount = ""; note = ""
                }) { Text("Add") }
            }
        }
        if (list.isNotEmpty()) {
            item { ChipRow(listOf("7 days", "30 days", "90 days"), days) { days = it } }
            item {
                Section("Summary · last $n days (in $cur)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        BigStat("total", PriceLogic.money(pat.total, cur)); BigStat("per day", PriceLogic.money(pat.perDay, cur)); BigStat("30-day pace", PriceLogic.money(pat.projectedMonth, cur))
                    }
                    pat.changePct?.let { Text("${pct(it)} vs the previous $n days", color = if (it > 10) Bad else if (it < -10) Good else Color.Unspecified, fontWeight = FontWeight.Medium) }
                    pat.topCat?.let { Text("Biggest category: $it") }
                    pat.biggest?.let { Text("Biggest single purchase: ${PriceLogic.money(it.amount, it.cur)} (${it.cat}${if (it.note.isNotBlank()) ", ${it.note}" else ""})", style = MaterialTheme.typography.bodySmall) }
                    if (rates.size <= 1) Hint("Open the Rates tab once online so mixed currencies can be converted.")
                }
            }
            item { Section("Per day") { BarChart(PriceLogic.dailyTotals(list, n.coerceAtMost(30), cur, rates).map { it.toFloat() }, Info, 90) } }
            item { Section("By category") { HBars(PriceLogic.totalsByCategory(list, System.currentTimeMillis() - n * 86_400_000L, cur, rates)) { PriceLogic.money(it, cur) } } }
            item {
                Section("Recent") {
                    list.takeLast(15).reversed().forEach { s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${d(s.t)} · ${s.cat}${if (s.note.isNotBlank()) " · ${s.note}" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(PriceLogic.money(s.amount, s.cur), fontWeight = FontWeight.Medium)
                            TextButton(onClick = { PriceStore.deleteSpend(ctx, s); list = PriceStore.spends(ctx) }) { Text("✕") }
                        }
                    }
                    OutlinedButton(onClick = { shareText(ctx, PriceStore.spendCsv(list)) }) { Text("Export CSV") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ fiat rates

@Composable
fun PriceRatesScreen() {
    val ctx = LocalContext.current
    var stamp by remember { mutableStateOf(PriceStore.cachedRates(ctx).first) }
    var rates by remember { mutableStateOf(PriceStore.cachedRates(ctx).second) }
    var status by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(PriceStore.localCurrency()) }
    var amount by remember { mutableStateOf("1") }
    var group by remember { mutableStateOf("Major") }
    var q by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }

    LaunchedEffect(auto) {
        while (auto) {
            val r = withContext(Dispatchers.IO) { PriceStore.fetchRates(ctx) }
            if (r != null) { stamp = r.first; rates = r.second; status = "Live" } else status = if (rates.size > 1) "Offline: showing saved rates" else "Offline: no rates yet"
            delay(60_000)
        }
    }
    val amt = amount.toDoubleOrNull() ?: 1.0
    val groups = PriceLogic.fiatGroups.keys.toList() + listOf("All")
    val shown = when {
        q.isNotBlank() -> rates.keys.filter { it.contains(q.trim().uppercase()) }.sorted()
        group == "All" -> rates.keys.sorted()
        else -> PriceLogic.fiatGroups[group].orEmpty().filter { rates.containsKey(it) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Exchange rates", style = MaterialTheme.typography.headlineSmall) }
        item {
            Section("Converter", if (stamp > 0) "${status.ifEmpty { "Saved" }} · updated ${d(stamp)} · refreshes every minute · ${rates.size} currencies" else status) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(amount, { amount = it }, Modifier.weight(1f), label = { Text("Amount") }, singleLine = true, keyboardOptions = numKb)
                    OutlinedTextField(base, { base = it.uppercase().take(3) }, Modifier.width(100.dp), label = { Text("From") }, singleLine = true)
                }
                if (!rates.containsKey(base)) Text("Unknown currency code", color = Warn, style = MaterialTheme.typography.bodySmall)
                SwitchRow("Auto refresh", "", auto) { auto = it }
            }
        }
        item {
            ChipRow(groups, if (q.isBlank()) group else "") { group = it; q = "" }
        }
        item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Search code (e.g. NGN)") }, singleLine = true) }
        items(shown, key = { it }) { c ->
            val v = PriceLogic.convert(amt, base, c, rates)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(c, Modifier.width(60.dp), fontWeight = FontWeight.Bold)
                Text(v?.let { PriceLogic.money(it, "") } ?: "–", Modifier.weight(1f))
                TextButton(onClick = { base = c }) { Text("use") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ crypto

@Composable
fun PriceCryptoScreen() {
    val ctx = LocalContext.current
    var cat by remember { mutableStateOf("All") }
    var coins by remember { mutableStateOf<List<PriceStore.Coin>>(emptyList()) }
    var status by remember { mutableStateOf("Loading…") }
    var stamp by remember { mutableStateOf(0L) }
    var q by remember { mutableStateOf("") }
    var vs by remember { mutableStateOf("USD") }
    var open by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("1") }
    val rates = remember { PriceStore.cachedRates(ctx).second }
    val local = remember { PriceStore.localCurrency() }
    val vsList = listOf("USD", local).distinct()
    val factor = if (vs == "USD") 1.0 else (rates[vs] ?: 1.0)

    LaunchedEffect(cat) {
        coins = emptyList(); status = "Loading…"
        while (true) {
            val id = PriceLogic.cryptoCategories.firstOrNull { it.first == cat }?.second.orEmpty()
            val r = withContext(Dispatchers.IO) { PriceStore.fetchCoins(id) }
            if (r != null) { coins = r; stamp = System.currentTimeMillis(); status = "Live" } else status = if (coins.isEmpty()) "Could not load (rate limit or offline). Retrying…" else "Offline: showing last data"
            delay(60_000)
        }
    }
    val shown = coins.filter { q.isBlank() || it.name.contains(q, true) || it.symbol.contains(q, true) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 14.dp)) {
        item { Text("Crypto market", style = MaterialTheme.typography.headlineSmall) }
        item { Hint("$status${if (stamp > 0) " · updated ${d(stamp)}" else ""} · auto-refresh every minute · data: CoinGecko public API · not financial advice") }
        item { ChipRow(PriceLogic.cryptoCategories.map { it.first }, cat) { cat = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), label = { Text("Search coin") }, singleLine = true)
                ChipRow(vsList, vs) { vs = it }
            }
        }
        items(shown, key = { it.id }) { c ->
            val price = c.price * factor
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("#${c.rank}", Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall)
                        Column(Modifier.weight(1f)) { Text(c.symbol, fontWeight = FontWeight.Bold); Text(c.name, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(PriceLogic.money(price, vs), fontWeight = FontWeight.Medium)
                            Text("24h ${pct(c.ch24)}  7d ${pct(c.ch7)}", style = MaterialTheme.typography.bodySmall, color = pctColor(c.ch24))
                        }
                        TextButton(onClick = { open = if (open == c.id) "" else c.id }) { Text(if (open == c.id) "▲" else "▼") }
                    }
                    if (open == c.id) {
                        if (c.spark.size > 2) LineChart(c.spark, if ((c.ch7 ?: 0.0) >= 0) Good else Bad, 80)
                        KV("Market cap", PriceLogic.money(c.cap * factor, vs)); KV("24h volume", PriceLogic.money(c.vol * factor, vs))
                        KV("24h range", "${PriceLogic.money(c.lo * factor, "")} – ${PriceLogic.money(c.hi * factor, vs)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(amount, { amount = it }, Modifier.width(110.dp), label = { Text("Amount") }, singleLine = true, keyboardOptions = numKb)
                            Text("${c.symbol} = ${PriceLogic.money((amount.toDoubleOrNull() ?: 0.0) * price, vs)}", fontWeight = FontWeight.Medium)
                        }
                        TextButton(onClick = { openUrl(ctx, "https://www.coingecko.com/en/coins/${c.id}") }) { Text("More on CoinGecko") }
                    }
                }
            }
        }
    }
}
