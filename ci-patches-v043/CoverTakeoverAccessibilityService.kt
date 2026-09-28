package com.fliphomeos.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
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
import com.fliphomeos.app.ui.MainActivity

class CoverTakeoverAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunchAt = 0L
    private var receiverRegistered = false
    private var wakeCheckAttempts = 0
    private var suppressSystemUiHomeUntil = 0L

    private var navView: View? = null
    private var navWindowManager: WindowManager? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    hideNavigation()
                    beginWakeCheck()
                }

                Intent.ACTION_USER_PRESENT -> {
                    handler.removeCallbacks(wakeCheckRunnable)
                    wakeCheckAttempts = 0
                    scheduleCoverHome(80L)
                }

                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(wakeCheckRunnable)
                    handler.removeCallbacks(showNavigationRunnable)
                    wakeCheckAttempts = 0
                    hideNavigation()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        if (!isDeviceLocked() && isCoverDisplayActive()) {
            scheduleCoverHome(250L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }

        if (isDeviceLocked()) {
            hideNavigation()
            return
        }

        // Samsung does not consistently report cover-app accessibility events with
        // displayId=1. Gate on whether the cover display is actually active instead.
        if (!isCoverDisplayActive()) {
            hideNavigation()
            return
        }

        val eventPackage = event.packageName?.toString() ?: return

        when {
            eventPackage == packageName -> {
                hideNavigation()
            }

            eventPackage == SYSTEM_UI_PACKAGE -> {
                hideNavigation()
                if (SystemClock.elapsedRealtime() >= suppressSystemUiHomeUntil) {
                    scheduleCoverHome(100L)
                }
            }

            eventPackage in PERMISSION_PACKAGES -> {
                hideNavigation()
            }

            else -> {
                scheduleNavigation()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideNavigation()

        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun isDeviceLocked(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceLocked == true
    }

    private fun coverDisplay(): Display? {
        return getSystemService(DisplayManager::class.java)?.getDisplay(COVER_DISPLAY_ID)
    }

    private fun isCoverDisplayActive(): Boolean {
        val display = coverDisplay() ?: return false
        return display.state != Display.STATE_OFF
    }

    private fun scheduleCoverHome(delayMs: Long) {
        handler.removeCallbacks(launchRunnable)
        handler.postDelayed(launchRunnable, delayMs)
    }

    private fun beginWakeCheck() {
        handler.removeCallbacks(wakeCheckRunnable)
        wakeCheckAttempts = 0
        handler.postDelayed(wakeCheckRunnable, 180L)
    }

    private val wakeCheckRunnable = object : Runnable {
        override fun run() {
            if (!isDeviceLocked()) {
                wakeCheckAttempts = 0
                launchCoverHomeIfAvailable(force = false)
                return
            }

            wakeCheckAttempts += 1
            if (wakeCheckAttempts < MAX_WAKE_CHECKS) {
                handler.postDelayed(this, WAKE_CHECK_INTERVAL_MS)
            }
        }
    }

    private val launchRunnable = Runnable {
        launchCoverHomeIfAvailable(force = false)
    }

    private fun launchCoverHomeIfAvailable(force: Boolean) {
        if (isDeviceLocked()) {
            hideNavigation()
            return
        }

        val coverDisplay = coverDisplay() ?: return
        if (coverDisplay.state == Display.STATE_OFF) return

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLaunchAt < LAUNCH_COOLDOWN_MS) return
        lastLaunchAt = now

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_COVER_HOME
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = COVER_DISPLAY_ID
        }

        runCatching {
            startActivity(intent, options.toBundle())
        }
    }

    private fun scheduleNavigation() {
        handler.removeCallbacks(showNavigationRunnable)
        handler.postDelayed(showNavigationRunnable, 80L)
        handler.postDelayed(showNavigationRetryRunnable, 420L)
    }

    private val showNavigationRunnable = Runnable {
        showNavigation()
    }

    private val showNavigationRetryRunnable = Runnable {
        if (navView == null) showNavigation()
    }

    private fun showNavigation() {
        if (navView != null || isDeviceLocked()) return

        val coverDisplay = coverDisplay() ?: return
        if (coverDisplay.state == Display.STATE_OFF) return

        val displayContext = createDisplayContext(coverDisplay)
        val windowManager = displayContext.getSystemService(WindowManager::class.java) ?: return
        val density = displayContext.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val background = GradientDrawable().apply {
            setColor(Color.argb(205, 18, 18, 18))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }

        val container = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            this.background = background
            setPadding(dp(4), dp(2), dp(4), dp(2))
            elevation = dp(10).toFloat()
        }

        fun navButton(label: String, description: String, action: () -> Unit): TextView {
            return TextView(displayContext).apply {
                text = label
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = description
                minWidth = dp(44)
                minHeight = dp(40)
                setPadding(dp(7), 0, dp(7), 0)
                setOnClickListener { action() }
            }
        }

        container.addView(
            navButton("‹", "Back") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        )

        container.addView(
            navButton("●", "FlipHome") {
                hideNavigation()
                launchCoverHomeIfAvailable(force = true)
            }
        )

        container.addView(
            navButton("▦", "Recents") {
                suppressSystemUiHomeUntil = SystemClock.elapsedRealtime() + RECENTS_GRACE_MS
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(46),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(8)
            title = "FlipHome navigation"
        }

        runCatching {
            windowManager.addView(container, params)
            navWindowManager = windowManager
            navView = container
        }
    }

    private fun hideNavigation() {
        handler.removeCallbacks(showNavigationRunnable)
        handler.removeCallbacks(showNavigationRetryRunnable)

        val view = navView ?: return
        runCatching {
            navWindowManager?.removeViewImmediate(view)
        }
        navView = null
        navWindowManager = null
    }

    companion object {
        const val COVER_DISPLAY_ID = 1
        const val ACTION_OPEN_COVER_HOME = "com.fliphomeos.app.OPEN_COVER_HOME"

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val LAUNCH_COOLDOWN_MS = 650L
        private const val WAKE_CHECK_INTERVAL_MS = 250L
        private const val MAX_WAKE_CHECKS = 20
        private const val RECENTS_GRACE_MS = 1800L

        private val PERMISSION_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )
    }
}
