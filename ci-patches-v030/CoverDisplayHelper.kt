package com.fliphomeos.app.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Resolves the cover display without hard-coding display id 1.
 *
 * Foldables can report transient display power states while folding/unfolding.
 * Treat ON, DOZE, ON_SUSPEND and VR as usable so a short state transition
 * does not tear down and recreate the launcher unnecessarily.
 */
class CoverDisplayHelper(context: Context) {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    fun getCoverDisplay(): Display? {
        val presentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        presentation.firstOrNull { it.isUsableCoverDisplay() }?.let { return it }

        return displayManager.displays.firstOrNull { it.isUsableCoverDisplay() }
    }

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
