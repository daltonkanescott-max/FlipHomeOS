package com.fliphomeos.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.fliphomeos.app.data.AppRepository
import com.fliphomeos.app.data.HomePreferences
import com.fliphomeos.app.data.WallpaperDecoder
import com.fliphomeos.app.databinding.ActivityMainBinding
import com.fliphomeos.app.model.AppEntry
import com.fliphomeos.app.ui.HomeAdapter
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Persistent cover-screen runtime.
 *
 * The launcher is a WindowManager overlay attached directly to the active
 * non-default display. When an app launches, the home overlay remains attached
 * but becomes transparent and NOT_TOUCHABLE. Keeping it attached makes Home
 * restoration instant while preventing an invisible window from eating taps.
 */
class CoverHomeService : Service() {

    private lateinit var displayManager: DisplayManager
    private lateinit var displayHelper: CoverDisplayHelper
    private lateinit var prefs: HomePreferences
    private lateinit var repository: AppRepository

    private val handler = Handler(Looper.getMainLooper())
    private var homeSurface: HomeSurface? = null
    private var navSurface: NavSurface? = null
    private var screenReceiverRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = retargetSoon()
        override fun onDisplayRemoved(displayId: Int) = retargetSoon()
        override fun onDisplayChanged(displayId: Int) = retargetSoon()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    handler.postDelayed({ showHome("screen_wake") }, 120L)
                }
                Intent.ACTION_SCREEN_OFF -> navSurface?.hide()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayHelper = CoverDisplayHelper(this)
        prefs = HomePreferences(this)
        repository = AppRepository(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        displayManager.registerDisplayListener(displayListener, handler)
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START, ACTION_SHOW_HOME ->
                showHome(intent?.getStringExtra(EXTRA_REASON) ?: "start")

            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
        }
        homeSurface?.destroy()
        navSurface?.destroy()
        homeSurface = null
        navSurface = null
        currentDisplayId = Display.INVALID_DISPLAY
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showHome(reason: String) {
        if (!Settings.canDrawOverlays(this)) return

        val cover = displayHelper.getCoverDisplay() ?: run {
            currentDisplayId = Display.INVALID_DISPLAY
            homeSurface?.hideCompletely()
            navSurface?.hide()
            return
        }

        currentDisplayId = cover.displayId

        val surface = homeSurface ?: HomeSurface().also { homeSurface = it }
        if (!surface.isAttachedTo(cover.displayId)) {
            surface.attach(cover)
        }
        surface.restore()
        navSurface?.hide()
    }

    private fun launchApp(app: AppEntry) {
        val cover = displayHelper.getCoverDisplay() ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)

        if (launchIntent == null) {
            Toast.makeText(this, app.label + " cannot be launched.", Toast.LENGTH_SHORT).show()
            return
        }

        // Important ordering learned from mature cover launchers:
        // dispatch the app while the overlay is still visible, then suppress it.
        // Hiding first can remove a background-activity-launch exemption and cause
        // Android to silently drop the launch.
        val launchedViaAccessibility =
            CoverTakeoverAccessibilityService.launchPackageOnDisplay(
                contextIntent = launchIntent,
                displayId = cover.displayId
            )

        val launched = if (launchedViaAccessibility) {
            true
        } else {
            runCatching {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                val options = android.app.ActivityOptions.makeBasic().apply {
                    launchDisplayId = cover.displayId
                }.toBundle()
                startActivity(launchIntent, options)
                true
            }.getOrDefault(false)
        }

        if (launched) {
            homeSurface?.suppressForApp()
            val nav = navSurface ?: NavSurface().also { navSurface = it }
            nav.show(cover)
        } else {
            Toast.makeText(
                this,
                "Could not open " + app.label + " on the cover display.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun retargetSoon() {
        handler.removeCallbacks(retargetRunnable)
        handler.postDelayed(retargetRunnable, 350L)
    }

    private val retargetRunnable = Runnable {
        val cover = displayHelper.getCoverDisplay()
        if (cover == null) {
            currentDisplayId = Display.INVALID_DISPLAY
            homeSurface?.hideCompletely()
            navSurface?.hide()
        } else {
            currentDisplayId = cover.displayId
            showHome("display_change")
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FlipHome cover service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the FlipHome cover launcher available."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FlipHome OS")
            .setContentText("Cover launcher is running")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private inner class HomeSurface {
        private var binding: ActivityMainBinding? = null
        private var windowManager: WindowManager? = null
        private var params: WindowManager.LayoutParams? = null
        private var activeDisplayId = Display.INVALID_DISPLAY
        private var homeAdapter: HomeAdapter? = null
        private var editMode = false
        private var clockRunnable: Runnable? = null

        fun isAttachedTo(displayId: Int): Boolean =
            binding?.root?.isAttachedToWindow == true && activeDisplayId == displayId

        fun attach(display: Display) {
            destroy()

            val displayContext = createDisplayContext(display)
            val windowContext = displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            )
            val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(windowContext)
            val nextBinding = ActivityMainBinding.inflate(inflater)

            val adapter = HomeAdapter(
                packageManager = packageManager,
                onClick = { app -> if (!editMode) launchApp(app) },
                onLongPress = {
                    if (!editMode) {
                        setEditMode(true)
                        true
                    } else {
                        false
                    }
                }
            )

            nextBinding.homeGrid.layoutManager = GridLayoutManager(windowContext, prefs.getColumns())
            nextBinding.homeGrid.adapter = adapter

            val drag = object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0
            ) {
                override fun isLongPressDragEnabled(): Boolean = editMode

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean = adapter.moveItem(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                )

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    prefs.setHomePackages(adapter.currentPackages())
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            }
            ItemTouchHelper(drag).attachToRecyclerView(nextBinding.homeGrid)

            nextBinding.rootContainer.setOnLongClickListener {
                setEditMode(true)
                true
            }
            nextBinding.doneButton.setOnClickListener { setEditMode(false) }
            nextBinding.gridButton.setOnClickListener {
                val next = when (prefs.getColumns()) {
                    3 -> 4
                    4 -> 5
                    else -> 3
                }
                prefs.setColumns(next)
                nextBinding.homeGrid.layoutManager = GridLayoutManager(windowContext, next)
            }
            nextBinding.appsButton.setOnClickListener {
                Toast.makeText(
                    this@CoverHomeService,
                    "App selection is configured from FlipHome settings for now.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            nextBinding.wallpaperButton.setOnClickListener {
                Toast.makeText(
                    this@CoverHomeService,
                    "Wallpaper settings are configured from FlipHome settings for now.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            val lp = WindowManager.LayoutParams(
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

            wm.addView(nextBinding.root, lp)

            binding = nextBinding
            windowManager = wm
            params = lp
            activeDisplayId = display.displayId
            homeAdapter = adapter

            loadApps()
            applyWallpaper()
            startClock()
            restore()
        }

        fun restore() {
            val root = binding?.root ?: return
            val wm = windowManager ?: return
            val lp = params ?: return

            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            root.alpha = 1f
            root.visibility = View.VISIBLE
            runCatching { wm.updateViewLayout(root, lp) }

            loadApps()
            applyWallpaper()
            startClock()
        }

        fun suppressForApp() {
            val root = binding?.root ?: return
            val wm = windowManager ?: return
            val lp = params ?: return

            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            root.alpha = 0f
            runCatching { wm.updateViewLayout(root, lp) }
            stopClock()
        }

        fun hideCompletely() {
            binding?.root?.visibility = View.GONE
            stopClock()
        }

        fun destroy() {
            stopClock()
            val root = binding?.root
            val wm = windowManager
            if (root != null && wm != null) {
                runCatching { wm.removeView(root) }
            }
            binding = null
            windowManager = null
            params = null
            activeDisplayId = Display.INVALID_DISPLAY
            homeAdapter = null
            editMode = false
        }

        private fun setEditMode(enabled: Boolean) {
            editMode = enabled
            binding?.editPanel?.visibility = if (enabled) View.VISIBLE else View.GONE
            binding?.editBadge?.visibility = if (enabled) View.VISIBLE else View.GONE
            homeAdapter?.setEditing(enabled)
        }

        private fun loadApps() {
            val adapter = homeAdapter ?: return
            val allApps = repository.getLauncherApps()
            var packages = prefs.getHomePackages()
            if (packages.isEmpty()) {
                packages = repository.makeStarterLayout(allApps)
                prefs.setHomePackages(packages)
            }
            val byPackage = allApps.associateBy { it.packageName }
            adapter.submitApps(packages.mapNotNull(byPackage::get))
        }

        private fun applyWallpaper() {
            val b = binding ?: return
            b.wallpaperDim.alpha = prefs.getWallpaperDim()
            val uri: Uri = prefs.getWallpaperUri() ?: run {
                b.wallpaperView.setImageDrawable(null)
                return
            }

            Thread {
                val bitmap = runCatching {
                    WallpaperDecoder.decode(contentResolver, uri)
                }.getOrNull()

                handler.post {
                    if (binding === b && prefs.getWallpaperUri() == uri) {
                        b.wallpaperView.setImageBitmap(bitmap)
                    }
                }
            }.start()
        }

        private fun startClock() {
            stopClock()
            val runnable = object : Runnable {
                override fun run() {
                    val b = binding ?: return
                    val now = LocalDateTime.now()
                    b.clockText.text = now.format(DateTimeFormatter.ofPattern("h:mm"))
                    b.dateText.text = now.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
                    handler.postDelayed(this, 30_000L)
                }
            }
            clockRunnable = runnable
            handler.post(runnable)
        }

        private fun stopClock() {
            clockRunnable?.let(handler::removeCallbacks)
            clockRunnable = null
        }
    }

    private inner class NavSurface {
        private var root: LinearLayout? = null
        private var wm: WindowManager? = null
        private var activeDisplayId = Display.INVALID_DISPLAY

        fun show(display: Display) {
            if (root?.isAttachedToWindow == true && activeDisplayId == display.displayId) {
                root?.visibility = View.VISIBLE
                return
            }
            destroy()

            val displayContext = createDisplayContext(display)
            val windowContext = displayContext.createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                null
            )
            val manager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = windowContext.resources.displayMetrics.density

            val container = LinearLayout(windowContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4, density), dp(2, density), dp(4, density), dp(2, density))
                background = GradientDrawable().apply {
                    setColor(Color.argb(215, 12, 12, 12))
                    cornerRadius = 14f * density
                }
            }

            fun navButton(symbol: String, action: () -> Unit): TextView =
                TextView(windowContext).apply {
                    text = symbol
                    textSize = 21f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { action() }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f
                    )
                }

            container.addView(navButton("‹") {
                CoverTakeoverAccessibilityService.performBack()
            })
            container.addView(navButton("●") {
                showHome("nav_home")
            })
            container.addView(navButton("▣") {
                CoverTakeoverAccessibilityService.performRecents()
            })

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(40, density),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(3, density)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

            manager.addView(container, lp)
            root = container
            wm = manager
            activeDisplayId = display.displayId
        }

        fun hide() {
            root?.visibility = View.GONE
        }

        fun destroy() {
            val view = root
            val manager = wm
            if (view != null && manager != null) {
                runCatching { manager.removeView(view) }
            }
            root = null
            wm = null
            activeDisplayId = Display.INVALID_DISPLAY
        }

        private fun dp(value: Int, density: Float): Int = (value * density).toInt()
    }

    companion object {
        const val ACTION_START = "com.fliphomeos.app.action.START_COVER_RUNTIME"
        const val ACTION_SHOW_HOME = "com.fliphomeos.app.action.SHOW_COVER_HOME"
        const val ACTION_STOP = "com.fliphomeos.app.action.STOP_COVER_RUNTIME"
        private const val EXTRA_REASON = "reason"
        private const val CHANNEL_ID = "fliphome_cover_runtime"
        private const val NOTIFICATION_ID = 2301

        @Volatile
        private var activeService: WeakReference<CoverHomeService>? = null

        @Volatile
        private var currentDisplayId: Int = Display.INVALID_DISPLAY

        fun requestShowHome(reason: String) {
            val service = activeService?.get()
            if (service != null) {
                service.handler.post { service.showHome(reason) }
            }
        }

        fun currentCoverDisplayId(): Int = currentDisplayId
    }
}
