package com.toolkit.privacy

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/** Where an incoming intent (notification tap, share sheet) wants to land. */
data class Route(val tab: Int, val sub: Int, val url: String? = null, val text: String? = null, val id: Long = System.nanoTime())

class MainActivity : FragmentActivity() {
    private val route = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route.value = parse(intent)
        setContent { AppTheme { Surface { App(route.value) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route.value = parse(intent)
    }

    private fun parse(i: Intent?): Route? {
        if (i == null) return null
        if (i.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val shared = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (shared.isEmpty()) return null
            val urls = SiteRules.extractUrls(shared)
            val onlyLink = urls.size == 1 && shared.length <= urls[0].length + 25
            return if (onlyLink) {
                SiteGuardService.instance?.check(urls[0], force = true)
                Route(5, 0, url = urls[0])
            } else Route(5, 2, text = shared)
        }
        if (i.hasExtra("tab")) return Route(i.getIntExtra("tab", 0), i.getIntExtra("sub", 0))
        return null
    }
}

@Composable
fun App(route: Route?) {
    var tab by remember { mutableIntStateOf(0) }
    val subs = remember { mutableStateListOf(0, 0, 0, 0, 0, 0) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(route?.id) {
        route?.let {
            tab = it.tab.coerceIn(0, 5)
            subs[tab] = it.sub
            pendingUrl = it.url; pendingText = it.text
        }
    }
    val titles = listOf("Run", "Files", "Network", "Security", "Tools", "Assist")
    val icons = listOf("💻", "🗂", "📶", "🛡", "🧰", "🧭")
    Scaffold(bottomBar = {
        NavigationBar {
            titles.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icons[i]) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HtmlRunnerScreen()
                1 -> SubTabs(listOf("Compress", "PDF", "Encrypt", "Strip EXIF", "OCR", "Cleaner"), subs[1], { subs[1] = it }) { i ->
                    when (i) { 0 -> CompressScreen(); 1 -> PdfScreen(); 2 -> EncryptScreen(); 3 -> MetadataScreen(); 4 -> OcrScreen(); else -> CleanerScreen() }
                }
                2 -> SubTabs(listOf("Signal", "Speed", "Wi-Fi", "Toolbox", "Data", "LAN"), subs[2], { subs[2] = it }) { i ->
                    when (i) { 0 -> SignalScreen(); 1 -> SpeedScreen(); 2 -> WifiAnalyzerScreen(); 3 -> ToolboxScreen(); 4 -> DataUsageScreen(); else -> LanScreen() }
                }
                3 -> SubTabs(listOf("VPN", "Shield", "Audit", "Passwords", "Vault"), subs[3], { subs[3] = it }) { i ->
                    when (i) { 0 -> VpnScreen(); 1 -> ShieldScreen(); 2 -> AuditScreen(); 3 -> PasswordsScreen(); else -> VaultScreen() }
                }
                4 -> SubTabs(listOf("QR", "Sensors", "API", "Dev", "Fonts", "Info", "Location", "Preview"), subs[4], { subs[4] = it }) { i ->
                    when (i) { 0 -> QrScreen(); 1 -> SensorLabScreen(); 2 -> ApiTesterScreen(); 3 -> DevUtilsScreen(); 4 -> FontsScreen(); 5 -> DeviceInfoScreen(); 6 -> MockLocationScreen(); else -> DeviceProfileScreen() }
                }
                else -> SubTabs(listOf("Site Guard", "Smart Scan", "Scam Check", "Monitor", "Home", "Bluetooth"), subs[5], { subs[5] = it }) { i ->
                    when (i) {
                        0 -> SiteGuardScreen(pendingUrl) { pendingUrl = null }
                        1 -> LookupScreen()
                        2 -> ScamScreen(pendingText) { pendingText = null }
                        3 -> MonitorScreen()
                        4 -> OrganizerScreen()
                        else -> BluetoothScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun SubTabs(names: List<String>, selected: Int, onSelect: (Int) -> Unit, content: @Composable (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = selected.coerceIn(0, names.size - 1), edgePadding = 8.dp) {
            names.forEachIndexed { i, n -> Tab(selected = selected == i, onClick = { onSelect(i) }, text = { Text(n) }) }
        }
        Box(Modifier.fillMaxSize()) { content(selected.coerceIn(0, names.size - 1)) }
    }
}
