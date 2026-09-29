package com.fliphomeos.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.fliphomeos.app.ui.MainActivity
import java.lang.ref.WeakReference

class CoverTakeoverAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    internal lateinit var displayHelper: CoverDisplayHelper

    private var navView: View? = null
    private var navWindowManager: WindowManager? = null
    private lateinit var homeOverlay: CoverHomeOverlay
    private var suppression = OverlaySuppressionState()
    private var lastHomeLaunchAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
        displayHelper = CoverDisplayHelper(this)
        homeOverlay = CoverHomeOverlay(
            service = this,
            onLaunch = { app ->
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                val cover = displayHelper.getCoverDisplay()
                if (launchIntent != null && cover != null &&
                    CoverLaunchCoordinator.launchFromAccessibility(this, launchIntent, cover.displayId)
                ) {
                    homeOverlay.hide()
                }
            },
            onOpenSettings = {
                performGlobalAction(GLOBAL_ACTION_HOME)
                val settingsIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                runCatching { startActivity(settingsIntent) }
            }
        )

        ContextCompat.startForegroundService(
            this,
            Intent(this, CoverRuntimeService::class.java)
        )

        if (isDeviceLocked()) {
            suppress(OverlaySuppressionReason.DEVICE_LOCKED)
        } else {
            clearSuppression(OverlaySuppressionReason.DEVICE_LOCKED)
            handler.postDelayed({ requestCoverHomeInternal(false) }, 300L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        val cover = displayHelper.getCoverDisplay() ?: run {
            hideNavigation()
            return
        }

        if (isDeviceLocked()) {
            suppress(OverlaySuppressionReason.DEVICE_LOCKED)
            hideNavigation()
            if (::homeOverlay.isInitialized) homeOverlay.hide()
            return
        } else {
            clearSuppression(OverlaySuppressionReason.DEVICE_LOCKED)
        }

        val eventPackage = event.packageName?.toString()?.trim().orEmpty()
        if (eventPackage.isBlank()) return

        when {
            eventPackage == packageName -> {
                if (suppression.reason == OverlaySuppressionReason.APP_LAUNCH &&
                    ::homeOverlay.isInitialized &&
                    homeOverlay.isShowingOn(cover.displayId)
                ) {
                    clearSuppression(OverlaySuppressionReason.APP_LAUNCH)
                    hideNavigation()
                }
            }

            eventPackage in PERMISSION_PACKAGES -> hideNavigation()

            isTransientSystemUi(eventPackage) -> {
                if (suppression.reason == OverlaySuppressionReason.APP_LAUNCH) {
                    showNavigation(cover)
                }
            }

            else -> {
                suppress(OverlaySuppressionReason.APP_LAUNCH)
                showNavigation(cover)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideNavigation()
        if (::homeOverlay.isInitialized) homeOverlay.destroy()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    private fun suppress(reason: OverlaySuppressionReason) {
        suppression = OverlaySuppressionState(reason, SystemClock.elapsedRealtime())
    }

    private fun clearSuppression(reason: OverlaySuppressionReason) {
        if (suppression.reason == reason) suppression = OverlaySuppressionState()
    }

    private fun requestCoverHomeInternal(force: Boolean) {
        if (isDeviceLocked()) {
            suppress(OverlaySuppressionReason.DEVICE_LOCKED)
            return
        }

        if (suppression.reason == OverlaySuppressionReason.TEMPORARILY_DISABLED && !force) return
        if (suppression.reason == OverlaySuppressionReason.INCOMING_CALL && !force) return
        if (suppression.reason == OverlaySuppressionReason.APP_LAUNCH && !force) return

        val cover = displayHelper.getCoverDisplay() ?: return
        if (cover.state == Display.STATE_OFF) return

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastHomeLaunchAt < HOME_COOLDOWN_MS) return
        lastHomeLaunchAt = now

        runCatching {
            homeOverlay.show(cover)
            suppression = OverlaySuppressionState()
            hideNavigation()
        }
    }

    internal fun onExternalCoverAppLaunched() {
        suppress(OverlaySuppressionReason.APP_LAUNCH)
        if (::homeOverlay.isInitialized) homeOverlay.hide()
        displayHelper.getCoverDisplay()?.let(::showNavigation)
    }

    private fun temporarilyDisable() {
        suppress(OverlaySuppressionReason.TEMPORARILY_DISABLED)
        hideNavigation()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun showNavigation(display: Display) {
        if (navView?.isAttachedToWindow == true) return
        hideNavigation()

        val displayContext = createDisplayContext(display)
        val windowContext = displayContext.createWindowContext(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null
        )
        val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = windowContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(windowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(2), dp(3), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 12, 12, 12))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(110, 255, 255, 255))
            }
            elevation = dp(8).toFloat()
        }

        fun button(symbol: String, description: String, action: () -> Unit) =
            TextView(windowContext).apply {
                text = symbol
                textSize = 19f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = description
                minWidth = dp(42)
                minHeight = dp(38)
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { action() }
            }

        container.addView(button("‹", "Back") {
            performGlobalAction(GLOBAL_ACTION_BACK)
        })
        container.addView(button("●", "FlipHome") {
            clearSuppression(OverlaySuppressionReason.APP_LAUNCH)
            clearSuppression(OverlaySuppressionReason.TEMPORARILY_DISABLED)
            requestCoverHomeInternal(true)
        })
        container.addView(button("▦", "Recents") {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        })

        // Long-press Home is the emergency escape hatch. It temporarily yields
        // to Samsung's own UI until the next genuine screen wake.
        container.getChildAt(1).setOnLongClickListener {
            temporarilyDisable()
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(44),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(5)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FlipHome cover navigation"
        }

        runCatching {
            wm.addView(container, params)
            navWindowManager = wm
            navView = container
        }
    }

    private fun hideNavigation() {
        val view = navView ?: return
        runCatching { navWindowManager?.removeViewImmediate(view) }
        navView = null
        navWindowManager = null
    }

    private fun isTransientSystemUi(packageName: String): Boolean =
        TRANSIENT_SYSTEM_UI_PREFIXES.any(packageName::startsWith)

    companion object {
        const val COVER_DISPLAY_ID = 1
        const val ACTION_OPEN_COVER_HOME = "com.fliphomeos.app.OPEN_COVER_HOME"
        private const val HOME_COOLDOWN_MS = 500L

        private val TRANSIENT_SYSTEM_UI_PREFIXES = arrayOf(
            "com.android.systemui",
            "com.samsung.systemui",
            "com.samsung.android.app.aodservice",
            "com.sec.android.app.launcher"
        )
        private val PERMISSION_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

        @Volatile
        private var activeService: WeakReference<CoverTakeoverAccessibilityService>? = null

        fun isConnected(): Boolean = activeService?.get() != null

        fun startActivityOnDisplay(intent: Intent, displayId: Int): Boolean {
            val service = activeService?.get() ?: return false
            return CoverLaunchCoordinator.launchFromAccessibility(service, intent, displayId)
        }

        fun markCoverAppLaunch() {
            activeService?.get()?.onExternalCoverAppLaunched()
        }

        fun isSuppressedByExternalApp(): Boolean =
            activeService?.get()?.suppression?.reason == OverlaySuppressionReason.APP_LAUNCH

        fun onLockStatePolled(locked: Boolean) {
            activeService?.get()?.handler?.post {
                val service = activeService?.get() ?: return@post
                if (locked) {
                    service.suppress(OverlaySuppressionReason.DEVICE_LOCKED)
                    service.hideNavigation()
                    if (service::homeOverlay.isInitialized) service.homeOverlay.hide()
                } else {
                    service.clearSuppression(OverlaySuppressionReason.DEVICE_LOCKED)
                }
            }
        }

        fun requestCoverHome(reason: String, force: Boolean = false) {
            activeService?.get()?.handler?.post {
                val service = activeService?.get() ?: return@post
                if (force && service.suppression.reason == OverlaySuppressionReason.TEMPORARILY_DISABLED) {
                    service.clearSuppression(OverlaySuppressionReason.TEMPORARILY_DISABLED)
                }
                service.requestCoverHomeInternal(force)
            }
        }
    }
}
