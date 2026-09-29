package com.fliphomeos.app.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.view.Display

object CoverDisplayLauncher {

    fun launch(activity: Activity, intent: Intent) {
        val helper = CoverDisplayHelper(activity)
        val displayId = helper.getCoverDisplayId()
            ?: activity.display?.displayId
            ?: Display.DEFAULT_DISPLAY

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        if (CoverTakeoverAccessibilityService.startActivityOnDisplay(intent, displayId)) {
            return
        }

        // Fallback remains associated with the cover display where Android
        // exposes it. Explicit launchDisplayId is retained as a second guard.
        val launchContext = helper.createCoverContext() ?: activity
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
        }.toBundle()

        runCatching { launchContext.startActivity(intent, options) }
            .onSuccess { CoverTakeoverAccessibilityService.markCoverAppLaunch() }
    }
}
