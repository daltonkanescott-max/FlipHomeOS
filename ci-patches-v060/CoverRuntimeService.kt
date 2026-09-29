package com.fliphomeos.app.service

import android.app.KeyguardManager
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

class CoverRuntimeService : Service() {
    private lateinit var displayManager: DisplayManager
    private lateinit var displayHelper: CoverDisplayHelper
    private lateinit var keyguardManager: KeyguardManager
    private val handler = Handler(Looper.getMainLooper())
    private var screenReceiverRegistered = false
    private var pendingForceHome = false
    private var lastLocked: Boolean? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleHome(250L, false)
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = scheduleHome(350L, false)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> scheduleHome(180L, true)
                Intent.ACTION_USER_PRESENT -> scheduleHome(80L, true)
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(showHomeRunnable)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayHelper = CoverDisplayHelper(this)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        displayManager.registerDisplayListener(displayListener, handler)
        registerScreenReceiver()
        handler.post(lockPollRunnable)
        scheduleHome(350L, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleHome(120L, false)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        if (screenReceiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleHome(delayMs: Long, force: Boolean) {
        pendingForceHome = pendingForceHome || force
        handler.removeCallbacks(showHomeRunnable)
        handler.postDelayed(showHomeRunnable, delayMs)
    }

    private val showHomeRunnable = Runnable {
        val force = pendingForceHome
        pendingForceHome = false
        val cover = displayHelper.getCoverDisplay() ?: return@Runnable
        if (cover.state == Display.STATE_OFF) return@Runnable

        if (!force && CoverTakeoverAccessibilityService.isSuppressedByExternalApp()) {
            return@Runnable
        }

        CoverTakeoverAccessibilityService.requestCoverHome(
            reason = if (force) "screen_wake" else "display_change",
            force = force
        )
    }

    private val lockPollRunnable = object : Runnable {
        override fun run() {
            val locked = keyguardManager.isDeviceLocked
            if (lastLocked != locked) {
                lastLocked = locked
                CoverTakeoverAccessibilityService.onLockStatePolled(locked)
            }
            handler.postDelayed(this, 10_000L)
        }
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "FlipHome cover runtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the FlipHome cover launcher ready." }
        )
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
