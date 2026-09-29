package com.fliphomeos.app.service

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

object CoverSetupHelper {
    private var dialogShowing = false

    fun ensureReady(activity: Activity) {
        val overlayGranted = Settings.canDrawOverlays(activity)
        val accessibilityGranted = isAccessibilityEnabled(activity)

        if (overlayGranted && accessibilityGranted) {
            ContextCompat.startForegroundService(
                activity,
                CoverOverlayService.startIntent(activity)
            )
            return
        }

        if (dialogShowing || activity.isFinishing) return
        dialogShowing = true

        val message = buildString {
            append("FlipHome needs two special-access settings to behave like a real cover launcher.\n\n")
            append(if (overlayGranted) "✓ Appear on top enabled" else "• Enable Appear on top")
            append("\n")
            append(if (accessibilityGranted) "✓ Cover controls enabled" else "• Enable FlipHome cover controls in Accessibility")
            append("\n\n")
            append("If Accessibility is greyed out because this APK was sideloaded, open App info, tap the three-dot menu, choose Allow restricted settings, then return here.")
        }

        AlertDialog.Builder(activity)
            .setTitle("Finish FlipHome setup")
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
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    private fun isAccessibilityEnabled(activity: Activity): Boolean {
        val target = ComponentName(
            activity,
            CoverInputAccessibilityService::class.java
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.split(':').any { it.equals(target, ignoreCase = true) }
    }
}