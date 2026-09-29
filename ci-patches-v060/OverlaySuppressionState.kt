package com.fliphomeos.app.service

enum class OverlaySuppressionReason {
    NONE,
    APP_LAUNCH,
    DEVICE_LOCKED,
    INCOMING_CALL,
    TEMPORARILY_DISABLED
}

data class OverlaySuppressionState(
    val reason: OverlaySuppressionReason = OverlaySuppressionReason.NONE,
    val sinceElapsedRealtime: Long = 0L
) {
    val isSuppressed: Boolean get() = reason != OverlaySuppressionReason.NONE
}
