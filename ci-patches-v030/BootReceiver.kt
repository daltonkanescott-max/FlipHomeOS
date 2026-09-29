package com.fliphomeos.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, CoverHomeService::class.java)
                .setAction(CoverHomeService.ACTION_START)
        )
    }
}
