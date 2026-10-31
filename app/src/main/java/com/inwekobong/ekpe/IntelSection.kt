package com.inwekobong.ekpe

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.net.HttpURLConnection
import java.net.URL

/** SuperSmart / Entity Intelligence: the Flask website runs on-device (Chaquopy) and is shown in a WebView. */
class IntelSection(private val a: MainActivity) {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var port = 0

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): View {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4)); setBackgroundColor(C.CARD) }
        bar.addView(a.button("◀") { if (web.canGoBack()) web.goBack() }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(a.button("Home") { if (port > 0) web.loadUrl("http://127.0.0.1:$port/") }, LinearLayout.LayoutParams(0, -2, 2f).apply { leftMargin = a.dp(6) })
        bar.addView(a.button("Reload") { web.reload() }, LinearLayout.LayoutParams(0, -2, 2f).apply { leftMargin = a.dp(6) })
        bar.addView(a.button("API keys") { keysDialog() }, LinearLayout.LayoutParams(0, -2, 2f).apply { leftMargin = a.dp(6) })
        root.addView(bar)
        status = a.tv("Starting the SuperSmart engine on your phone…\n(first launch takes a few seconds)", 14f, C.SUB).apply { setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16)) }
        root.addView(status)
        web = WebView(a).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            setBackgroundColor(C.BG)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, req: android.webkit.WebResourceRequest): Boolean {
                    val u = req.url
                    if (u.host == "127.0.0.1" || u.host == "localhost") return false
                    try { a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
                    return true
                }
            }
            webChromeClient = WebChromeClient()
            setDownloadListener { url, _, disp, mime, _ -> download(url, disp, mime) }
            visibility = View.GONE
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        boot()
        return root
    }

    private fun boot() {
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(a.applicationContext))
                val mod = Python.getInstance().getModule("vint_server")
                applyKeys(mod)
                port = mod.callAttr("start", a.filesDir.absolutePath + "/intel").toInt()
                a.runOnUiThread { status.visibility = View.GONE; web.visibility = View.VISIBLE; web.loadUrl("http://127.0.0.1:$port/") }
            } catch (e: Throwable) {
                a.runOnUiThread { status.text = "Could not start the engine:\n${e.message}" }
            }
        }.start()
    }

    private fun applyKeys(mod: com.chaquo.python.PyObject) {
        val sp = a.prefs()
        mod.callAttr("set_keys", sp.getString("k_serp", "") ?: "", sp.getString("k_cse", "") ?: "", sp.getString("k_cx", "") ?: "", sp.getString("k_brave", "") ?: "")
    }

    private fun keysDialog() {
        val sp = a.prefs()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20), a.dp(10), a.dp(20), 0) }
        fun field(h: String, k: String) = a.edit(h).apply { setText(sp.getString(k, "")); setTextColor(0xFF000000.toInt()); setHintTextColor(0xFF777777.toInt()); setBackgroundColor(0x11000000) }.also { box.add(it, 6) }
        val f1 = field("SerpAPI key", "k_serp"); val f2 = field("Google CSE key", "k_cse")
        val f3 = field("Google CSE cx", "k_cx"); val f4 = field("Brave Search key", "k_brave")
        AlertDialog.Builder(a).setTitle("Search API keys (optional)").setView(box)
            .setPositiveButton("Save") { _, _ ->
                sp.edit().putString("k_serp", f1.text.toString().trim()).putString("k_cse", f2.text.toString().trim())
                    .putString("k_cx", f3.text.toString().trim()).putString("k_brave", f4.text.toString().trim()).apply()
                Thread { try { applyKeys(Python.getInstance().getModule("vint_server")) } catch (e: Exception) {} }.start()
                a.toast("Saved. Run a new scan to use them.")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun download(url: String, disp: String?, mime: String?) {
        Thread {
            try {
                val name = URLUtil.guessFileName(url, disp, mime)
                val conn = URL(url).openConnection() as HttpURLConnection
                val data = conn.inputStream.use { it.readBytes() }
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/EKPE")
                    }
                    val u = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
                    a.contentResolver.openOutputStream(u)!!.use { it.write(data) }
                } else {
                    val d = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "EKPE"); d.mkdirs()
                    java.io.File(d, name).writeBytes(data)
                }
                a.runOnUiThread { a.toast("Saved to Downloads/EKPE/$name") }
            } catch (e: Exception) { a.runOnUiThread { a.toast("Download failed: ${e.message}") } }
        }.start()
    }
}
