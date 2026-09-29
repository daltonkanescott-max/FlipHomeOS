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
import java.lang.ref.WeakReference

class CoverTakeoverAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayHelper: CoverDisplayHelper

    private var lastHomeLaunchAt = 0L
    private var receiverRegistered = false
    private var wakeCheckAttempts = 0

    private var suppressSystemUiHomeUntil = 0L
    private var systemUiCandidateGeneration = 0L
    private var lastWindowPackage: String? = null

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
                    handler.removeCallbacks(showNavigationRetryRunnable)
                    wakeCheckAttempts = 0
                    invalidateSystemUiCandidate()
                    hideNavigation()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = WeakReference(this)
        displayHelper = CoverDisplayHelper(this)

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
            invalidateSystemUiCandidate()
            hideNavigation()
            return
        }

        // Samsung does not always report cover-app events with the correct displayId.
        // Use the real secondary-display hardware state instead of trusting event.displayId.
        if (!isCoverDisplayActive()) {
            invalidateSystemUiCandidate()
            hideNavigation()
            return
        }

        val eventPackage = event.packageName?.toString()?.trim().orEmpty()
        if (eventPackage.isBlank()) return
        lastWindowPackage = eventPackage

        when {
            eventPackage == packageName -> {
                invalidateSystemUiCandidate()
                hideNavigation()
            }

            eventPackage == SYSTEM_UI_PACKAGE ||
                eventPackage.startsWith(SAMSUNG_SYSTEM_UI_PREFIX) -> {
                hideNavigation()
                if (SystemClock.elapsedRealtime() >= suppressSystemUiHomeUntil) {
                    scheduleStableSystemUiReclaim()
                }
            }

            eventPackage in PERMISSION_PACKAGES -> {
                invalidateSystemUiCandidate()
                hideNavigation()
            }

            else -> {
                // Do not wait for another accessibility event before making nav available.
                invalidateSystemUiCandidate()
                scheduleNavigation()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        invalidateSystemUiCandidate()
        hideNavigation()

        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }

        if (activeService?.get() === this) {
            activeService = null
        }
        super.onDestroy()
    }

    private fun isDeviceLocked(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceLocked == true
    }

    private fun coverDisplay(): Display? =
        if (::displayHelper.isInitialized) displayHelper.getCoverDisplay() else null

    private fun isCoverDisplayActive(): Boolean =
        coverDisplay()?.let { it.state != Display.STATE_OFF } == true

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

        val cover = coverDisplay() ?: return
        if (cover.state == Display.STATE_OFF) return

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastHomeLaunchAt < HOME_LAUNCH_COOLDOWN_MS) return
        lastHomeLaunchAt = now
        invalidateSystemUiCandidate()

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_COVER_HOME
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = cover.displayId
        }

        runCatching {
            startActivity(intent, options.toBundle())
        }
    }

    private fun dispatchExternalIntent(intent: Intent): Boolean {
        if (isDeviceLocked()) return false

        val cover = coverDisplay() ?: return false
        if (cover.state == Display.STATE_OFF) return false

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = cover.displayId
        }.toBundle()

        val launched = runCatching {
            // Launch first, then mutate navigation/overlay state. Hiding first can
            // remove the very privilege Android uses to accept the cover launch.
            startActivity(intent, options)
            true
        }.getOrDefault(false)

        if (launched) {
            prepareForExternalAppLaunch()
        }
        return launched
    }

    private fun prepareForExternalAppLaunch() {
        suppressSystemUiHomeUntil =
            SystemClock.elapsedRealtime() + APP_LAUNCH_SYSTEM_UI_GRACE_MS

        invalidateSystemUiCandidate()

        // Some apps emit sparse or delayed accessibility events. Explicitly request
        // nav now so the user is never trapped without Back/Home/Recents.
        scheduleNavigation()
    }

    private fun scheduleStableSystemUiReclaim() {
        val generation = ++systemUiCandidateGeneration

        handler.postDelayed({
            if (generation != systemUiCandidateGeneration) return@postDelayed
            if (isDeviceLocked() || !isCoverDisplayActive()) return@postDelayed
            if (SystemClock.elapsedRealtime() < suppressSystemUiHomeUntil) return@postDelayed

            val pkg = lastWindowPackage.orEmpty()
            val stillSystemUi =
                pkg == SYSTEM_UI_PACKAGE || pkg.startsWith(SAMSUNG_SYSTEM_UI_PREFIX)

            if (stillSystemUi) {
                launchCoverHomeIfAvailable(force = true)
            }
        }, SYSTEM_UI_STABILITY_MS)
    }

    private fun invalidateSystemUiCandidate() {
        systemUiCandidateGeneration += 1L
    }

    private fun scheduleNavigation() {
        handler.removeCallbacks(showNavigationRunnable)
        handler.removeCallbacks(showNavigationRetryRunnable)
        handler.postDelayed(showNavigationRunnable, 90L)
        handler.postDelayed(showNavigationRetryRunnable, 450L)
    }

    private val showNavigationRunnable = Runnable {
        showNavigation()
    }

    private val showNavigationRetryRunnable = Runnable {
        if (navView == null) showNavigation()
    }

    private fun showNavigation() {
        if (navView != null || isDeviceLocked()) return

        val cover = coverDisplay() ?: return
        if (cover.state == Display.STATE_OFF) return

        val displayContext = createDisplayContext(cover)
        val windowContext = runCatching {
            displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null
            )
        }.getOrElse { displayContext }

        val windowManager =
            windowContext.getSystemService(WindowManager::class.java) ?: return
        val density = windowContext.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val background = GradientDrawable().apply {
            setColor(Color.argb(215, 14, 14, 14))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(110, 255, 255, 255))
        }

        val container = LinearLayout(windowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            this.background = background
            setPadding(dp(4), dp(2), dp(4), dp(2))
            elevation = dp(10).toFloat()
        }

        fun navButton(
            label: String,
            description: String,
            action: () -> Unit
        ): TextView = TextView(windowContext).apply {
            text = label
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = description
            minWidth = dp(46)
            minHeight = dp(40)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { action() }
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
                suppressSystemUiHomeUntil =
                    SystemClock.elapsedRealtime() + RECENTS_GRACE_MS
                invalidateSystemUiCandidate()
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
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
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
        const val ACTION_OPEN_COVER_HOME = "com.fliphomeos.app.OPEN_COVER_HOME"

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val SAMSUNG_SYSTEM_UI_PREFIX = "com.samsung.systemui"

        private const val HOME_LAUNCH_COOLDOWN_MS = 650L
        private const val WAKE_CHECK_INTERVAL_MS = 250L
        private const val MAX_WAKE_CHECKS = 20

        private const val APP_LAUNCH_SYSTEM_UI_GRACE_MS = 1_500L
        private const val SYSTEM_UI_STABILITY_MS = 650L
        private const val RECENTS_GRACE_MS = 2_400L

        private val PERMISSION_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

        @Volatile
        private var activeService:
            WeakReference<CoverTakeoverAccessibilityService>? = null

        fun launchIntentOnCover(intent: Intent): Boolean =
            activeService?.get()?.dispatchExternalIntent(intent) ?: false

        fun notifyFallbackExternalLaunch() {
            activeService?.get()?.prepareForExternalAppLaunch()
        }

        fun activeCoverDisplayId(): Int? =
            activeService?.get()?.coverDisplay()?.displayId
    }
}
