package com.fliphomeos.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (Settings.canDrawOverlays(context)) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    CoverOverlayService.startIntent(context)
                )
            }
        }
    }
}