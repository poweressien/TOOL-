package com.toolkit.privacy

data class AppEntry(
    val pkg: String,
    val label: String,
    val androidCategory: Int,   // ApplicationInfo.CATEGORY_* or -1
    val system: Boolean,
    val usedMs7d: Long = 0,
    val lastUsedMs: Long = 0
)

/** Sorts installed apps into sensible folders. Local knowledge first (Nigerian banks, fintech, telcos), then Android's own category. */
object AppCategories {
    const val BANKING = "Banking & Money"
    const val COMMS = "Calls & Chat"
    const val SOCIAL = "Social"
    const val MEDIA = "Music & Video"
    const val GAMES = "Games"
    const val SHOP = "Shopping & Food"
    const val TRAVEL = "Maps & Rides"
    const val WORK = "Work & Study"
    const val PHOTO = "Camera & Photos"
    const val TOOLS = "Tools & Security"
    const val DEV = "Developer"
    const val NEWS = "News & Reading"
    const val SYSTEM = "System"
    const val OTHER = "Other"

    /** Folder display order. */
    val ORDER = listOf(COMMS, SOCIAL, BANKING, SHOP, TRAVEL, MEDIA, PHOTO, WORK, NEWS, GAMES, TOOLS, DEV, OTHER, SYSTEM)

    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        BANKING to listOf("bank", "opay", "palmpay", "moniepoint", "kuda", "paystack", "flutterwave", "piggyvest", "cowrywise", "carbon",
            "gtbank", "gtworld", "zenith", "accessbank", "access.more", "firstmobile", "fbn", "ubagroup", ".uba.", "wema", "alat", "sterling", "fcmb", "ecobank",
            "stanbic", "fidelity", "polaris", "unionbank", "keystone", "paga", "chipper", "payday", "paypal", "wallet", "bet9ja", "sportybet", "nairabet",
            "binance", "coinbase", "trustwallet", "luno", "quidax", "bybit", "remitly", "westernunion", "fintech", "loan", "credit", "mtn.momo", "momo"),
        COMMS to listOf("dialer", "contacts", "whatsapp", "telegram", "messenger", "signal", "messaging", "mms", "sms", "truecaller", "gmail", "outlook",
            "mail", "viber", "imo", "skype", "zoom", "meet", "duo", "teams", "discord", "slack"),
        SOCIAL to listOf("facebook", "instagram", "tiktok", "twitter", "com.x.", "snapchat", "linkedin", "threads", "reddit", "pinterest", "tumblr", "likee", "kwai", "sharechat", "lite.fb"),
        MEDIA to listOf("youtube", "spotify", "netflix", "audiomack", "boomplay", "showmax", "deezer", "vlc", "mxplayer", "music", "player", "video", "radio", "podcast", "soundcloud", "primevideo", "iroko", "tubi", "shazam"),
        SHOP to listOf("jumia", "konga", "temu", "aliexpress", "shein", "amazon", "olist", "glovo", "chowdeck", "foodcourt", "ubereats", "kilimall", "alibaba", "ebay", "jiji", "shop", "store", "food", "delivery"),
        TRAVEL to listOf("maps", "waze", "uber", "bolt", "indrive", "lagride", "gokada", "taxi", "ride", "airline", "flight", "booking", "airbnb", "transit", "navigation", "gps"),
        PHOTO to listOf("camera", "gallery", "photos", "snapseed", "capcut", "inshot", "picsart", "vsco", "lightroom", "kinemaster", "videoeditor", "photoeditor", "canva", "remini"),
        WORK to listOf("office", "docs", "sheets", "slides", "drive", "notion", "word", "excel", "powerpoint", "pdf", "calendar", "classroom", "duolingo", "khan", "udemy", "coursera", "notes", "onedrive", "dropbox", "scanner", "wps", "evernote", "todo", "tasks"),
        NEWS to listOf("news", "bbc", "cnn", "legit.ng", "punch", "vanguard", "kindle", "wattpad", "reader", "books", "bible", "quran", "pocket", "feedly", "medium"),
        TOOLS to listOf("vpn", "cleaner", "booster", "calculator", "files", "filemanager", "settings", "torch", "flashlight", "clock", "weather", "compass", "antivirus", "security", "privacy", "toolkit", "battery", "wifi", "speedtest", "qr", "barcode", "scanner", "translate", "keyboard", "launcher", "usb", "backup", "clone", "shareit", "xender", "files.go", "chrome", "firefox", "browser", "opera", "brave", "vending", "playstore", "play store"),
        DEV to listOf("termux", "github", "gitlab", "androidide", "acode", "sketchware", "mobile.dev", "devtools", "jsrunner", "ssh", "python", "kotlin")
    )

    private val GAME_HINTS = listOf(".game", "games", "puzzle", "racing", "ludo", "chess", "pubg", "freefire", "codm", "roblox", "minecraft", "subway", "candycrush", "efootball", "fifa", "dreamleague", "clash", "pes", "carrom", "snake", "2048", "solitaire", "tetris", "mobilelegends", "legends")

    private val EXACT = mapOf(
        "com.android.vending" to TOOLS, "com.google.android.apps.maps" to TRAVEL, "com.google.android.gm" to COMMS,
        "com.android.chrome" to TOOLS, "com.google.android.googlequicksearchbox" to TOOLS, "com.google.android.apps.photos" to PHOTO
    )

    fun categorize(a: AppEntry): String {
        EXACT[a.pkg]?.let { return it }
        val id = (a.pkg + " " + a.label).lowercase()
        if (a.androidCategory == 0 || GAME_HINTS.any { id.contains(it) }) return GAMES
        for ((cat, words) in KEYWORDS) if (words.any { id.contains(it) }) return cat
        val byAndroid = when (a.androidCategory) {
            1, 2 -> MEDIA        // audio, video
            3 -> PHOTO           // image
            4 -> SOCIAL
            5 -> NEWS
            6 -> TRAVEL          // maps
            7 -> WORK            // productivity
            8 -> TOOLS           // accessibility
            else -> null
        }
        if (byAndroid != null) return byAndroid
        return if (a.system) SYSTEM else OTHER
    }

    data class Folder(val name: String, val apps: List<AppEntry>)

    /** Folders in a sensible order; inside each, most used first then A-Z. Empty folders are dropped. */
    fun folders(apps: List<AppEntry>): List<Folder> {
        val by = apps.groupBy { categorize(it) }
        return ORDER.mapNotNull { name ->
            by[name]?.let { list -> Folder(name, list.sortedWith(compareByDescending<AppEntry> { it.usedMs7d }.thenBy { it.label.lowercase() })) }
        }
    }

    fun alphabetical(apps: List<AppEntry>) = apps.sortedBy { it.label.lowercase() }
    fun mostUsed(apps: List<AppEntry>) = apps.filter { it.usedMs7d > 0 }.sortedByDescending { it.usedMs7d }

    /** Apps not opened for `days` days (never-used counts). Excludes system apps. */
    fun unused(apps: List<AppEntry>, nowMs: Long, days: Int = 30) =
        apps.filter { !it.system && it.usedMs7d == 0L && (it.lastUsedMs == 0L || nowMs - it.lastUsedMs > days * 86_400_000L) }.sortedBy { it.label.lowercase() }
}
