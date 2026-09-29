package com.fliphomeos.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

class CoverInputAccessibilityService : AccessibilityService() {
    companion object {
        private const val SYSTEM_UI_RECLAIM_DELAY_MS = 700L
        private const val RECENT_USER_APP_GUARD_MS = 1_100L

        @Volatile
        private var activeService = WeakReference<CoverInputAccessibilityService>(null)

        private val RECLAIM_PACKAGES = arrayOf(
            "com.sec.android.app.launcher",
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.clockface"
        )

        private val NON_USER_PACKAGES = arrayOf(
            "com.android.systemui",
            "com.samsung.systemui",
            "com.samsung.android.app.aodservice",
            "com.sec.android.app.launcher",
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.clockface",
            "com.fliphomeos.app"
        )

        fun launchPackageOnDisplay(packageName: String, displayId: Int): Boolean {
            val service = activeService.get() ?: return false
            return launchFromContext(service, packageName, displayId)
        }

        fun fallbackLaunchFromContext(
            context: Context,
            packageName: String,
            displayId: Int
        ): Boolean = launchFromContext(context, packageName, displayId)

        private fun launchFromContext(
            context: Context,
            packageName: String,
            displayId: Int
        ): Boolean {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false

            // MULTIPLE_TASK helps isolate the cover task instead of migrating/reusing an existing
            // inner-display task.
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )

            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }.toBundle()

            return runCatching {
                context.startActivity(intent, options)
                true
            }.getOrDefault(false)
        }

        fun performBack(): Boolean =
            activeService.get()?.performGlobalAction(GLOBAL_ACTION_BACK) == true

        fun performRecents(): Boolean =
            activeService.get()?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownUserAppAt = 0L
    private var pendingSystemUiGeneration = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        if (!CoverOverlayService.isHomeSuppressedForApp()) return

        val packageName = event.packageName?.toString()?.trim().orEmpty()
        if (packageName.isEmpty() || packageName == this.packageName) return

        val now = SystemClock.elapsedRealtime()
        if (isUserPackage(packageName)) {
            lastKnownUserAppAt = now
            // Cancel any stale delayed SystemUI reclaim when a real app becomes foreground.
            pendingSystemUiGeneration++
            return
        }

        if (RECLAIM_PACKAGES.any(packageName::startsWith)) {
            pendingSystemUiGeneration++
            CoverOverlayService.requestShowHome("reclaim:$packageName")
            return
        }

        // Samsung emits transient SystemUI/AOD windows during normal launch, rotate and wake
        // transitions. Reclaiming on every event makes Home jump back over healthy running apps.
        val isSystemUi = packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.samsung.systemui") ||
            packageName.startsWith("com.samsung.android.app.aodservice")

        if (isSystemUi && event.displayId != Display.DEFAULT_DISPLAY) {
            val generation = ++pendingSystemUiGeneration
            handler.postDelayed({
                if (generation != pendingSystemUiGeneration) return@postDelayed
                if (!CoverOverlayService.isHomeSuppressedForApp()) return@postDelayed

                val userAppAge = SystemClock.elapsedRealtime() - lastKnownUserAppAt
                if (lastKnownUserAppAt == 0L || userAppAge >= RECENT_USER_APP_GUARD_MS) {
                    CoverOverlayService.requestShowHome("delayed_cover_systemui")
                }
            }, SYSTEM_UI_RECLAIM_DELAY_MS)
        }
    }

    private fun isUserPackage(packageName: String): Boolean =
        NON_USER_PACKAGES.none(packageName::startsWith)

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        activeService = WeakReference(null)
        super.onDestroy()
    }
}