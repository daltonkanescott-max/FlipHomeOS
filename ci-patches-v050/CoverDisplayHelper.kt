package com.fliphomeos.app.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Resolves the active cover display dynamically instead of assuming ID 1.
 *
 * Samsung can briefly move the cover panel through DOZE / ON_SUSPEND while the
 * fold state changes. Those states are still usable and should not cause the
 * launcher runtime to tear itself down.
 */
class CoverDisplayHelper(context: Context) {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun getCoverDisplay(): Display? {
        displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isUsableCoverDisplay() }
            ?.let { return it }

        return displayManager.displays.firstOrNull { it.isUsableCoverDisplay() }
    }

    fun getCoverDisplayId(): Int? = getCoverDisplay()?.displayId

    private fun Display.isUsableCoverDisplay(): Boolean {
        return displayId != Display.DEFAULT_DISPLAY &&
            isValid &&
            state in USABLE_STATES
    }

    companion object {
        private val USABLE_STATES = setOf(
            Display.STATE_ON,
            Display.STATE_DOZE,
            Display.STATE_ON_SUSPEND,
            Display.STATE_VR
        )
    }
}
