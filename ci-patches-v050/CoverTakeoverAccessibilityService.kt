package com.fliphomeos.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
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

/**
 * Cover navigation + reliable activity launch bridge.
 *
 * SystemUI events are intentionally treated as transient. Earlier FlipHome
 * builds immediately relaunched Home whenever SystemUI appeared, which caused
 * the visible One UI -> FlipHome bounce. Home is now explicit via the nav pill
 * or a real cover wake.
 */
class CoverTakeoverAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayHelper: CoverDisplayHelper

    private var navView: View? = null
    private var navWindowManager: WindowManager? = null
    private var coverAppSessionActive = false
    private var lastHomeLaunchAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
        displayHelper = CoverDisplayHelper(this)

        ContextCompat.startForegroundService(
            this,
            Intent(this, CoverRuntimeService::class.java)
        )

        handler.postDelayed({
            if (!isDeviceLocked()) requestCoverHomeInternal(force = false)
        }, 300L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }

        val cover = displayHelper.getCoverDisplay() ?: run {
            hideNavigation()
            coverAppSessionActive = false
            return
        }

        if (isDeviceLocked()) {
            hideNavigation()
            return
        }

        val eventPackage = event.packageName?.toString()?.trim().orEmpty()
        if (eventPackage.isBlank()) return

        when {
            eventPackage == packageName -> {
                coverAppSessionActive = false
                hideNavigation()
            }

            eventPackage in PERMISSION_PACKAGES -> {
                hideNavigation()
            }

            isTransientSystemUi(eventPackage) -> {
                // Keep navigation available if the user reached Samsung's cover
                // UI while exiting an app. Do not auto-reclaim here because the
                // same package also owns notifications/AOD and reclaiming every
                // SystemUI event steals focus.
                if (coverAppSessionActive) showNavigation(cover)
            }

            else -> {
                coverAppSessionActive = true
                showNavigation(cover)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideNavigation()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    private fun isDeviceLocked(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceLocked == true
    }

    private fun requestCoverHomeInternal(force: Boolean) {
        if (isDeviceLocked()) return

        val cover = displayHelper.getCoverDisplay() ?: return
        if (cover.state == Display.STATE_OFF) return

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastHomeLaunchAt < HOME_COOLDOWN_MS) return
        lastHomeLaunchAt = now

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_COVER_HOME
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = cover.displayId
        }.toBundle()

        runCatching {
            startActivity(intent, options)
            coverAppSessionActive = false
            hideNavigation()
        }
    }

    private fun showNavigation(display: Display) {
        if (navView?.isAttachedToWindow == true) return

        hideNavigation()

        val displayContext = createDisplayContext(display)
        val windowContext = displayContext.createWindowContext(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
        val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

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

        fun button(symbol: String, description: String, action: () -> Unit): TextView =
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
            requestCoverHomeInternal(force = true)
        })

        container.addView(button("▦", "Recents") {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        })

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

    private fun isTransientSystemUi(packageName: String): Boolean {
        return TRANSIENT_SYSTEM_UI_PREFIXES.any(packageName::startsWith)
    }

    companion object {
        // Kept for compatibility with the existing setup helper. Actual routing
        // uses CoverDisplayHelper and does not assume this ID.
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
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }.toBundle()

            return runCatching {
                service.startActivity(intent, options)
                true
            }.getOrDefault(false)
        }

        fun markCoverAppLaunch() {
            activeService?.get()?.let { service ->
                service.coverAppSessionActive = true
                service.displayHelper.getCoverDisplay()?.let(service::showNavigation)
            }
        }

        fun requestCoverHome(reason: String) {
            activeService?.get()?.handler?.post {
                activeService?.get()?.requestCoverHomeInternal(force = false)
            }
        }
    }
}
