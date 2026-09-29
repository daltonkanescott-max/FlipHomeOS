package com.fliphomeos.app.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.view.Display

object CoverDisplayLauncher {
    fun launch(activity: Activity, intent: Intent) {
        val cover = CoverDisplayHelper(activity).getCoverDisplay()
        val targetDisplayId =
            cover?.displayId ?: activity.display?.displayId ?: Display.DEFAULT_DISPLAY

        val targetPackage = intent.component?.packageName
        val isInternalFlipHomeIntent = targetPackage == activity.packageName

        if (!isInternalFlipHomeIntent &&
            CoverTakeoverAccessibilityService.launchIntentOnCover(intent)) {
            return
        }

        if (isInternalFlipHomeIntent) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        } else {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = targetDisplayId
        }

        activity.startActivity(intent, options.toBundle())

        if (!isInternalFlipHomeIntent) {
            CoverTakeoverAccessibilityService.notifyFallbackExternalLaunch()
        }
    }
}
