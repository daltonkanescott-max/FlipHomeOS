package com.fliphomeos.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
 * The actual FlipHome surface shown on the closed phone.
 *
 * This is intentionally NOT an Activity. Samsung is therefore unable to
 * redirect the launcher surface back to display 0. The AccessibilityService
 * owns this window and attaches it to a WindowContext created from the current
 * cover Display.
 */
class CoverHomeOverlay(
    private val service: CoverTakeoverAccessibilityService,
    private val onLaunch: (AppEntry) -> Unit,
    private val onOpenSettings: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val repository = AppRepository(service)
    private val prefs = HomePreferences(service)

    private var root: View? = null
    private var wm: WindowManager? = null
    private var attachedDisplayId = Display.INVALID_DISPLAY
    private var clockRunnable: Runnable? = null

    fun isShowingOn(displayId: Int): Boolean =
        root?.isAttachedToWindow == true &&
            root?.visibility == View.VISIBLE &&
            attachedDisplayId == displayId

    fun show(display: Display) {
        if (isShowingOn(display.displayId)) {
            refreshApps()
            return
        }
        attach(display)
    }

    fun hide() {
        root?.visibility = View.GONE
        stopClock()
    }

    fun destroy() {
        stopClock()
        val view = root
        val manager = wm
        if (view != null && manager != null) {
            runCatching { manager.removeViewImmediate(view) }
        }
        root = null
        wm = null
        attachedDisplayId = Display.INVALID_DISPLAY
    }

    private fun attach(display: Display) {
        destroy()

        val displayContext = service.createDisplayContext(display)
        val windowContext = displayContext.createWindowContext(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null
        )
        val density = windowContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }

        val top = LinearLayout(windowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clockBlock = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
        }
        val clock = TextView(windowContext).apply {
            id = CLOCK_ID
            textSize = 31f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val date = TextView(windowContext).apply {
            id = DATE_ID
            textSize = 14f
            setTextColor(Color.LTGRAY)
        }
        clockBlock.addView(clock)
        clockBlock.addView(date)

        val settings = TextView(windowContext).apply {
            text = "EDIT"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = pillBackground(density)
            setPadding(dp(15), dp(9), dp(15), dp(9))
            setOnClickListener { onOpenSettings() }
        }

        top.addView(
            clockBlock,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(settings)
        container.addView(top)

        val scroll = ScrollView(windowContext).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val grid = LinearLayout(windowContext).apply {
            id = GRID_ID
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(50))
        }
        scroll.addView(
            grid,
            ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FlipHome cover home"
        }

        val manager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        manager.addView(container, params)

        root = container
        wm = manager
        attachedDisplayId = display.displayId

        refreshApps()
        startClock()
    }

    private fun refreshApps() {
        val container = root as? LinearLayout ?: return
        val grid = container.findViewById<LinearLayout>(GRID_ID) ?: return
        val context = grid.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val allApps = repository.getLauncherApps()
        var packages = prefs.getHomePackages()
        if (packages.isEmpty()) {
            packages = repository.makeStarterLayout(allApps)
            prefs.setHomePackages(packages)
        }
        val byPackage = allApps.associateBy { it.packageName }
        val apps = packages.mapNotNull(byPackage::get)

        grid.removeAllViews()
        val columns = prefs.getColumns().coerceIn(3, 5)
        apps.chunked(columns).forEach { rowApps ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            repeat(columns) { index ->
                val app = rowApps.getOrNull(index)
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(5), dp(2), dp(7))
                }

                if (app != null) {
                    val icon = ImageView(context).apply {
                        setImageDrawable(runCatching {
                            service.packageManager.getApplicationIcon(app.packageName)
                        }.getOrNull())
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    cell.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))

                    cell.addView(
                        TextView(context).apply {
                            text = app.label
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            maxLines = 1
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(25)
                        )
                    )
                    cell.setOnClickListener { onLaunch(app) }
                }

                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, dp(102), 1f)
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun startClock() {
        stopClock()
        val runnable = object : Runnable {
            override fun run() {
                val container = root as? LinearLayout ?: return
                val now = LocalDateTime.now()
                container.findViewById<TextView>(CLOCK_ID)?.text =
                    now.format(DateTimeFormatter.ofPattern("h:mm"))
                container.findViewById<TextView>(DATE_ID)?.text =
                    now.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
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

    private fun pillBackground(density: Float) = GradientDrawable().apply {
        setColor(Color.rgb(31, 31, 31))
        cornerRadius = 22f * density
    }

    companion object {
        private const val CLOCK_ID = 0x5F1001
        private const val DATE_ID = 0x5F1002
        private const val GRID_ID = 0x5F1003
    }
}
