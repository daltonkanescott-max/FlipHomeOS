package com.fliphomeos.app.service

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object TakeoverSetup {
    fun promptIfNeeded(activity: Activity) {
        if (activity.display?.displayId == CoverTakeoverAccessibilityService.COVER_DISPLAY_ID) return

        val overlayGranted = Settings.canDrawOverlays(activity)
        val accessibilityGranted = isAccessibilityEnabled(activity)
        if (overlayGranted && accessibilityGranted) return

        val message = buildString {
            append("FlipHome needs two special-access settings to take over the cover screen.\n\n")
            append(if (overlayGranted) "✓ Appear on top enabled" else "• Appear on top is not enabled")
            append("\n")
            append(if (accessibilityGranted) "✓ Cover takeover enabled" else "• Accessibility cover takeover is not enabled")
            append("\n\n")
            append("Because this APK is sideloaded, Android 13+ may also require App info → ⋮ → Allow restricted settings before Accessibility can be switched on.")
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
