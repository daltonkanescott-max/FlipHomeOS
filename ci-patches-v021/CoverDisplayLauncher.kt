package com.fliphomeos.app.service

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display

object CoverDisplayLauncher {
    private const val COVER_DISPLAY_ID = 1

    fun launch(activity: Activity, intent: Intent) {
        val displayManager = activity.getSystemService(DisplayManager::class.java)
        val cover = displayManager?.getDisplay(COVER_DISPLAY_ID)
        val targetDisplayId = if (cover != null && cover.state != Display.STATE_OFF) {
            COVER_DISPLAY_ID
        } else {
            activity.display?.displayId ?: Display.DEFAULT_DISPLAY
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = targetDisplayId
        }
        activity.startActivity(intent, options.toBundle())
    }
}
