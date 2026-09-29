package com.fliphomeos.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import androidx.core.app.NotificationCompat

/**
 * Persistent cover runtime.
 *
 * This service owns cover-display lifetime monitoring. It deliberately does not
 * launch arbitrary apps itself because modern Android can silently reject
 * background activity launches from a normal Service. Those launches are routed
 * through the connected AccessibilityService instead.
 */
class CoverRuntimeService : Service() {

    private lateinit var displayManager: DisplayManager
    private lateinit var displayHelper: CoverDisplayHelper
    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiverRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) =
            scheduleHomeIfCoverActive(250L, force = false)

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) =
            scheduleHomeIfCoverActive(350L, force = false)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> scheduleHomeIfCoverActive(180L, force = true)
                Intent.ACTION_USER_PRESENT -> scheduleHomeIfCoverActive(80L, force = true)
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacksAndMessages(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayHelper = CoverDisplayHelper(this)

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        displayManager.registerDisplayListener(displayListener, handler)
        registerScreenReceiver()
        scheduleHomeIfCoverActive(350L, force = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleHomeIfCoverActive(120L, force = false)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var pendingForceHome = false

    private fun scheduleHomeIfCoverActive(delayMs: Long, force: Boolean) {
        pendingForceHome = pendingForceHome || force
        handler.removeCallbacks(showHomeRunnable)
        handler.postDelayed(showHomeRunnable, delayMs)
    }

    private val showHomeRunnable = Runnable {
        val force = pendingForceHome
        pendingForceHome = false

        val cover = displayHelper.getCoverDisplay() ?: return@Runnable
        if (cover.state == Display.STATE_OFF) return@Runnable

        // Display topology/state callbacks also fire while launching apps. Do not
        // interpret those as "return Home" or the launcher will jump over a movie,
        // call, game, etc. A real screen wake is allowed to reset the session.
        if (!force && CoverTakeoverAccessibilityService.isCoverAppSessionActive()) {
            return@Runnable
        }

        CoverTakeoverAccessibilityService.requestCoverHome(
            reason = if (force) "screen_wake" else "display_change",
            force = force
        )
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FlipHome cover runtime",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the FlipHome cover launcher ready."
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FlipHome OS")
            .setContentText("Cover launcher is active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL_ID = "fliphome_cover_runtime"
        private const val NOTIFICATION_ID = 2501
    }
}
