package com.fliphomeos.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display

class CoverDisplayHelper(context: Context) {
    private val appContext = context.applicationContext
    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun supportsSecondaryActivities(): Boolean =
        appContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
        )

    fun getCoverDisplay(): Display? {
        displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isUsableCoverDisplay() }
            ?.let { return it }

        return displayManager.displays.firstOrNull { it.isUsableCoverDisplay() }
    }

    fun getCoverDisplayId(): Int? = getCoverDisplay()?.displayId

    fun createCoverContext(): Context? =
        getCoverDisplay()?.let(appContext::createDisplayContext)

    private fun Display.isUsableCoverDisplay(): Boolean =
        displayId != Display.DEFAULT_DISPLAY &&
            isValid &&
            state in USABLE_STATES

    companion object {
        private val USABLE_STATES = setOf(
            Display.STATE_ON,
            Display.STATE_DOZE,
            Display.STATE_ON_SUSPEND,
            Display.STATE_VR
        )
    }
}
