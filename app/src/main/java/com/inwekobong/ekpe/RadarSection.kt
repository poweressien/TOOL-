package com.inwekobong.ekpe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Radar: live scanner for cell towers, Wi-Fi, Bluetooth, signal strength/speed and electromagnetic field.
 * Everything runs on the phone. The scanning itself lives in [RadarEngine] and keeps running in
 * [RadarService] so alerts still pop up when the app is closed.
 */
class RadarSection(private val a: MainActivity) {

    private fun wanted(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p += Manifest.permission.NEARBY_WIFI_DEVICES
            p += Manifest.permission.POST_NOTIFICATIONS
        }
        return p
    }

    private fun startScanning() {
        RadarEngine.start(a)
        if (a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { a.startForegroundService(Intent(a, RadarService::class.java)) } catch (e: Exception) { }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(): View {
        val web = WebView(a).apply {
            setBackgroundColor(C.BG)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            addJavascriptInterface(Bridge(), "Native")
            // the page is fully local: refuse to navigate anywhere else
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean = true
            }
            loadUrl("file:///android_asset/radar/index.html")
        }
        RadarEngine.start(a)
        val need = wanted().filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startScanning() else a.ask(need.toTypedArray()) { startScanning() }
        return FrameLayout(a).apply {
            setBackgroundColor(C.BG)
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    inner class Bridge {
        @JavascriptInterface fun snapshot(): String = RadarEngine.snapshot()
        @JavascriptInterface fun test() { RadarEngine.notify("EKPE Radar test", "Alerts are working. You will get a pop-up when something new shows up nearby.") }
        @JavascriptInterface fun cfg(r: Int, e: Int) { RadarEngine.cfg(r, e) }
    }
}
