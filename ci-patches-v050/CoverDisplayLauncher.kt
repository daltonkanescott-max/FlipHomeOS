package com.fliphomeos.app.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.view.Display

/**
 * Routes user-selected apps onto the active cover display.
 *
 * NEW_TASK + MULTIPLE_TASK isolates the cover instance from a task that may
 * already exist on the inner screen. Launching through AccessibilityService
 * first avoids Android silently dropping a background launch.
 */
object CoverDisplayLauncher {

    fun launch(activity: Activity, intent: Intent) {
        val displayId = CoverDisplayHelper(activity).getCoverDisplayId()
            ?: activity.display?.displayId
            ?: Display.DEFAULT_DISPLAY

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        val dispatched = CoverTakeoverAccessibilityService.startActivityOnDisplay(
            intent = intent,
            displayId = displayId
        )

        if (!dispatched) {
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }.toBundle()
            activity.startActivity(intent, options)
        }

        CoverTakeoverAccessibilityService.markCoverAppLaunch()
    }
}
