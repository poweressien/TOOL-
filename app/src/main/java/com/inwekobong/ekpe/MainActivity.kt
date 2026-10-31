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
    private val cache = HashMap<Int, View>()
    private lateinit var frame: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tf = FontStore.load(prefs().getString("font_app", null))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.CARD) }
        names.forEachIndexed { i, n ->
            tabs.addView(button(n) { show(i) }.apply { background = round(0x00000000, dp(8)); setPadding(dp(14), 0, dp(14), 0) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        tabScroll = HorizontalScrollView(this).apply { setBackgroundColor(C.CARD); isHorizontalScrollBarEnabled = false; addView(tabs) }
        root.addView(tabScroll)
        setContentView(root)
        show(prefs().getInt("tab", 0))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("delete_uri")?.let { requestDelete(Uri.parse(it)); i.removeExtra("delete_uri") }
        i?.getIntExtra("tab", -1)?.takeIf { it >= 0 && it < names.size - 1 }?.let { show(it) }
    }

    /** Last tab = "◀ Toolkit": return to the main Privacy Toolkit screen (EKPE is one app with it). */
    private fun backToToolkit() {
        if (isTaskRoot) startActivity(Intent(this, com.toolkit.privacy.MainActivity::class.java))
        finish()
    }

    private fun show(i: Int) {
        if (i == names.size - 1) { backToToolkit(); return }
        prefs().edit().putInt("tab", i).apply()
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
