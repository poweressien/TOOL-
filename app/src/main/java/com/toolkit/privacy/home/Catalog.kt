package com.toolkit.privacy.home

import com.toolkit.privacy.*
import com.toolkit.privacy.core.*

/** The sections of the home screen. */
enum class Group(val title: String, val color: Int) {
    DEV("Run", 0xFF94A3B8.toInt()),
    FILES("Files & Media", 0xFF5B9DF8.toInt()),
    NET("Network", 0xFF2DD4BF.toInt()),
    SEC("Security", 0xFFF0657A.toInt()),
    UTIL("Utilities", 0xFFF5A524.toInt()),
    ASSIST("Assist", 0xFF4ADE80.toInt()),
    HEALTH("BioTrack", 0xFFFF8A65.toInt()),
    PRICE("Price", 0xFFA3E635.toInt()),
    RADIO("Radio & Environment", 0xFFA78BFA.toInt()),
    LENS("Lens", 0xFF22D3EE.toInt()),
    GEO("GeoSense", 0xFFFB923C.toInt()),
    EKPE("EKPE Suite", 0xFFF2C94C.toInt()),
    GUARD("EKPE Guard", 0xFF60A5FA.toInt()),
    LABS("EKPE Labs", 0xFFE879F9.toInt())
}

/**
 * One tool = one app tile on the home screen.
 * ekpe = false: opens inside this screen (tab / sub are the old tab and sub-tab numbers).
 * ekpe = true : opens the EKPE screen (tab = EKPE section, sub = tool inside Guard / Labs, or -1).
 */
class ToolDef(val name: String, val icon: String, val group: Group, val tab: Int, val sub: Int = 0, val ekpe: Boolean = false)

object Catalog {
    val tools: List<ToolDef> = listOf(
        // Run
        ToolDef("AppForge", "apps", Group.DEV, 0, 1),
        ToolDef("HTML Runner", "html", Group.DEV, 0, 0),

        // Files & Media (old tab 1)
        ToolDef("Compress", "compress", Group.FILES, 1, 0),
        ToolDef("PDF Tools", "picture_as_pdf", Group.FILES, 1, 1),
        ToolDef("Encrypt", "enhanced_encryption", Group.FILES, 1, 2),
        ToolDef("Strip EXIF", "hide_image", Group.FILES, 1, 3),
        ToolDef("OCR Scanner", "document_scanner", Group.FILES, 1, 4),
        ToolDef("Cleaner", "cleaning_services", Group.FILES, 1, 5),
        ToolDef("Smart Files", "folder_special", Group.FILES, 1, 6),

        // Network (old tab 2)
        ToolDef("Signal", "signal_cellular_alt", Group.NET, 2, 0),
        ToolDef("Speed Test", "speed", Group.NET, 2, 1),
        ToolDef("Wi-Fi Analyzer", "network_check", Group.NET, 2, 2),
        ToolDef("Net Toolbox", "build", Group.NET, 2, 3),
        ToolDef("Data Usage", "data_usage", Group.NET, 2, 4),
        ToolDef("LAN Scan", "lan", Group.NET, 2, 5),
        ToolDef("Wi-Fi Pro", "wifi", Group.NET, 2, 6),
        ToolDef("Net Intel", "travel_explore", Group.NET, 2, 7),

        // Security (old tab 3)
        ToolDef("VPN", "vpn_key", Group.SEC, 3, 0),
        ToolDef("DNS Shield", "shield", Group.SEC, 3, 1),
        ToolDef("App Audit", "fact_check", Group.SEC, 3, 2),
        ToolDef("Passwords", "password", Group.SEC, 3, 3),
        ToolDef("Vault", "lock", Group.SEC, 3, 4),

        // Utilities (old tab 4)
        ToolDef("QR", "qr_code_scanner", Group.UTIL, 4, 0),
        ToolDef("Sensors", "sensors", Group.UTIL, 4, 1),
        ToolDef("API Tester", "api", Group.UTIL, 4, 2),
        ToolDef("Dev Utils", "code", Group.UTIL, 4, 3),
        ToolDef("Fonts", "text_fields", Group.UTIL, 4, 4),
        ToolDef("Device Info", "info", Group.UTIL, 4, 5),
        ToolDef("Mock Location", "wrong_location", Group.UTIL, 4, 6),
        ToolDef("Device Preview", "phone_android", Group.UTIL, 4, 7),
        ToolDef("Sensitive Scan", "find_in_page", Group.UTIL, 4, 8),
        ToolDef("Sensor+", "explore", Group.UTIL, 4, 9),

        // Assist (old tab 5)
        ToolDef("Site Guard", "security", Group.ASSIST, 5, 0),
        ToolDef("Smart Scan", "manage_search", Group.ASSIST, 5, 1),
        ToolDef("Scam Check", "report", Group.ASSIST, 5, 2),
        ToolDef("Monitor", "visibility", Group.ASSIST, 5, 3),
        ToolDef("Home Organizer", "home", Group.ASSIST, 5, 4),
        ToolDef("Bluetooth", "bluetooth", Group.ASSIST, 5, 5),

        // BioTrack (old tab 6)
        ToolDef("Today", "today", Group.HEALTH, 6, 0),
        ToolDef("Heart", "favorite", Group.HEALTH, 6, 1),
        ToolDef("Sleep", "bedtime", Group.HEALTH, 6, 2),
        ToolDef("Trends", "trending_up", Group.HEALTH, 6, 3),

        // Price (old tab 7)
        ToolDef("Price Scan", "sell", Group.PRICE, 7, 0),
        ToolDef("Price Track", "track_changes", Group.PRICE, 7, 1),
        ToolDef("Price Check", "price_check", Group.PRICE, 7, 2),
        ToolDef("Spending", "payments", Group.PRICE, 7, 3),
        ToolDef("Rates", "currency_exchange", Group.PRICE, 7, 4),
        ToolDef("Crypto", "currency_bitcoin", Group.PRICE, 7, 5),

        // Radio & Environment (old tab 8)
        ToolDef("Spectrum", "graphic_eq", Group.RADIO, 8, 0),
        ToolDef("Radio Wi-Fi", "wifi_tethering", Group.RADIO, 8, 1),
        ToolDef("BLE", "bluetooth_searching", Group.RADIO, 8, 2),
        ToolDef("SDR", "radio", Group.RADIO, 8, 3),
        ToolDef("Env Sensors", "thermostat", Group.RADIO, 8, 4),
        ToolDef("Noise", "hearing", Group.RADIO, 8, 5),
        ToolDef("Alerts", "notifications_active", Group.RADIO, 8, 6),
        ToolDef("Air", "air", Group.RADIO, 8, 7),

        // Lens (old tab 9)
        ToolDef("RealityLens", "center_focus_strong", Group.LENS, 9),

        // GeoSense (old tab 10)
        ToolDef("Places", "place", Group.GEO, 10, 0),
        ToolDef("Journeys", "route", Group.GEO, 10, 1),
        ToolDef("Parking", "local_parking", Group.GEO, 10, 2),
        ToolDef("Reminders", "alarm", Group.GEO, 10, 3),

        // EKPE suite (opens the EKPE screen)
        ToolDef("Number Analyzer", "analytics", Group.EKPE, 0, -1, true),
        ToolDef("Entity Intel", "person_search", Group.EKPE, 1, -1, true),
        ToolDef("Split Screen", "splitscreen", Group.EKPE, 2, -1, true),
        ToolDef("Font Studio", "font_download", Group.EKPE, 3, -1, true),
        ToolDef("Cloud Gallery", "cloud_upload", Group.EKPE, 4, -1, true),
        ToolDef("Floating Clock", "schedule", Group.EKPE, 5, -1, true),
        ToolDef("Extra Tools", "handyman", Group.EKPE, 6, -1, true),
        ToolDef("Radar", "radar", Group.EKPE, 7, -1, true),

        // EKPE Guard (tab 8, one tile per tool)
        ToolDef("LAN Guard", "router", Group.GUARD, 8, 0, true),
        ToolDef("Bug Sweep", "bug_report", Group.GUARD, 8, 1, true),
        ToolDef("Device Finder", "location_searching", Group.GUARD, 8, 2, true),
        ToolDef("Network Toolkit", "dns", Group.GUARD, 8, 3, true),
        ToolDef("App Privacy Audit", "privacy_tip", Group.GUARD, 8, 4, true),
        ToolDef("Private Vault", "inventory_2", Group.GUARD, 8, 5, true),

        // EKPE Labs (tab 9, one tile per tool)
        ToolDef("Coverage Mapper", "map", Group.LABS, 9, 0, true),
        ToolDef("Tower Locator", "cell_tower", Group.LABS, 9, 1, true),
        ToolDef("Offline AI", "psychology", Group.LABS, 9, 2, true),
        ToolDef("Automation", "bolt", Group.LABS, 9, 3, true),
        ToolDef("Script Lab", "terminal", Group.LABS, 9, 4, true),
        ToolDef("Phone Share", "swap_horiz", Group.LABS, 9, 5, true),
        ToolDef("Media Forensics", "image_search", Group.LABS, 9, 6, true),
        ToolDef("Money Tracker", "account_balance_wallet", Group.LABS, 9, 7, true)
    )

    /** Finds the in-app tool for an old (tab, sub) address, used by notifications and the share sheet. */
    fun find(tab: Int, sub: Int): ToolDef? =
        tools.firstOrNull { !it.ekpe && it.tab == tab && it.sub == sub }
            ?: tools.firstOrNull { !it.ekpe && it.tab == tab && (tab == 0 || tab == 9) }
}
