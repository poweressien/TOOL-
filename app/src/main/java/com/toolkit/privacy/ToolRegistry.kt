package com.toolkit.privacy

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** Text handed to a tool by the Share sheet or a notification. */
class Pending {
    var url by mutableStateOf<String?>(null)
    var text by mutableStateOf<String?>(null)
}

/**
 * One app tile on the home screen.
 * - Privacy Toolkit tools show their Compose [content] inside the main screen. [tab] and [sub] are the old tab numbers,
 *   kept so notifications, the Share sheet and the bubble still land on the right tool.
 * - EKPE tools open the EKPE screen ([ekpeTab], and [ekpeTool] to jump straight into one tool of Guard or Labs).
 */
class ToolDef(
    val id: String,
    val name: String,
    val section: String,
    val icon: ImageVector,
    val keywords: String = "",
    val tab: Int = -1,
    val sub: Int = -1,
    val ekpeTab: Int = -1,
    val ekpeTool: String? = null,
    val content: (@Composable (Pending) -> Unit)? = null
) {
    val isEkpe: Boolean get() = ekpeTab >= 0
}

object ToolRegistry {
    const val S_FILES = "Files"
    const val S_NET = "Network"
    const val S_SEC = "Security"
    const val S_ASSIST = "Assist"
    const val S_TOOLS = "Tools"
    const val S_BUILD = "Build & See"
    const val S_BIO = "BioTrack"
    const val S_PRICE = "PriceRadar"
    const val S_RADIO = "Radio"
    const val S_GEO = "GeoSense"
    const val S_EKPE = "EKPE"
    const val S_GUARD = "Guard"
    const val S_LABS = "Labs"

    val sections = listOf(S_FILES, S_NET, S_SEC, S_ASSIST, S_TOOLS, S_BUILD, S_BIO, S_PRICE, S_RADIO, S_GEO, S_EKPE, S_GUARD, S_LABS)

    val all: List<ToolDef> = listOf(
        // ---- Files (old tab 1)
        ToolDef("files_compress", "Compress", S_FILES, Icons.Rounded.Archive, "zip gzip xz photo video smaller", 1, 0) { CompressScreen() },
        ToolDef("files_pdf", "PDF", S_FILES, Icons.Rounded.PictureAsPdf, "images merge split", 1, 1) { PdfScreen() },
        ToolDef("files_encrypt", "Encrypt", S_FILES, Icons.Rounded.EnhancedEncryption, "aes shred lock file", 1, 2) { EncryptScreen() },
        ToolDef("files_exif", "Strip EXIF", S_FILES, Icons.Rounded.FilterNone, "metadata gps photo privacy", 1, 3) { MetadataScreen() },
        ToolDef("files_ocr", "OCR", S_FILES, Icons.Rounded.TextFields, "text from image scan", 1, 4) { OcrScreen() },
        ToolDef("files_cleaner", "Cleaner", S_FILES, Icons.Rounded.DeleteSweep, "duplicates big old files storage", 1, 5) { CleanerScreen() },
        ToolDef("files_smart", "Smart Files", S_FILES, Icons.Rounded.FolderSpecial, "file manager intelligent", 1, 6) { SmartFilesScreen() },
        // ---- Network (old tab 2)
        ToolDef("net_signal", "Signal Meter", S_NET, Icons.Rounded.SignalCellularAlt, "bars percent mobile", 2, 0) { SignalScreen() },
        ToolDef("net_speed", "Speed Test", S_NET, Icons.Rounded.Speed, "download upload jitter", 2, 1) { SpeedScreen() },
        ToolDef("net_wifi", "Wi-Fi Analyzer", S_NET, Icons.Rounded.Wifi, "channel graph best channel", 2, 2) { WifiAnalyzerScreen() },
        ToolDef("net_toolbox", "Toolbox", S_NET, Icons.Rounded.Build, "ping traceroute dns whois port", 2, 3) { ToolboxScreen() },
        ToolDef("net_data", "Data Usage", S_NET, Icons.Rounded.DataUsage, "per app wifi mobile", 2, 4) { DataUsageScreen() },
        ToolDef("net_lan", "LAN Scanner", S_NET, Icons.Rounded.Router, "devices open ports", 2, 5) { LanScreen() },
        ToolDef("net_wifipro", "Wi-Fi Pro", S_NET, Icons.Rounded.NetworkWifi, "bssid gateway interference evil twin password", 2, 6) { WifiProScreen() },
        ToolDef("net_intel", "Site Intel", S_NET, Icons.Rounded.Public, "domain ip whois dns tls headers", 2, 7) { IntelScreen() },
        // ---- Security (old tab 3)
        ToolDef("sec_vpn", "VPN", S_SEC, Icons.Rounded.VpnLock, "wireguard tunnel", 3, 0) { VpnScreen() },
        ToolDef("sec_shield", "DNS Shield", S_SEC, Icons.Rounded.Security, "tracker blocker ads", 3, 1) { ShieldScreen() },
        ToolDef("sec_audit", "App Audit", S_SEC, Icons.Rounded.Policy, "permissions trackers apps", 3, 2) { AuditScreen() },
        ToolDef("sec_passwords", "Passwords", S_SEC, Icons.Rounded.VpnKey, "generator strength breach", 3, 3) { PasswordsScreen() },
        ToolDef("sec_vault", "Vault", S_SEC, Icons.Rounded.Lock, "encrypted passwords fingerprint", 3, 4) { VaultScreen() },
        // ---- Assist (old tab 5)
        ToolDef("as_siteguard", "Site Guard", S_ASSIST, Icons.Rounded.VerifiedUser, "website safety bubble link", 5, 0) { p -> SiteGuardScreen(p.url) { p.url = null } },
        ToolDef("as_scan", "Smart Scan", S_ASSIST, Icons.Rounded.Search, "lookup phone email number", 5, 1) { LookupScreen() },
        ToolDef("as_scam", "Scam Check", S_ASSIST, Icons.Rounded.Report, "sms whatsapp fake bank otp", 5, 2) { p -> ScamScreen(p.text) { p.text = null } },
        ToolDef("as_monitor", "Device Monitor", S_ASSIST, Icons.Rounded.Visibility, "alerts background battery storage calls", 5, 3) { MonitorScreen() },
        ToolDef("as_home", "Home Organizer", S_ASSIST, Icons.Rounded.Dashboard, "launcher folders apps unused", 5, 4) { OrganizerScreen() },
        ToolDef("as_bluetooth", "Bluetooth", S_ASSIST, Icons.Rounded.Bluetooth, "scanner devices ble", 5, 5) { BluetoothScreen() },
        // ---- Tools (old tab 4)
        ToolDef("tl_qr", "QR", S_TOOLS, Icons.Rounded.CropFree, "scan generate wifi qr barcode", 4, 0) { QrScreen() },
        ToolDef("tl_sensors", "Sensors", S_TOOLS, Icons.Rounded.Explore, "compass level barometer light magnetic", 4, 1) { SensorLabScreen() },
        ToolDef("tl_api", "API Tester", S_TOOLS, Icons.Rounded.Http, "http request rest", 4, 2) { ApiTesterScreen() },
        ToolDef("tl_dev", "Dev Utils", S_TOOLS, Icons.Rounded.DeveloperMode, "json base64 hash url color uuid timestamp cidr", 4, 3) { DevUtilsScreen() },
        ToolDef("tl_fonts", "Fonts", S_TOOLS, Icons.Rounded.FontDownload, "fancy unicode styles copy", 4, 4) { FontsScreen() },
        ToolDef("tl_info", "Device Info", S_TOOLS, Icons.Rounded.Info, "hardware battery", 4, 5) { DeviceInfoScreen() },
        ToolDef("tl_location", "Mock Location", S_TOOLS, Icons.Rounded.MyLocation, "gps test fake location", 4, 6) { MockLocationScreen() },
        ToolDef("tl_profile", "Device Profile", S_TOOLS, Icons.Rounded.PhoneAndroid, "preview screen", 4, 7) { DeviceProfileScreen() },
        ToolDef("tl_sensitive", "Sensitive Scan", S_TOOLS, Icons.Rounded.FindInPage, "secrets keys leak text", 4, 8) { p -> SensitiveScreen(p.text) },
        ToolDef("tl_sensorplus", "Sensor+", S_TOOLS, Icons.Rounded.Vibration, "metal detector vibration light altimeter tone", 4, 9) { SensorPlusScreen() },
        // ---- Build & See (old tabs 0 and 9)
        ToolDef("run_html", "HTML Runner", S_BUILD, Icons.Rounded.Web, "localhost run html console", 0, 0) { HtmlRunnerScreen() },
        ToolDef("lens_main", "RealityLens", S_BUILD, Icons.Rounded.CenterFocusStrong, "camera recognise translate plant object", 9, 0) { RealityLensScreen() },
        // ---- BioTrack (old tab 6)
        ToolDef("bio_today", "Bio Today", S_BIO, Icons.Rounded.Today, "steps daily", 6, 0) { BioTodayScreen() },
        ToolDef("bio_heart", "Heart Rate", S_BIO, Icons.Rounded.Favorite, "pulse camera ppg", 6, 1) { BioHeartScreen() },
        ToolDef("bio_sleep", "Sleep", S_BIO, Icons.Rounded.NightsStay, "tracker smart alarm night", 6, 2) { BioSleepScreen() },
        ToolDef("bio_trends", "Bio Trends", S_BIO, Icons.Rounded.BarChart, "history charts", 6, 3) { BioTrendsScreen() },
        // ---- PriceRadar (old tab 7)
        ToolDef("pr_scan", "Price Scan", S_PRICE, Icons.Rounded.CameraAlt, "barcode shelf", 7, 0) { PriceScanScreen() },
        ToolDef("pr_track", "Price Track", S_PRICE, Icons.Rounded.LocalOffer, "watch items", 7, 1) { PriceTrackScreen() },
        ToolDef("pr_check", "Price Check", S_PRICE, Icons.Rounded.CompareArrows, "compare deal", 7, 2) { PriceCheckScreen() },
        ToolDef("pr_spend", "Spending", S_PRICE, Icons.Rounded.AccountBalanceWallet, "budget expenses", 7, 3) { PriceSpendScreen() },
        ToolDef("pr_rates", "Rates", S_PRICE, Icons.Rounded.SwapHoriz, "currency fiat exchange naira dollar", 7, 4) { PriceRatesScreen() },
        ToolDef("pr_crypto", "Crypto", S_PRICE, Icons.Rounded.MonetizationOn, "bitcoin coins prices", 7, 5) { PriceCryptoScreen() },
        // ---- Radio (old tab 8)
        ToolDef("rd_spectrum", "Spectrum", S_RADIO, Icons.Rounded.GraphicEq, "frequency analyzer", 8, 0) { RadioSpectrumScreen() },
        ToolDef("rd_wifi", "Radio Wi-Fi", S_RADIO, Icons.Rounded.PermScanWifi, "access points signal", 8, 1) { RadioWifiScreen() },
        ToolDef("rd_ble", "BLE Scanner", S_RADIO, Icons.Rounded.BluetoothSearching, "bluetooth low energy", 8, 2) { RadioBleScreen() },
        ToolDef("rd_sdr", "SDR", S_RADIO, Icons.Rounded.Radio, "rtl_tcp software defined radio", 8, 3) { RadioSdrScreen() },
        ToolDef("rd_env", "Env Sensors", S_RADIO, Icons.Rounded.WbSunny, "temperature pressure humidity light", 8, 4) { RadioEnvScreen() },
        ToolDef("rd_noise", "Noise Meter", S_RADIO, Icons.Rounded.Mic, "decibel sound level", 8, 5) { RadioNoiseScreen() },
        ToolDef("rd_alerts", "Env Alerts", S_RADIO, Icons.Rounded.NotificationsActive, "noise heat air alerts", 8, 6) { RadioAlertsScreen() },
        ToolDef("rd_air", "Air Quality", S_RADIO, Icons.Rounded.Cloud, "aqi pollution", 8, 7) { RadioAirScreen() },
        // ---- GeoSense (old tab 10)
        ToolDef("geo_places", "Places", S_GEO, Icons.Rounded.Place, "saved places alerts", 10, 0) { GeoPlacesScreen() },
        ToolDef("geo_trips", "Journeys", S_GEO, Icons.Rounded.Timeline, "trips record gpx", 10, 1) { GeoTripsScreen() },
        ToolDef("geo_parking", "Parking", S_GEO, Icons.Rounded.LocalParking, "car meter", 10, 2) { GeoParkingScreen() },
        ToolDef("geo_reminders", "Reminders", S_GEO, Icons.Rounded.AlarmOn, "location reminders", 10, 3) { GeoRemindersScreen() },
        // ---- EKPE suite (opens the EKPE screen)
        ToolDef("e_analyzer", "Analyzer", S_EKPE, Icons.Rounded.InsertChart, "signal speed wifi graphs", ekpeTab = 0),
        ToolDef("e_intel", "Entity Intel", S_EKPE, Icons.Rounded.Fingerprint, "supersmart phone email domain username osint", ekpeTab = 1),
        ToolDef("e_split", "Split & Windows", S_EKPE, Icons.Rounded.VerticalSplit, "split screen floating windows browser notes", ekpeTab = 2),
        ToolDef("e_fonts", "Font Changer", S_EKPE, Icons.Rounded.TextFormat, "system font style", ekpeTab = 3),
        ToolDef("e_cloud", "Cloud Backup", S_EKPE, Icons.Rounded.CloudUpload, "google drive gallery watcher photos", ekpeTab = 4),
        ToolDef("e_clock", "Floating Clock", S_EKPE, Icons.Rounded.AccessTime, "overlay time", ekpeTab = 5),
        ToolDef("e_tools", "Extra Tools", S_EKPE, Icons.Rounded.Extension, "torch device info duplicate contacts", ekpeTab = 6),
        ToolDef("e_radar", "Radar", S_EKPE, Icons.Rounded.TrackChanges, "cell towers wifi bluetooth field meter", ekpeTab = 7),
        // ---- EKPE Guard
        ToolDef("g_lan", "LAN Guard", S_GUARD, Icons.Rounded.Devices, "devices on wifi new device alerts", ekpeTab = 8, ekpeTool = "LAN Guard"),
        ToolDef("g_bug", "Bug Sweep", S_GUARD, Icons.Rounded.BugReport, "hidden camera listening device", ekpeTab = 8, ekpeTool = "Bug Sweep"),
        ToolDef("g_finder", "Device Finder", S_GUARD, Icons.Rounded.NearMe, "hot cold lost earbuds tracker", ekpeTab = 8, ekpeTool = "Device Finder"),
        ToolDef("g_net", "Network Toolkit", S_GUARD, Icons.Rounded.NetworkCheck, "speed ping traceroute dns port", ekpeTab = 8, ekpeTool = "Network Toolkit"),
        ToolDef("g_audit", "App Privacy Audit", S_GUARD, Icons.Rounded.Apps, "dangerous permissions trackers", ekpeTab = 8, ekpeTool = "App Privacy Audit"),
        ToolDef("g_vault", "Private Vault", S_GUARD, Icons.Rounded.Https, "encrypted photos files notes", ekpeTab = 8, ekpeTool = "Private Vault"),
        ToolDef("g_sound", "Sound Lab", S_GUARD, Icons.Rounded.Equalizer, "decibel spectrum noise alarm", ekpeTab = 8, ekpeTool = "Sound Lab"),
        // ---- EKPE Labs
        ToolDef("l_cov", "Coverage Mapper", S_LABS, Icons.Rounded.Map, "signal heatmap carrier", ekpeTab = 9, ekpeTool = "Coverage Mapper"),
        ToolDef("l_tower", "Tower Locator", S_LABS, Icons.Rounded.CellWifi, "opencellid distance bearing", ekpeTab = 9, ekpeTool = "Tower Locator"),
        ToolDef("l_ai", "Offline AI", S_LABS, Icons.Rounded.Memory, "mediapipe llm gemma", ekpeTab = 9, ekpeTool = "Offline AI"),
        ToolDef("l_auto", "Automation Rules", S_LABS, Icons.Rounded.Autorenew, "triggers actions", ekpeTab = 9, ekpeTool = "Automation Rules"),
        ToolDef("l_script", "Script Lab", S_LABS, Icons.Rounded.Code, "python console", ekpeTab = 9, ekpeTool = "Script Lab"),
        ToolDef("l_share", "Phone-to-Phone Share", S_LABS, Icons.Rounded.Share, "wifi direct files chat", ekpeTab = 9, ekpeTool = "Phone-to-Phone Share"),
        ToolDef("l_forensics", "Media Forensics", S_LABS, Icons.Rounded.ImageSearch, "exif ela tamper", ekpeTab = 9, ekpeTool = "Media Forensics"),
        ToolDef("l_money", "Money Tracker", S_LABS, Icons.Rounded.Money, "sms bank opay moniepoint palmpay ledger", ekpeTab = 9, ekpeTool = "Money Tracker")
    )

    fun bySection(s: String): List<ToolDef> = all.filter { it.section == s }

    fun byId(id: String): ToolDef? = all.firstOrNull { it.id == id }

    /** Old (tab, sub) address -> tool. Falls back to the first tool of that tab. */
    fun byRoute(tab: Int, sub: Int): ToolDef? =
        all.firstOrNull { it.tab == tab && it.sub == sub } ?: all.firstOrNull { it.tab == tab }

    fun search(q: String): List<ToolDef> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return all
        return all.filter { it.name.lowercase().contains(t) || it.section.lowercase().contains(t) || it.keywords.contains(t) }
    }
}
