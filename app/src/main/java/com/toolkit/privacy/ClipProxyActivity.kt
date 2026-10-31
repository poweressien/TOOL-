package com.toolkit.privacy

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle

/**
 * Android only lets the app in the foreground read the clipboard. The floating bubble opens this invisible activity for a
 * split second, reads what you copied, hands it to the bubble and closes.
 */
class ClipProxyActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val kind = intent.getStringExtra("kind") ?: "link"
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        } catch (e: Exception) { "" }
        SiteGuardService.instance?.onClip(kind, text)
        finish()
        overridePendingTransition(0, 0)
    }
}
