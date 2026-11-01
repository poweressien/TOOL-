package com.toolkit.privacy.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.fragment.app.FragmentActivity
import com.toolkit.privacy.*
import com.toolkit.privacy.core.*

private val subPages = setOf("sounds", "live", "hidden")

@Composable
fun App(route: Route?) {
    val ctx = LocalContext.current
    // Reopening the app lands on the exact tool / page that was open when it was left.
    var tool by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<ToolDef?, String>(
        save = { it?.name ?: "" }, restore = { n -> Catalog.tools.firstOrNull { !it.ekpe && it.name == n } })
    ) { mutableStateOf(Catalog.tools.firstOrNull { !it.ekpe && it.name == ScrollMemory.lastTool(ctx) }) }
    var page by rememberSaveable { mutableStateOf(ScrollMemory.lastPage(ctx)) }
    LaunchedEffect(tool, page) { ScrollMemory.saveLocation(ctx, tool?.name ?: "", page) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // A notification tap or the share sheet lands straight on the right tool.
    LaunchedEffect(route?.id) {
        if (route != null) {
            pendingUrl = route.url; pendingText = route.text
            val d = Catalog.find(route.tab.coerceIn(0, 11), route.sub)
            if (d != null) {
                if (MasterSwitch.on) {
                    tool = d; page = "home"
                } else {
                    android.widget.Toast.makeText(ctx, "EKPE is switched off. Turn the master switch on first.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun openTool(t: ToolDef) {
        if (!MasterSwitch.on) {
            android.widget.Toast.makeText(ctx, "EKPE is switched off. Turn the master switch on first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (t.ekpe) {
            // EKPE suite tools open on the EKPE screen, straight onto the chosen section / tool.
            val i = android.content.Intent(ctx, com.inwekobong.ekpe.MainActivity::class.java).putExtra("tab", t.tab)
            if (t.sub >= 0) i.putExtra("sub", t.sub)
            ctx.startActivity(i)
        } else {
            tool = t; page = "home"
        }
    }

    fun go(p: String) {
        tool = null; page = p
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = tool != null || page != "home") {
        if (tool != null) tool = null else page = if (page in subPages) "settings" else "home"
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
                    Text("EKPE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Privacy Toolkit · iWEKOBONGstudios", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                val pad = NavigationDrawerItemDefaults.ItemPadding
                val tint = MaterialTheme.colorScheme.onSurface
                Spacer(Modifier.padding(top = 8.dp))
                NavigationDrawerItem(label = { Text("Home") }, selected = tool == null && page == "home", onClick = { go("home") },
                    icon = { GlyphIcon("home", tint, 22.dp) }, modifier = Modifier.padding(pad))
                NavigationDrawerItem(label = { Text("Settings") }, selected = tool == null && page == "settings", onClick = { go("settings") },
                    icon = { GlyphIcon("settings", tint, 22.dp) }, modifier = Modifier.padding(pad))
                NavigationDrawerItem(label = { Text("Appearance") }, selected = tool == null && page == "appearance", onClick = { go("appearance") },
                    icon = { GlyphIcon("palette", tint, 22.dp) }, modifier = Modifier.padding(pad))
                NavigationDrawerItem(label = { Text("About") }, selected = tool == null && page == "about", onClick = { go("about") },
                    icon = { GlyphIcon("info", tint, 22.dp) }, modifier = Modifier.padding(pad))
                NavigationDrawerItem(label = { Text("Developer") }, selected = tool == null && page == "developer", onClick = { go("developer") },
                    icon = { GlyphIcon("person", tint, 22.dp) }, modifier = Modifier.padding(pad))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon("power_settings_new", if (MasterSwitch.on) MaterialTheme.colorScheme.primary else tint, 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Master switch", fontWeight = FontWeight.Medium)
                        Text(if (MasterSwitch.on) "Everything is running" else "Everything is paused", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = MasterSwitch.on, onCheckedChange = { MasterSwitch.set(ctx, it) })
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val t = tool
            when {
                t != null -> {
                    AppTopBar(t.name, "arrow_back") { tool = null }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        // While the master switch is off the tool is removed, which also stops anything it was doing.
                        if (MasterSwitch.on) ToolContent(t, pendingUrl, { pendingUrl = null }, pendingText, { pendingText = null })
                        else PausedPanel()
                    }
                }
                page == "home" -> {
                    AppTopBar("EKPE", "menu") { scope.launch { drawerState.open() } }
                    Box(Modifier.weight(1f).fillMaxSize()) { LiveBackground(); HomeScreen { openTool(it) } }
                }
                else -> {
                    val title = when (page) { "settings" -> "Settings"; "appearance" -> "Appearance"; "sounds" -> "Notification sound"; "live" -> "Live background"; "hidden" -> "Hidden apps"; "about" -> "About"; else -> "Developer" }
                    AppTopBar(title, "arrow_back") { page = if (page in subPages) "settings" else "home" }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        when (page) {
                            "settings" -> SettingsPage({ page = "appearance" }, { page = "sounds" }, { page = "live" }, { page = "hidden" })
                            "live" -> LiveBgPage()
                            "hidden" -> HiddenAppsPage()
                            "sounds" -> SoundsPage()
                            "appearance" -> AppearancePage()
                            "about" -> AboutPage()
                            else -> DeveloperPage()
                        }
                    }
                }
            }
        }
    }
}

/** The screen behind each tile. Same screens as before, addressed by their old tab / sub-tab numbers. */
@Composable
private fun ToolContent(t: ToolDef, pendingUrl: String?, clearUrl: () -> Unit, pendingText: String?, clearText: () -> Unit) {
    when (t.tab) {
        0 -> if (t.sub == 1) AppForgeScreen() else HtmlRunnerScreen()
        1 -> when (t.sub) { 0 -> CompressScreen(); 1 -> PdfScreen(); 2 -> EncryptScreen(); 3 -> MetadataScreen(); 4 -> OcrScreen(); 5 -> CleanerScreen(); else -> SmartFilesScreen() }
        2 -> when (t.sub) { 0 -> SignalScreen(); 1 -> SpeedScreen(); 2 -> WifiAnalyzerScreen(); 3 -> ToolboxScreen(); 4 -> DataUsageScreen(); 5 -> LanScreen(); 6 -> WifiProScreen(); else -> IntelScreen() }
        3 -> when (t.sub) { 0 -> VpnScreen(); 1 -> ShieldScreen(); 2 -> AuditScreen(); 3 -> PasswordsScreen(); else -> VaultScreen() }
        4 -> when (t.sub) { 0 -> QrScreen(); 1 -> SensorLabScreen(); 2 -> ApiTesterScreen(); 3 -> DevUtilsScreen(); 4 -> FontsScreen(); 5 -> DeviceInfoScreen(); 6 -> MockLocationScreen(); 7 -> DeviceProfileScreen(); 8 -> SensitiveScreen(pendingText); else -> SensorPlusScreen() }
        5 -> when (t.sub) {
            0 -> SiteGuardScreen(pendingUrl) { clearUrl() }
            1 -> LookupScreen()
            2 -> ScamScreen(pendingText) { clearText() }
            3 -> MonitorScreen()
            4 -> OrganizerScreen()
            else -> BluetoothScreen()
        }
        6 -> when (t.sub) { 0 -> BioTodayScreen(); 1 -> BioHeartScreen(); 2 -> BioSleepScreen(); else -> BioTrendsScreen() }
        7 -> when (t.sub) { 0 -> PriceScanScreen(); 1 -> PriceTrackScreen(); 2 -> PriceCheckScreen(); 3 -> PriceSpendScreen(); 4 -> PriceRatesScreen(); else -> PriceCryptoScreen() }
        8 -> when (t.sub) { 0 -> RadioSpectrumScreen(); 1 -> RadioWifiScreen(); 2 -> RadioBleScreen(); 3 -> RadioSdrScreen(); 4 -> RadioEnvScreen(); 5 -> RadioNoiseScreen(); 6 -> RadioAlertsScreen(); else -> RadioAirScreen() }
        9 -> RealityLensScreen()
        else -> when (t.sub) { 0 -> GeoPlacesScreen(); 1 -> GeoTripsScreen(); 2 -> GeoParkingScreen(); else -> GeoRemindersScreen() }
    }
}

