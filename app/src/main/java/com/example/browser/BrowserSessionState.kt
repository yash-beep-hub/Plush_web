package com.example.browser

enum class WebViewHostMode {
    DETACHED,
    FOREGROUND_UI,
    BACKGROUND_OVERLAY
}

data class RefreshLogEntry(
    val id: Long,
    val timestampMillis: Long,
    val message: String,
    val isBackground: Boolean
)

data class PermissionReadiness(
    val notificationsGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false
) {
    val missingCount: Int
        get() = listOf(
            notificationsGranted,
            overlayGranted,
            batteryOptimizationIgnored
        ).count { !it }

    val allReady: Boolean
        get() = missingCount == 0
}

data class BrowserSessionState(
    val currentUrl: String = "",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val loadProgress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasLoadedPage: Boolean = false,
    val isAutoRefreshActive: Boolean = false,
    val intervalSeconds: Int = UrlUtils.DEFAULT_INTERVAL_SECONDS,
    val secondsUntilNextRefresh: Int = UrlUtils.DEFAULT_INTERVAL_SECONDS,
    val refreshCount: Int = 0,
    val lastRefreshedAtMillis: Long? = null,
    val hostMode: WebViewHostMode = WebViewHostMode.DETACHED,
    val isServiceRunning: Boolean = false,
    val isWakeLockHeld: Boolean = false,
    val lastErrorMessage: String? = null,
    val activityLog: List<RefreshLogEntry> = emptyList()
)
