package com.fliphomeos.app.service

import android.app.ActivityOptions
import android.content.Intent
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes cover launches and preserves the critical ordering:
 * launch first, then suppress the launcher.
 */
object CoverLaunchCoordinator {
    private val launchInFlight = AtomicBoolean(false)
    @Volatile private var lastLaunchAt = 0L

    fun launchFromAccessibility(
        service: CoverTakeoverAccessibilityService,
        intent: Intent,
        displayId: Int
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < 350L) return false
        if (!launchInFlight.compareAndSet(false, true)) return false

        return try {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = displayId
            }.toBundle()

            // Important: do not hide/suppress FlipHome until Android accepted
            // the user-initiated activity launch.
            service.startActivity(intent, options)
            lastLaunchAt = now
            service.onExternalCoverAppLaunched()
            true
        } catch (_: Throwable) {
            false
        } finally {
            launchInFlight.set(false)
        }
    }
}
