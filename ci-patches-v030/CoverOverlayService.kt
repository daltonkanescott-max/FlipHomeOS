package com.fliphomeos.app.service

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.fliphomeos.app.R
import com.fliphomeos.app.data.AppRepository
import com.fliphomeos.app.data.HomePreferences
import com.fliphomeos.app.data.WallpaperDecoder
import com.fliphomeos.app.model.AppEntry
import com.fliphomeos.app.ui.HomeAdapter
import com.fliphomeos.app.ui.MainActivity
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persistent cover-display launcher host.
 *
 * FlipHome Home is a WindowManager overlay created from a Context that belongs to the physical
 * secondary display. Normal Android activities can run underneath it on the same cover display.
 *
 * When an app launches we keep Home attached, but make it transparent and non-touchable. Keeping
 * the surface resident makes Home restore quickly and reduces flashes of Samsung's stock cover UI.
 */
class CoverOverlayService : Service(), DisplayManager.DisplayListener {
    companion object {
        const val ACTION_START = "com.fliphomeos.app.action.START_COVER_ENGINE"
        const val ACTION_SHOW_HOME = "com.fliphomeos.app.action.SHOW_COVER_HOME"
        const val ACTION_STOP = "com.fliphomeos.app.action.STOP_COVER_ENGINE"

        private const val CHANNEL_ID = "fliphome_cover_engine"
        private const val NOTIFICATION_ID = 3103
        private const val DISPLAY_CHANGE_DEBOUNCE_MS = 250L

        @Volatile
        private var activeService = WeakReference<CoverOverlayService>(null)

        fun startIntent(context: Context) = Intent(context, CoverOverlayService::class.java).apply {
            action = ACTION_START
        }

        fun showHomeIntent(context: Context) = Intent(context, CoverOverlayService::class.java).apply {
            action = ACTION_SHOW_HOME
        }

        fun requestShowHome(reason: String = "external") {
            activeService.get()?.showHome(reason)
        }

        fun isHomeSuppressedForApp(): Boolean =
            activeService.get()?.homeSuppressedForApp == true
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private lateinit var prefs: HomePreferences
    private lateinit var repository: AppRepository

    private var activeDisplayId: Int? = null
    private var windowManager: WindowManager? = null
    private var homeView: View? = null
    private var navView: View? = null
    private var homeParams: WindowManager.LayoutParams? = null
    private var homeAdapter: HomeAdapter? = null
    private var screenReceiverRegistered = false

    @Volatile
    private var homeSuppressedForApp = false

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> scheduleDisplaySync(90L)
                Intent.ACTION_SCREEN_OFF -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
        displayManager = getSystemService(DisplayManager::class.java)
        prefs = HomePreferences(this)
        repository = AppRepository(this)
        displayManager.registerDisplayListener(this, mainHandler)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                detachOverlays()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_HOME -> {
                ensureForeground()
                showHome("intent")
            }
            ACTION_START -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureForeground()
                syncCoverDisplay()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { displayManager.unregisterDisplayListener(this) }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        detachOverlays()
        activeService = WeakReference(null)
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) = scheduleDisplaySync()
    override fun onDisplayRemoved(displayId: Int) = scheduleDisplaySync()
    override fun onDisplayChanged(displayId: Int) = scheduleDisplaySync()

    private fun scheduleDisplaySync(delayMs: Long = DISPLAY_CHANGE_DEBOUNCE_MS) {
        mainHandler.removeCallbacks(displaySyncRunnable)
        mainHandler.postDelayed(displaySyncRunnable, delayMs)
    }

    private val displaySyncRunnable = Runnable { syncCoverDisplay() }

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "FlipHome cover screen",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the FlipHome cover launcher ready on the outer display."
            }
        )

        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FlipHome OS")
            .setContentText("Cover launcher is active")
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun syncCoverDisplay() {
        if (!Settings.canDrawOverlays(this)) {
            detachOverlays()
            return
        }

        val cover = findUsableCoverDisplay()
        if (cover == null) {
            // Keep a resident overlay if the same physical display is still valid but simply OFF.
            // Detaching on every power-state tick guarantees a visible One UI flash on the next wake.
            val existingId = activeDisplayId
            if (existingId != null) {
                val existing = displayManager.getDisplay(existingId)
                if (existing != null && existing.isValid) return
            }
            detachOverlays()
            return
        }

        if (activeDisplayId != cover.displayId || homeView == null || navView == null) {
            attachToDisplay(cover)
        } else if (!homeSuppressedForApp) {
            showHome("display_sync")
        }
    }

    /**
     * Do not hard-code display 1. Display ids are runtime identifiers. Prefer a presentation
     * display, then any active valid non-default display.
     */
    private fun findUsableCoverDisplay(): Display? {
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isUsableCoverDisplay() }
            ?.let { return it }

        return displayManager.displays.firstOrNull { it.isUsableCoverDisplay() }
    }

    private fun Display.isUsableCoverDisplay(): Boolean {
        val usableState = state == Display.STATE_ON ||
            state == Display.STATE_DOZE ||
            state == Display.STATE_VR ||
            state == Display.STATE_ON_SUSPEND
        return displayId != Display.DEFAULT_DISPLAY && isValid && usableState
    }

    private fun attachToDisplay(display: Display) {
        detachOverlays()

        val displayContext = createDisplayContext(display)
        val overlayContext = runCatching {
            displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            )
        }.getOrNull() ?: return

        val wm = overlayContext.getSystemService(WindowManager::class.java) ?: return
        val inflater = LayoutInflater.from(overlayContext)
        val launcher = inflater.inflate(R.layout.cover_overlay_home, null, false)
        val nav = inflater.inflate(R.layout.cover_nav_overlay, null, false)

        val launcherParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val navigationParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(6, overlayContext)
            y = dp(6, overlayContext)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val attached = runCatching {
            wm.addView(launcher, launcherParams)
            wm.addView(nav, navigationParams)
            true
        }.getOrElse {
            runCatching { wm.removeView(launcher) }
            runCatching { wm.removeView(nav) }
            false
        }
        if (!attached) return

        activeDisplayId = display.displayId
        windowManager = wm
        homeView = launcher
        navView = nav
        homeParams = launcherParams

        configureLauncherView(launcher, overlayContext)
        configureNavigationView(nav)
        nav.visibility = View.GONE

        homeSuppressedForApp = false
        updateClock()
        mainHandler.removeCallbacks(clockRunnable)
        mainHandler.post(clockRunnable)
    }

    private fun configureLauncherView(root: View, context: Context) {
        val grid = root.findViewById<RecyclerView>(R.id.coverHomeGrid)
        val adapter = HomeAdapter(
            packageManager = packageManager,
            onClick = { launchAppOnCover(it) },
            onLongPress = { false }
        )
        homeAdapter = adapter
        grid.layoutManager = GridLayoutManager(context, prefs.getColumns())
        grid.adapter = adapter
        loadHomeApps()
        applyWallpaper(root)
    }

    private fun configureNavigationView(root: View) {
        root.findViewById<View>(R.id.coverBackButton).setOnClickListener {
            CoverInputAccessibilityService.performBack()
        }
        root.findViewById<View>(R.id.coverHomeButton).setOnClickListener {
            showHome("nav_home")
        }
        root.findViewById<View>(R.id.coverRecentsButton).setOnClickListener {
            CoverInputAccessibilityService.performRecents()
        }
    }

    private fun loadHomeApps() {
        val allApps = repository.getLauncherApps()
        var packages = prefs.getHomePackages()
        if (packages.isEmpty()) {
            packages = repository.makeStarterLayout(allApps)
            prefs.setHomePackages(packages)
        }
        val byPackage = allApps.associateBy { it.packageName }
        homeAdapter?.submitApps(packages.mapNotNull(byPackage::get))
    }

    private fun launchAppOnCover(app: AppEntry) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) return

        val displayId = activeDisplayId ?: return

        // Order matters: dispatch while the overlay is still visible, then suppress Home. Hiding
        // first can remove a background-activity-launch exemption on newer Android versions.
        val launched = CoverInputAccessibilityService.launchPackageOnDisplay(
            packageName = app.packageName,
            displayId = displayId
        ) || CoverInputAccessibilityService.fallbackLaunchFromContext(
            context = this,
            packageName = app.packageName,
            displayId = displayId
        )

        if (launched) hideHomeForApp()
    }

    private fun hideHomeForApp() {
        homeSuppressedForApp = true
        val root = homeView ?: return
        val wm = windowManager ?: return
        val params = homeParams ?: return

        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        root.alpha = 0f
        root.visibility = View.VISIBLE
        runCatching { wm.updateViewLayout(root, params) }
        navView?.visibility = View.VISIBLE
    }

    fun showHome(reason: String) {
        mainHandler.post {
            val cover = findUsableCoverDisplay() ?: return@post
            if (activeDisplayId != cover.displayId || homeView == null) {
                attachToDisplay(cover)
                return@post
            }

            homeSuppressedForApp = false
            val root = homeView ?: return@post
            val wm = windowManager ?: return@post
            val params = homeParams ?: return@post

            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            root.alpha = 1f
            root.visibility = View.VISIBLE
            runCatching { wm.updateViewLayout(root, params) }
            navView?.visibility = View.GONE
            loadHomeApps()
            applyWallpaper(root)
            updateClock()
        }
    }

    private fun applyWallpaper(root: View) {
        val image = root.findViewById<ImageView>(R.id.coverWallpaperView)
        val dim = root.findViewById<View>(R.id.coverWallpaperDim)
        dim.alpha = prefs.getWallpaperDim()
        val uri = prefs.getWallpaperUri()
        if (uri == null) {
            image.setImageDrawable(null)
            return
        }

        Thread {
            val bitmap = runCatching {
                WallpaperDecoder.decode(contentResolver, uri)
            }.getOrNull()
            mainHandler.post {
                if (homeView === root && prefs.getWallpaperUri() == uri) {
                    image.setImageBitmap(bitmap)
                }
            }
        }.start()
    }

    private fun updateClock() {
        val root = homeView ?: return
        val now = LocalDateTime.now()
        root.findViewById<TextView>(R.id.coverClockText)?.text =
            now.format(DateTimeFormatter.ofPattern("h:mm"))
        root.findViewById<TextView>(R.id.coverDateText)?.text =
            now.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
    }

    private fun detachOverlays() {
        mainHandler.removeCallbacks(clockRunnable)
        val wm = windowManager
        homeView?.let { runCatching { wm?.removeView(it) } }
        navView?.let { runCatching { wm?.removeView(it) } }
        activeDisplayId = null
        windowManager = null
        homeView = null
        navView = null
        homeParams = null
        homeAdapter = null
        homeSuppressedForApp = false
    }

    private fun dp(value: Int, context: Context): Int =
        (value * context.resources.displayMetrics.density).toInt()
}