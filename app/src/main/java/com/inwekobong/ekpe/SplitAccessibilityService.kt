package com.inwekobong.ekpe

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class SplitAccessibilityService : AccessibilityService() {
    companion object { var inst: SplitAccessibilityService? = null }
    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun toggleSplit() = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
}
