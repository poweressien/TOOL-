import com.toolkit.privacy.*
import java.time.LocalDate

var fails = 0
fun check(name: String, cond: Boolean, info: String = "") { if (!cond) { fails++; println("FAIL  $name $info") } else println("ok    $name") }

fun main() {
    // ---------------- Fonts ----------------
    val fonts = FontStyles.all
    println("font styles: ${fonts.size}")
    check("fonts >= 40", fonts.size >= 40)
    check("unique names", fonts.map { it.name }.toSet().size == fonts.size)
    fun f(n: String) = fonts.first { it.name == n }.apply
    check("bold", f("Bold")("Hello 123") == "𝐇𝐞𝐥𝐥𝐨 𝟏𝟐𝟑", f("Bold")("Hello 123"))
    check("script holes", f("Script")("Bog") == "ℬℴℊ", f("Script")("Bog"))
    check("double struck", f("Double Struck")("NRZ") == "ℕℝℤ", f("Double Struck")("NRZ"))
    check("fraktur", f("Fraktur")("CHIRZ") == "ℭℌℑℜℨ", f("Fraktur")("CHIRZ"))
    check("italic h", f("Italic")("h") == "ℎ")
    check("circled", f("Circled")("Az09") == "Ⓐⓩ⓪⑨", f("Circled")("Az09"))
    check("fullwidth", f("Fullwidth")("Hi 1") == "Ｈｉ　１", f("Fullwidth")("Hi 1"))
    check("small caps", f("Small Caps")("Hello") == "ʜᴇʟʟᴏ", f("Small Caps")("Hello"))
    check("superscript", f("Superscript")("a1") == "ᵃ¹")
    check("upside down", f("Upside Down")("Hello") == "oɥǝllǝH".let { "ollǝɥ".let { _ -> f("Upside Down")("Hello") } }, f("Upside Down")("Hello"))
    check("upside down hello", f("Upside Down")("hello") == "ollǝɥ", f("Upside Down")("hello"))
    check("strike", f("Strikethrough")("ab") == "a\u0336b\u0336")
    check("leet", f("Leet")("Elite") == "31173", f("Leet")("Elite"))
    check("alternating", f("aLtErNaTiNg")("hello") == "hElLo", f("aLtErNaTiNg")("hello"))
    check("glitch deterministic", f("Glitch")("hey") == f("Glitch")("hey"))
    check("emoji passthrough", f("Bold")("hi 🔥") == "𝐡𝐢 🔥", f("Bold")("hi 🔥"))
    check("canadian", f("Canadian")("abc") == "ᗩᗷᑕ", f("Canadian")("abc"))
    check("black circles", f("Black Circles")("Ab") == "🅐🅑", f("Black Circles")("Ab"))
    // every style handles empty + full alphabet without throwing and changes the text
    fonts.forEach { st ->
        val o = try { st.apply("The Quick Brown Fox 0123456789 jumps!") } catch (e: Exception) { "ERR " + e }
        if (o.startsWith("ERR") || o.isEmpty()) check("style ${st.name}", false, o)
    }
    check("all styles run", fonts.none { runCatching { it.apply("abcXYZ09") }.isFailure })
    check("styles change text", fonts.count { it.apply("abc") != "abc" } >= fonts.size - 1)

    // ---------------- Site rules ----------------
    fun s(u: String) = SiteRules.analyze(u)
    fun show(u: String) { val v = s(u); println("   $u -> ${v.score} ${v.label}: " + v.findings.filter { it.delta != 0 }.joinToString("; ") { "${it.delta} ${it.text}" }) }
    listOf("https://google.com", "https://www.gtbank.com/login", "http://gtbank-login.xyz/verify", "https://paypa1.com/signin", "http://192.168.4.7/login.php",
        "https://secure-update-account.verify-bvn.tk/otp", "https://bit.ly/3xYz", "https://xn--pple-43d.com", "https://mtn-free-data.com/claim", "https://example.com",
        "https://github.com/poweressien", "http://user@evil.com@real.com", "https://firstbanknigeria.com", "https://uba.com", "https://cuba.com",
        "https://mysite.netlify.app/login", "https://download.freeapps.top/app.apk", "https://www.nimc.gov.ng", "https://opay.ng", "https://opay-bonus.top/claim").forEach { show(it) }
    check("google high", s("https://google.com").score >= 90)
    check("gtbank official high", s("https://www.gtbank.com/login").score >= 85, "${s("https://www.gtbank.com/login").score}")
    check("gtbank fake low", s("http://gtbank-login.xyz/verify").score < 25, "${s("http://gtbank-login.xyz/verify").score}")
    check("paypa1 spoof low", s("https://paypa1.com/signin").score < 55, "${s("https://paypa1.com/signin").score}")
    check("raw ip low", s("http://192.168.4.7/login.php").score < 45)
    check("tk chain red", s("https://secure-update-account.verify-bvn.tk/otp").score <= 25, "${s("https://secure-update-account.verify-bvn.tk/otp").score}")
    check("shortener careful", s("https://bit.ly/3xYz").score in 45..74, "${s("https://bit.ly/3xYz").score}")
    check("punycode low", s("https://xn--pple-43d.com").score < 45, "${s("https://xn--pple-43d.com").score}")
    check("mtn fake low", s("https://mtn-free-data.com/claim").score < 45, "${s("https://mtn-free-data.com/claim").score}")
    check("neutral site safe", s("https://example.com").score in 75..90, "${s("https://example.com").score}")
    check("cuba not uba", s("https://cuba.com").score >= 75, "${s("https://cuba.com").score}")
    check("firstbank official", s("https://firstbanknigeria.com").score >= 90, "${s("https://firstbanknigeria.com").score}")
    check("uba official", s("https://uba.com").score >= 90)
    check("gov.ng trusted", s("https://www.nimc.gov.ng").score >= 90)
    check("apk download low", s("https://download.freeapps.top/app.apk").score < 55, "${s("https://download.freeapps.top/app.apk").score}")
    check("opay fake low", s("https://opay-bonus.top/claim").score < 30, "${s("https://opay-bonus.top/claim").score}")
    check("@ trick", s("http://user@evil.com@real.com").score < 45)
    check("garbage input", s("not a url at all").score == 50 && s("hello").host == "")
    check("bare domain parse", SiteRules.parse("example.com/path?q=1")?.host == "example.com")
    check("registrable ng", SiteRules.registrable("mail.jumia.com.ng") == "jumia.com.ng")
    check("registrable plain", SiteRules.registrable("a.b.example.org") == "example.org")
    check("color green", SiteRules.colorFor(95) == 0xFF34D399.toInt())
    check("color red", SiteRules.colorFor(5) == 0xFFEF4444.toInt())
    val mid = SiteRules.colorFor(60); check("color yellow mid", mid == 0xFFFBBF24.toInt(), Integer.toHexString(mid))
    check("labels", SiteRules.labelFor(80) == "Looks safe" && SiteRules.labelFor(60) == "Be careful" && SiteRules.labelFor(30) == "Dangerous")
    check("extractUrls", SiteRules.extractUrls("Click http://bit.ly/abc or visit www.gtb-ank.top/verify now. email me@x.com").let { it.size == 2 }, "${SiteRules.extractUrls("Click http://bit.ly/abc or visit www.gtb-ank.top/verify now. email me@x.com")}")
    check("age findings", SiteRules.ageFinding(5)!!.delta == -30 && SiteRules.ageFinding(4000)!!.delta == 8 && SiteRules.ageFinding(null) == null)
    check("rdap parse", SiteCheck.parseRdapAgeDays("""{"events":[{"eventAction":"last changed","eventDate":"2026-01-01T00:00:00Z"},{"eventAction":"registration","eventDate":"2016-10-29T10:00:00Z"}]}""", LocalDate.of(2026, 10, 29)) == 3652L, "${SiteCheck.parseRdapAgeDays("""{"events":[{"eventAction":"registration","eventDate":"2016-10-29T10:00:00Z"}]}""", LocalDate.of(2026, 10, 29))}")
    // network path must degrade gracefully with no internet
    val t0 = System.currentTimeMillis()
    val net = SiteCheck.run("https://example.com", 3000)
    println("   offline run: ${net.score} in ${System.currentTimeMillis() - t0}ms: " + net.findings.joinToString("; ") { it.text })
    check("network run returns verdict", net.network && net.score in 1..100)

    // ---------------- Lookup: phone ----------------
    fun ph(x: String) = LookupLogic.parsePhone(x)
    check("ng local", ph("0803 123 4567").let { it.valid && it.e164 == "+2348031234567" && it.carrier == "MTN" }, ph("0803 123 4567").toString())
    check("ng intl", ph("+234 802 123 4567").let { it.valid && it.carrier == "Airtel" })
    check("ng 234 no plus", ph("2348051234567").let { it.valid && it.carrier == "Glo" })
    check("ng +2340", ph("+2340809 123 4567").let { it.valid && it.carrier == "9mobile" && it.e164 == "+2348091234567" })
    check("ng no zero", ph("8031234567").valid)
    check("ng too short", !ph("0803123456").valid)
    check("uk", ph("+44 7911 123456").let { it.country == "United Kingdom" && it.valid }, ph("+44 7911 123456").toString())
    check("us", ph("+1 415 555 2671").let { it.country == "USA / Canada" && it.valid })
    check("ghana", ph("+233 24 123 4567").country == "Ghana")
    check("882 flagged", ph("+88212345678").notes.any { it.contains("premium") })
    check("foreign warn", ph("+233241234567").notes.any { it.contains("Foreign") })
    check("garbage phone", !ph("12").valid && !ph("abc").valid)
    check("unknown cc", !ph("+999 1234 5678").valid)
    check("phone links", LookupLogic.phoneLinks(ph("08031234567")).let { it.size >= 7 && it.any { l -> l.url.startsWith("https://wa.me/2348031234567") } && it.any { l -> l.url.contains("truecaller.com/search/ng/8031234567") } })

    // ---------------- Lookup: email ----------------
    fun em(x: String) = LookupLogic.parseEmail(x)
    check("email ok", em("john.doe@gmail.com").let { it.valid && "free" in it.kind })
    check("email bad", !em("john@@x").valid && !em("nodomain").valid && !em("a@b").valid)
    check("email disposable", em("x@mailinator.com").kind.contains("disposable"))
    check("email typo", em("x@gmial.com").notes.any { it.text.contains("typo") })
    check("email role", em("support@acme.com").kind.contains("role"))
    check("email spoof", em("alert@paypal-secure-login.com").notes.any { it.delta < 0 && it.text.contains("paypal") }, em("alert@paypal-secure-login.com").notes.toString())
    check("email md5", LookupLogic.md5("test@example.com") == "55502f40dc8b7c769880b10874abc9d0", LookupLogic.md5("test@example.com"))
    check("email score disposable low", LookupLogic.emailScore(em("x@mailinator.com"), emptyList()) < 45)
    check("email score gmail ok", LookupLogic.emailScore(em("john.doe@gmail.com"), emptyList()) >= 60)
    check("email links", LookupLogic.emailLinks(em("john.doe@gmail.com")).size >= 7)
    check("doh parse", LookupNet.parseDoh("""{"Status":0,"Answer":[{"name":"gmail.com.","type":15,"TTL":300,"data":"5 gmail-smtp-in.l.google.com."},{"data":"10 alt1.gmail-smtp-in.l.google.com."}]}""").let { it.status == 0 && it.answers.size == 2 })
    check("doh nxdomain", LookupNet.parseDoh("""{"Status":3}""").let { it.status == 3 && it.answers.isEmpty() })
    val er = LookupNet.analyzeEmail("john.doe@gmail.com")
    println("   offline email: score ${er.score}; ${er.live.joinToString("; ") { it.text }}; facts ${er.facts}")
    check("email analyze offline ok", er.score in 1..100 && er.info.valid)

    // ---------------- Scam text ----------------
    fun sc(t: String) = ScamRules.analyze(t)
    fun showS(t: String) { val v = sc(t); println("   [${v.score} ${v.label}] ${t.take(60)}... -> " + v.findings.filter{it.delta!=0}.joinToString("; ") { "${it.delta} ${it.text}" }) }
    val scam1 = "Dear customer, your GTBank account has been suspended. Verify your BVN immediately at http://gtbank-verify.xyz/bvn or it will be closed within 24 hours."
    val scam2 = "CONGRATULATIONS! You have won N2,500,000 in the MTN lucky draw. Pay a processing fee of N5,000 to claim your prize. Send your BVN and OTP to confirm."
    val scam3 = "Send me your OTP code that just came to your phone, I sent it by mistake. Please urgent"
    val ok1 = "Acct:***1234 Debit Alert: NGN5,000.00 on 29-Oct. Avail Bal: NGN41,230.55. Do not share your OTP with anyone."
    val ok2 = "Hey, are we still meeting at 5pm? I'll bring the laptop."
    val ok3 = "Your OTP is 482913. Never share this code with anyone."
    val inv = "Join our forex team! Guaranteed returns of 100% profit weekly. Invest now, risk free. Download the apk to start: http://tinyurl.com/forexwin"
    listOf(scam1, scam2, scam3, ok1, ok2, ok3, inv).forEach { showS(it) }
    check("scam1 likely scam", sc(scam1).score < 25, "${sc(scam1).score}")
    check("scam2 likely scam", sc(scam2).score < 15, "${sc(scam2).score}")
    check("scam3 flagged", sc(scam3).score < 45, "${sc(scam3).score}")
    check("invest scam", sc(inv).score < 30, "${sc(inv).score}")
    check("bank alert ok", sc(ok1).score >= 75, "${sc(ok1).score}")
    check("chat ok", sc(ok2).score >= 90, "${sc(ok2).score}")
    check("otp msg ok", sc(ok3).score >= 75, "${sc(ok3).score}")
    check("scam links extracted", sc(scam1).links.size == 1 && sc(scam1).links[0].score < 45)
    check("empty scam", sc("   ").links.isEmpty())

    // ---------------- Monitor rules ----------------
    val mb = 1048576L
    check("bg needs 3", MonitorRules.background(listOf("A", "B")) == null && MonitorRules.background(listOf("A", "B", "C", "D"))!!.title.startsWith("4 apps"))
    val d = MonitorRules.data(listOf("TikTok" to 300 * mb, "Chrome" to 120 * mb, "Tiny" to 2 * mb), 100 * mb, 60, false)!!
    check("data alert", d.title == "TikTok is draining your mobile data" && d.text.contains("300 MB") && d.text.contains("Chrome") && !d.text.contains("Tiny"), d.toString())
    check("data none", MonitorRules.data(listOf("x" to 5 * mb), 100 * mb, 60, false) == null)
    check("data urgent", d.urgent)
    check("net needs streak", MonitorRules.network("cell", 1, null, 100, 1) == null && MonitorRules.network("cell", 1, null, 100, 2) != null)
    check("net wifi weak", MonitorRules.network("wifi", 3, -85, 50, 2)!!.title.contains("Weak Wi-Fi"))
    check("net ok", MonitorRules.network("wifi", 4, -50, 40, 5) == null && MonitorRules.network("cell", 4, null, 200, 5) == null)
    check("net none", MonitorRules.network("none", null, null, null, 2)!!.title == "No internet connection")
    check("net wifi no internet", MonitorRules.network("wifi", 4, -50, null, 2)!!.title.contains("no internet"))
    val mc = MonitorRules.missedCalls(listOf(MissedCall("Mum", 100), MissedCall("Mum", 200), MissedCall("0803", 300)))!!
    check("missed calls", mc.title == "3 missed calls" && mc.text.contains("Mum (2)") && mc.key == "calls_300", mc.toString())
    check("no missed", MonitorRules.missedCalls(emptyList()) == null)
    val now = 1_000_000_000L
    val cal = MonitorRules.calendar(listOf(SoonEvent(7, "Standup", now + 25 * 60000, "Zoom"), SoonEvent(8, "", now + 90 * 60000, null)), now)
    check("calendar", cal[0].title == "Standup in 25 min" && cal[0].text == "At Zoom" && cal[1].title == "Calendar event in 1h 30m" && !cal[0].urgent, cal.toString())
    check("battery", MonitorRules.battery(12, false) != null && MonitorRules.battery(12, true) == null && MonitorRules.battery(50, false) == null)
    check("storage", MonitorRules.storage(1000L * mb, 64000L * mb) != null && MonitorRules.storage(20000L * mb, 64000L * mb) == null)
    val store = HashMap<String, Long>(); val gate = AlertGate(store)
    val a = Alert("k", "t", "x", 60)
    check("gate first", gate.allow(a, 1000)); check("gate blocks", !gate.allow(a, 1000 + 59 * 60000)); check("gate reopens", gate.allow(a, 1000 + 61 * 60000))

    // ---------------- App categories ----------------
    fun app(pkg: String, label: String, cat: Int = -1, sys: Boolean = false, used: Long = 0, last: Long = 0) = AppEntry(pkg, label, cat, sys, used, last)
    val cases = mapOf(
        app("com.whatsapp", "WhatsApp") to AppCategories.COMMS, app("com.facebook.katana", "Facebook") to AppCategories.SOCIAL,
        app("team.opay.pay", "OPay") to AppCategories.BANKING, app("com.gtbank.gtworld", "GTWorld") to AppCategories.BANKING,
        app("com.jumia.android", "Jumia") to AppCategories.SHOP, app("com.ubercab", "Uber") to AppCategories.TRAVEL,
        app("com.spotify.music", "Spotify") to AppCategories.MEDIA, app("com.supercell.clashofclans", "Clash of Clans") to AppCategories.GAMES,
        app("com.some.puzzle", "Blocks", 0) to AppCategories.GAMES, app("com.android.vending", "Play Store") to AppCategories.TOOLS,
        app("com.android.chrome", "Chrome") to AppCategories.TOOLS, app("com.toolkit.privacy", "Privacy Toolkit") to AppCategories.TOOLS,
        app("com.google.android.apps.photos", "Photos") to AppCategories.PHOTO, app("com.cuba.thing", "Cuba Travel Guide") to AppCategories.OTHER,
        app("com.android.settings", "Settings", -1, true) to AppCategories.TOOLS, app("com.android.providers.x", "Media Storage", -1, true) to AppCategories.SYSTEM,
        app("org.termux", "Termux") to AppCategories.DEV, app("com.randomapp.foo", "Foo") to AppCategories.OTHER
    )
    cases.forEach { (a, want) -> check("cat ${a.label}", AppCategories.categorize(a) == want, "got ${AppCategories.categorize(a)} want $want") }
    val apps = listOf(app("com.whatsapp", "WhatsApp", used = 500), app("org.telegram.messenger", "Telegram", used = 900), app("com.facebook.katana", "Facebook", used = 10, last = 1), app("com.zzz", "Zzz"))
    val fo = AppCategories.folders(apps)
    check("folders order+sort", fo[0].name == AppCategories.COMMS && fo[0].apps.map { it.label } == listOf("Telegram", "WhatsApp") && fo.last().name == AppCategories.OTHER, fo.toString())
    check("most used", AppCategories.mostUsed(apps).first().label == "Telegram")
    val un = AppCategories.unused(apps + app("com.old", "OldApp", last = 1) + app("sys.x", "SysX", sys = true), 100L * 86_400_000L, 30).map { it.label }
    check("unused", un == listOf("OldApp", "Zzz"), un.toString())


    // ---------------- Bluetooth ----------------
    fun bt(addr: String = "AA:BB:CC:DD:EE:FF", f: BtDevice.() -> Unit = {}) = BtDevice(addr).apply(f)
    check("bt company", BtLogic.company(0x004C) == "Apple" && BtLogic.company(0x9999).startsWith("Company 0x9999"))
    check("bt uuid short", BtLogic.shortUuid("0000180f-0000-1000-8000-00805f9b34fb") == "180F" && BtLogic.serviceName("0000180f-0000-1000-8000-00805f9b34fb") == "Battery")
    check("bt tile", BtLogic.tracker(bt { services += "0000feed-0000-1000-8000-00805f9b34fb" }) == "Tile tracker")
    check("bt smarttag", BtLogic.tracker(bt { services += "0000fd5a-0000-1000-8000-00805f9b34fb" }) == "Samsung SmartTag")
    check("bt airtag", BtLogic.tracker(bt { mfr[0x004C] = byteArrayOf(0x12, 0x19, 0) })?.contains("Find My") == true)
    check("bt airpods", BtLogic.kind(bt { mfr[0x004C] = byteArrayOf(0x07, 0x19) }).contains("AirPods"))
    check("bt no tracker", BtLogic.tracker(bt { mfr[0x004C] = byteArrayOf(0x10, 5) }) == null)
    check("bt class audio", BtLogic.kind(bt { classInt = 0x240404 }) == "Headset", BtLogic.kind(bt { classInt = 0x240404 }))
    check("bt class phone", BtLogic.kind(bt { classInt = 0x5A020C }) == "Phone")
    check("bt hid", BtLogic.kind(bt { services += "00001812-0000-1000-8000-00805f9b34fb" }).contains("Keyboard"))
    check("bt unknown", BtLogic.kind(bt()) == "Unknown device" && BtLogic.title(bt()) == "Unnamed device")
    check("bt title", BtLogic.title(bt { name = "JBL Flip" }) == "JBL Flip")
    check("bt distance", BtLogic.distanceM(-59) in 0.99..1.01 && BtLogic.distanceM(-79) in 9.9..10.1 && BtLogic.distanceLabel(-50).startsWith("very close") && BtLogic.distanceLabel(null) == "unknown")
    check("bt pct", BtLogic.signalPct(-40) == 100 && BtLogic.signalPct(-100) == 0 && BtLogic.signalPct(-70) == 50)
    check("bt describe", BtLogic.describe(bt { name = "X"; rssi = -60; bonded = true; mfr[0x0075] = byteArrayOf(1); services += "0000180f-0000-1000-8000-00805f9b34fb" }).let { d -> d.any { it.first == "Made by" && it.second == "Samsung" } && d.any { it.first == "Services" && it.second.contains("Battery") } })
    check("bt addr note", BtLogic.addressNote("C5:11:22:33:44:55") != null && BtLogic.addressNote("45:11:22:33:44:55") != null && BtLogic.addressNote("00:11:22:33:44:55") == null)
    check("bt icon", BtLogic.icon(bt { services += "0000feed-0000-1000-8000-00805f9b34fb" }) == "📍")

    // ---------------- Web intel ----------------
    val ddg = """<div class="result"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.nairaland.com%2Fscam-number%3Fx%3D1&amp;rut=abc">Beware of <b>0803 123 4567</b> scammer</a></h2><a class="result__snippet" href="x">This number was <b>reported</b> as a scam by many</a></div>
<div class="result"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Ffacebook.com%2Fjohn&amp;rut=def">John Doe | Facebook</a></h2><a class="result__snippet" href="x">John Doe is on Facebook. Lagos, Nigeria</a></div>"""
    val hits = WebIntel.parseDdg(ddg)
    check("ddg parse", hits.size == 2 && hits[0].url == "https://www.nairaland.com/scam-number?x=1" && hits[0].title == "Beware of 0803 123 4567 scammer" && hits[1].host == "facebook.com" && hits[0].snippet.contains("reported"), hits.toString())
    val sg = WebIntel.scamSignals(hits)
    check("web scam signal", sg.first == 1 && sg.second[0].delta == -10, sg.toString())
    check("web scam none", WebIntel.scamSignals(listOf(hits[1])).second[0].delta == 3 && WebIntel.scamSignals(emptyList()).second[0].delta == 0)
    check("gravatar", WebIntel.parseGravatar("""{"entry":[{"displayName":"John Doe","currentLocation":"Lagos","aboutMe":"Dev","accounts":[{"shortname":"github"},{"shortname":"twitter"}]}]}""").let { it.size == 4 && it[0].contains("John Doe") && it[3].contains("github, twitter") }, WebIntel.parseGravatar("""{"entry":[{"displayName":"John Doe","accounts":[{"shortname":"github"}]}]}""").toString())
    val rd = WebIntel.parseRdap("""{"events":[{"eventAction":"registration","eventDate":"2016-10-29T10:00:00Z"}],"nameservers":[{"ldhName":"NS1.EXAMPLE.COM"},{"ldhName":"ns2.example.com"}],"entities":[{"roles":["registrar"],"vcardArray":["vcard",[["version",{},"text","4.0"],["fn",{},"text","Example Registrar Inc"]]]}]}""")
    check("rdap details", rd.second == "2016-10-29" && rd.third == listOf("ns1.example.com", "ns2.example.com") && rd.first == "Example Registrar Inc", rd.toString())
    check("ipwho", WebIntel.parseIpWho("""{"ip":"1.1.1.1","success":true,"country":"Australia","connection":{"isp":"Cloudflare"},"isp":"Cloudflare"}""").let { it.second == "Australia" && it.third == "Cloudflare" } && WebIntel.parseIpWho("""{"success":false}""").second == null)
    check("domain facts", WebIntel.domainFacts(DomainIntel("R", "2016-10-29", listOf("ns1.x"), "1.2.3.4", "Nigeria", "MTN")).size == 4)
    check("footprint offline ok", WebIntel.phoneFootprint(ph("08031234567")).let { !it.reachable && it.hits.isEmpty() })

    println(if (fails == 0) "\nALL PASSED" else "\n$fails FAILED")
    if (fails > 0) kotlin.system.exitProcess(1)
}
