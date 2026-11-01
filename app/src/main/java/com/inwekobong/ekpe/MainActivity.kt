package com.inwekobong.ekpe

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

class MainActivity : FragmentActivity() {
    var tf: Typeface? = null
    private var permCb: (() -> Unit)? = null
    private var signCb: ((Boolean) -> Unit)? = null
    private var pickCb: ((Uri?) -> Unit)? = null
    private var saveCb: ((Uri?) -> Unit)? = null
    private var guard: GuardHome? = null
    private var labs: LabsHome? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permCb?.invoke() }
    private val signLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val ok = try { GoogleSignIn.getSignedInAccountFromIntent(r.data).result != null } catch (e: Exception) { toast("Sign-in failed: ${e.message}"); false }
        signCb?.invoke(ok)
    }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { pickCb?.invoke(it) }
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { saveCb?.invoke(it) }

    private val names = listOf("Analyzer", "Intel", "Split", "Fonts", "Cloud", "Clock", "Tools", "Radar", "Guard", "Labs", "◀ Toolkit")
    private val titles = listOf("Number Analyzer", "Entity Intel", "Split Screen", "Font Studio", "Cloud Gallery", "Floating Clock", "Extra Tools", "Radar", "Guard", "Labs")
    private val cache = HashMap<Int, View>()
    private lateinit var frame: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var titleTv: TextView
    private var themeVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.toolkit.privacy.core.MasterSwitch.init(this)
        if (!com.toolkit.privacy.core.MasterSwitch.isOn(this)) {
            toast("EKPE is switched off. Turn the master switch on from the home screen.")
            finish(); return
        }
        C.load(this)
        themeVersion = com.toolkit.privacy.core.ThemeState.version
        window.statusBarColor = C.CARD
        window.navigationBarColor = C.BG
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = C.LIGHT; isAppearanceLightNavigationBars = C.LIGHT
        }
        tf = FontStore.load(prefs().getString("font_app", null))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        // Slim header: back to the home screen + the name of the tool that is open.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(C.CARD); setPadding(dp(6), dp(4), dp(14), dp(4))
        }
        header.addView(button("←") { backToToolkit() }.apply { background = round(0x00000000, dp(8)); setTextColor(C.TXT); textSize = 20f; setPadding(dp(14), 0, dp(14), 0) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        titleTv = tv("", 18f, C.TXT, true)
        header.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // The tab strip is kept (not shown) so show() keeps working exactly as before.
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, n -> tabs.addView(button(n) { show(i) }) }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) { backToToolkit() }
        show(prefs().getInt("tab", 0))
        handleIntent(intent)
        header.applyTypeface(tf)
    }

    override fun onResume() {
        super.onResume()
        if (!com.toolkit.privacy.core.MasterSwitch.isOn(this)) { finish(); return }
        if (themeVersion != -1 && themeVersion != com.toolkit.privacy.core.ThemeState.version) recreate()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        if (!::frame.isInitialized) return
        i?.getStringExtra("delete_uri")?.let { requestDelete(Uri.parse(it)); i.removeExtra("delete_uri") }
        val t = i?.getIntExtra("tab", -1) ?: -1
        if (t >= 0 && t < names.size - 1) {
            show(t)
            // Home-screen tiles can open one tool of Guard (tab 8) or Labs (tab 9) directly.
            val sub = i?.getIntExtra("sub", -1) ?: -1
            if (sub >= 0) {
                when (t) { 8 -> guard?.openAt(sub); 9 -> labs?.openAt(sub) }
                i?.removeExtra("sub")
            }
        }
    }

    /** Last tab = "◀ Toolkit": return to the main Privacy Toolkit screen (EKPE is one app with it). */
    private fun backToToolkit() {
        if (isTaskRoot) startActivity(Intent(this, com.toolkit.privacy.MainActivity::class.java))
        finish()
    }

    private fun show(i: Int) {
        if (i == names.size - 1) { backToToolkit(); return }
        prefs().edit().putInt("tab", i).apply()
        titleTv.text = titles[i]
        for (k in 0 until tabs.childCount) (tabs.getChildAt(k) as Button).setTextColor(if (k == i) C.ACC else C.TXT)
        val v = cache.getOrPut(i) {
            when (i) {
                0 -> AnalyzerSection(this).view()
                1 -> IntelSection(this).view()
                2 -> SplitSection(this).view()
                3 -> FontSection(this).view()
                4 -> GallerySection(this).view()
                5 -> ClockSection(this).view()
                7 -> RadarSection(this).view()
                8 -> GuardHome(this).also { guard = it }.view()
                9 -> LabsHome(this).also { labs = it }.view()
                else -> ToolsSection(this).view()
            }.also { it.applyTypeface(tf) }
        }
        frame.removeAllViews(); frame.addView(v)
        tabs.applyTypeface(tf)
    }

    fun ask(perms: Array<String>, then: () -> Unit) { permCb = then; permLauncher.launch(perms) }
    fun pickFile(then: (Uri?) -> Unit) { pickCb = then; pickLauncher.launch(arrayOf("*/*")) }
    fun saveFile(name: String, then: (Uri?) -> Unit) { saveCb = then; saveLauncher.launch(name) }

    override fun onPause() { guard?.stopAll(); labs?.stopAll(); super.onPause() }

    fun signInDrive(then: (Boolean) -> Unit) {
        signCb = then
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(DriveUploader.SCOPE)).build()
        signLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    fun signOutDrive(then: () -> Unit) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener { then() }
    }

    fun requestDelete(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else contentResolver.delete(uri, null, null)
        } catch (e: Exception) { toast("Could not free local copy: ${e.message}") }
    }
}
