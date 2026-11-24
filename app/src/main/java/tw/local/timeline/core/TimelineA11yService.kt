package tw.local.timeline.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import tw.local.timeline.feature.TimelineFlow

/** 時間軸專用無障礙服務。只在設定頁操作，不讀取個人內容。 */
class TimelineA11yService : AccessibilityService() {
    companion object {
        @Volatile var instance: TimelineA11yService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TimelineFlow.attach(this)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        TimelineFlow.onEvent(e)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup(); return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup(); super.onDestroy()
    }

    private fun cleanup() {
        if (instance == null) return
        TimelineFlow.detach(this)
        instance = null
    }
}
