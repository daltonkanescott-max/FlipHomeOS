package com.fliphomeos.app.service

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object TakeoverSetup {
    fun promptIfNeeded(activity: Activity) {
        val coverId = CoverDisplayHelper(activity).getCoverDisplay()?.displayId
        if (coverId != null && activity.display?.displayId == coverId) return

        val overlayGranted = Settings.canDrawOverlays(activity)
        val accessibilityGranted = isAccessibilityEnabled(activity)
        if (overlayGranted && accessibilityGranted) return

        val message = buildString {
            append("FlipHome needs two special-access settings for the cover-screen runtime.\n\n")
            append(
                if (overlayGranted) "✓ Appear on top enabled"
                else "• Appear on top is not enabled"
            )
            append("\n")
            append(
                if (accessibilityGranted) "✓ FlipHome cover control enabled"
                else "• Accessibility cover control is not enabled"
            )
            append("\n\n")
            append(
                "Because this APK is sideloaded, Android may require " +
                    "App info → ⋮ → Allow restricted settings before Accessibility can be enabled."
            )
        }

        AlertDialog.Builder(activity)
            .setTitle("Finish FlipHome cover setup")
            .setMessage(message)
            .setPositiveButton("Appear on top") { _, _ ->
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            .setNeutralButton("Accessibility") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun isAccessibilityEnabled(activity: Activity): Boolean {
        val component = ComponentName(
            activity,
            CoverTakeoverAccessibilityService::class.java
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }
}
