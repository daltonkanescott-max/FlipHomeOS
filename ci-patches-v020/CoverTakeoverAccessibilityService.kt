package com.fliphomeos.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.fliphomeos.app.ui.MainActivity

class CoverTakeoverAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLaunchAt = 0L
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> scheduleCoverHome(90L)
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

        scheduleCoverHome(250L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }

        if (event.displayId != COVER_DISPLAY_ID) return

        val eventPackage = event.packageName?.toString() ?: return

        if (eventPackage == packageName) return

        // When Samsung's stock cover UI becomes visible again, restore FlipHome.
        if (eventPackage == SYSTEM_UI_PACKAGE) {
            scheduleCoverHome(110L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun scheduleCoverHome(delayMs: Long) {
        handler.removeCallbacks(launchRunnable)
        handler.postDelayed(launchRunnable, delayMs)
    }

    private val launchRunnable = Runnable {
        launchCoverHomeIfAvailable()
    }

    private fun launchCoverHomeIfAvailable() {
        if (!Settings.canDrawOverlays(this)) return

        val displayManager = getSystemService(DisplayManager::class.java)
        val coverDisplay = displayManager?.getDisplay(COVER_DISPLAY_ID) ?: return

        // The external display is normally off while the phone is unfolded.
        if (coverDisplay.state == Display.STATE_OFF) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < LAUNCH_COOLDOWN_MS) return
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

    companion object {
        const val COVER_DISPLAY_ID = 1
        const val ACTION_OPEN_COVER_HOME = "com.fliphomeos.app.OPEN_COVER_HOME"

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val LAUNCH_COOLDOWN_MS = 700L
    }
}
