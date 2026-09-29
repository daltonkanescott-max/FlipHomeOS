package com.fliphomeos.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

class CoverTakeoverAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var reclaimGeneration = 0L
    private var lastUserAppAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
        ContextCompat.startForegroundService(
            this,
            Intent(this, CoverHomeService::class.java).setAction(CoverHomeService.ACTION_START)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString()?.trim().orEmpty()
        if (packageName.isBlank() || packageName == this.packageName) return

        val coverId = CoverHomeService.currentCoverDisplayId()
        if (coverId != Display.INVALID_DISPLAY &&
            event.displayId != Display.INVALID_DISPLAY &&
            event.displayId != coverId) {
            return
        }

        if (isUserApp(packageName)) {
            lastUserAppAt = SystemClock.elapsedRealtime()
            reclaimGeneration++
            return
        }

        if (packageName.startsWith(SAMSUNG_LAUNCHER)) {
            scheduleStableReclaim(450L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    private fun scheduleStableReclaim(delayMs: Long) {
        val generation = ++reclaimGeneration
        handler.postDelayed({
            if (generation != reclaimGeneration) return@postDelayed
            val userAppAge = SystemClock.elapsedRealtime() - lastUserAppAt
            if (userAppAge < 350L) return@postDelayed
            CoverHomeService.requestShowHome("stable_launcher_signal")
        }, delayMs)
    }

    private fun isUserApp(packageName: String): Boolean {
        return !packageName.startsWith("com.android.systemui") &&
            !packageName.startsWith("com.samsung.systemui") &&
            !packageName.startsWith("com.samsung.android.app.aodservice") &&
            !packageName.startsWith(SAMSUNG_LAUNCHER)
    }

    companion object {
        private const val SAMSUNG_LAUNCHER = "com.sec.android.app.launcher"

        @Volatile
        private var activeService: WeakReference<CoverTakeoverAccessibilityService>? = null

        fun isConnected(): Boolean = activeService?.get() != null

        fun launchPackageOnDisplay(
            contextIntent: Intent,
            displayId: Int
        ): Boolean {
            val service = activeService?.get() ?: return false

            contextIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )

            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }.toBundle()

            return runCatching {
                service.startActivity(contextIntent, options)
                true
            }.getOrDefault(false)
        }

        fun performBack(): Boolean =
            activeService?.get()?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun performRecents(): Boolean =
            activeService?.get()?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
    }
}
