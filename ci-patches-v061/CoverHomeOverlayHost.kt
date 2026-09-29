package com.fliphomeos.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fliphomeos.app.data.AppRepository
import com.fliphomeos.app.data.HomePreferences
import com.fliphomeos.app.model.AppEntry
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The actual FlipHome cover home surface.
 *
 * Unlike MainActivity, this view is attached directly to the cover display as
 * TYPE_ACCESSIBILITY_OVERLAY. Samsung therefore does not need to approve an
 * Activity launch before the launcher can exist on the Flex Window.
 */
class CoverHomeOverlayHost(
    private val service: CoverTakeoverAccessibilityService,
    private val onLaunch: (AppEntry) -> Unit,
    private val onOpenSettings: () -> Unit
) {
    private val repository = AppRepository(service)
    private val prefs = HomePreferences(service)
    private val handler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var windowManager: WindowManager? = null
    private var attachedDisplayId = Display.INVALID_DISPLAY
    private var clockView: TextView? = null
    private var dateView: TextView? = null

    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = LocalDateTime.now()
            clockView?.text = now.format(DateTimeFormatter.ofPattern("h:mm"))
            dateView?.text = now.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
            handler.postDelayed(this, 30_000L)
        }
    }

    fun isShowingOn(displayId: Int): Boolean =
        root?.isAttachedToWindow == true &&
            root?.visibility == View.VISIBLE &&
            attachedDisplayId == displayId

    fun show(display: Display) {
        if (root?.isAttachedToWindow == true && attachedDisplayId == display.displayId) {
            root?.visibility = View.VISIBLE
            startClock()
            return
        }

        destroy()

        val displayContext = service.createDisplayContext(display)
        val windowContext = displayContext.createWindowContext(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
        val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = windowContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val header = LinearLayout(windowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val timeBlock = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        val time = TextView(windowContext).apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val date = TextView(windowContext).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        timeBlock.addView(time)
        timeBlock.addView(date)
        header.addView(
            timeBlock,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val all = TextView(windowContext).apply {
            text = "ALL"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(52)
            minHeight = dp(38)
            background = pillBackground(density)
            setOnClickListener {
                // The Activity remains the full settings/editor surface on the
                // inner screen. Home itself no longer depends on that Activity.
                onOpenSettings()
            }
        }
        header.addView(all)

        container.addView(header)

        val scroll = ScrollView(windowContext).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val grid = GridLayout(windowContext).apply {
            columnCount = prefs.getColumns().coerceIn(3, 5)
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(0, dp(8), 0, dp(8))
        }

        val allApps = repository.getLauncherApps()
        var packages = prefs.getHomePackages()
        if (packages.isEmpty()) {
            packages = repository.makeStarterLayout(allApps)
            prefs.setHomePackages(packages)
        }
        val byPackage = allApps.associateBy { it.packageName }
        val apps = packages.mapNotNull(byPackage::get)

        apps.forEach { app ->
            val cell = LinearLayout(windowContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(6), dp(3), dp(5))
                setOnClickListener { onLaunch(app) }
            }

            val icon = ImageView(windowContext).apply {
                setImageDrawable(runCatching {
                    service.packageManager.getApplicationIcon(app.packageName)
                }.getOrNull())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            cell.addView(
                icon,
                LinearLayout.LayoutParams(dp(54), dp(54))
            )

            cell.addView(
                TextView(windowContext).apply {
                    text = app.label
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val spec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            val columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            grid.addView(
                cell,
                GridLayout.LayoutParams(spec, columnSpec).apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    setGravity(Gravity.FILL_HORIZONTAL)
                }
            )
        }

        scroll.addView(
            grid,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FlipHome cover home"
        }

        wm.addView(container, params)
        root = container
        windowManager = wm
        attachedDisplayId = display.displayId
        clockView = time
        dateView = date
        startClock()
    }

    fun hide() {
        root?.visibility = View.GONE
        handler.removeCallbacks(clockRunnable)
    }

    fun destroy() {
        handler.removeCallbacks(clockRunnable)
        val view = root
        val wm = windowManager
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        root = null
        windowManager = null
        attachedDisplayId = Display.INVALID_DISPLAY
        clockView = null
        dateView = null
    }

    private fun startClock() {
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
    }

    private fun pillBackground(density: Float) = GradientDrawable().apply {
        setColor(Color.rgb(28, 28, 28))
        cornerRadius = 20f * density
    }
}
