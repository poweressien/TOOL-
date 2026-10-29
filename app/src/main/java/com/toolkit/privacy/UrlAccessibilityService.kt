package com.toolkit.privacy

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads ONLY the address bar of known browsers so Site Guard can score the page you're on.
 * It never reads page content, passwords, messages or anything outside a browser's URL field.
 */
class UrlAccessibilityService : AccessibilityService() {
    private val urlIds = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.chrome.beta" to "com.chrome.beta:id/url_bar",
        "com.chrome.dev" to "com.chrome.dev:id/url_bar",
        "org.chromium.chrome" to "org.chromium.chrome:id/url_bar",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
        "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.fenix" to "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
        "com.opera.browser" to "com.opera.browser:id/url_field",
        "com.opera.mini.native" to "com.opera.mini.native:id/url_field",
        "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
        "com.kiwibrowser.browser" to "com.kiwibrowser.browser:id/url_bar",
        "com.UCMobile.intl" to "com.UCMobile.intl:id/url_text"
    )
    private var lastUrl = ""
    private var lastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // keep the bubble alive once the user has granted this
        if (Prefs.sp(this).getBoolean(Prefs.SG_ON, false) && !SiteGuardService.isRunning() && SiteGuardService.canDraw(this)) SiteGuardService.start(this)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (!urlIds.containsKey(pkg)) return
        if (!Prefs.sp(this).getBoolean(Prefs.SG_AUTO, true) || !SiteGuardService.isRunning()) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 700) return
        val root = rootInActiveWindow ?: return
        try {
            val text = findUrl(root, urlIds.getValue(pkg)) ?: return
            val parsed = SiteRules.parse(text) ?: return
            if (parsed.full == lastUrl) return
            lastUrl = parsed.full; lastAt = now
            SiteGuardService.push(parsed.full)
        } finally {
            @Suppress("DEPRECATION") if (android.os.Build.VERSION.SDK_INT < 33) root.recycle()
        }
    }

    private fun findUrl(root: AccessibilityNodeInfo, id: String): String? {
        root.findAccessibilityNodeInfosByViewId(id)?.forEach { n ->
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && !t.contains(' ')) return t
        }
        // fallback for browser versions that renamed the id: any field named like a url bar
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val n = queue.removeFirst(); seen++
            val vid = n.viewIdResourceName ?: ""
            if (vid.contains("url_bar") || vid.contains("location_bar") || vid.contains("url_field") || vid.contains("toolbar_url")) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && !t.contains(' ')) return t
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun onInterrupt() {}
}
