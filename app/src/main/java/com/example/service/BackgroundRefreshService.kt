package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.browser.SharedWebViewManager
import com.example.browser.UrlUtils
import com.example.browser.WebViewHostMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Foreground Service keeping the user's logged-in WebView actively reloading in the background.
 * Acquires a PARTIAL_WAKE_LOCK, runs a drift-compensated coroutine timer, hosts the WebView
 * inside an invisible 0x0 WindowManager overlay when the app is minimized, and displays a
 * persistent notification with live URL, refresh count, countdown, and Stop action.
 */
class BackgroundRefreshService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshTimerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isInBackgroundMode: Boolean = false

    @Volatile
    private var activeIntervalSeconds: Int = UrlUtils.DEFAULT_INTERVAL_SECONDS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_REFRESH
        when (action) {
            ACTION_START_REFRESH -> {
                val interval = intent?.getIntExtra(
                    EXTRA_INTERVAL_SECONDS,
                    SharedWebViewManager.sessionState.value.intervalSeconds
                ) ?: SharedWebViewManager.sessionState.value.intervalSeconds

                activeIntervalSeconds = interval.coerceIn(
                    UrlUtils.MIN_INTERVAL_SECONDS,
                    UrlUtils.MAX_INTERVAL_SECONDS
                )
                isInBackgroundMode = intent?.getBooleanExtra(EXTRA_BACKGROUND_MODE, false) == true

                startAsForeground()
                acquireWakeLock()

                if (isInBackgroundMode) {
                    serviceScope.launch(Dispatchers.Main) {
                        SharedWebViewManager.attachToBackgroundOverlay(this@BackgroundRefreshService)
                        updateNotification()
                    }
                }

                SharedWebViewManager.updateAutoRefreshState(
                    isActive = true,
                    intervalSeconds = activeIntervalSeconds,
                    secondsRemaining = activeIntervalSeconds,
                    isServiceRunning = true,
                    isWakeLockHeld = wakeLock?.isHeld == true
                )
                SharedWebViewManager.appendLog(
                    "Auto-refresh started (${activeIntervalSeconds}s interval)",
                    isBackground = isInBackgroundMode
                )
                startOrRestartTimerLoop()
                updateNotification()
            }

            ACTION_ENTER_BACKGROUND_OVERLAY -> {
                val state = SharedWebViewManager.sessionState.value
                if (!state.isAutoRefreshActive) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                activeIntervalSeconds = state.intervalSeconds
                isInBackgroundMode = true
                startAsForeground()
                acquireWakeLock()

                serviceScope.launch(Dispatchers.Main) {
                    SharedWebViewManager.attachToBackgroundOverlay(this@BackgroundRefreshService)
                    updateNotification()
                }

                if (refreshTimerJob?.isActive != true) {
                    startOrRestartTimerLoop()
                }
                updateNotification()
            }

            ACTION_ENTER_FOREGROUND_UI -> {
                isInBackgroundMode = false
                serviceScope.launch(Dispatchers.Main) {
                    SharedWebViewManager.detachFromBackgroundOverlay()
                    if (SharedWebViewManager.sessionState.value.isAutoRefreshActive) {
                        updateNotification()
                    }
                }
            }

            ACTION_UPDATE_INTERVAL -> {
                val newInterval = intent?.getIntExtra(
                    EXTRA_INTERVAL_SECONDS,
                    activeIntervalSeconds
                ) ?: activeIntervalSeconds
                val clamped = newInterval.coerceIn(
                    UrlUtils.MIN_INTERVAL_SECONDS,
                    UrlUtils.MAX_INTERVAL_SECONDS
                )
                if (clamped != activeIntervalSeconds && SharedWebViewManager.sessionState.value.isAutoRefreshActive) {
                    activeIntervalSeconds = clamped
                    SharedWebViewManager.updateAutoRefreshState(
                        isActive = true,
                        intervalSeconds = clamped,
                        secondsRemaining = clamped,
                        isServiceRunning = true,
                        isWakeLockHeld = wakeLock?.isHeld == true
                    )
                    SharedWebViewManager.appendLog(
                        "Interval updated to ${clamped}s",
                        isBackground = isInBackgroundMode
                    )
                    startOrRestartTimerLoop()
                    updateNotification()
                }
            }

            ACTION_RELOAD_NOW -> {
                if (SharedWebViewManager.sessionState.value.isAutoRefreshActive) {
                    SharedWebViewManager.reloadNow(
                        context = this,
                        isAutoRefreshTick = true,
                        isBackground = isInBackgroundMode
                    )
                    startOrRestartTimerLoop()
                    updateNotification()
                }
            }

            ACTION_STOP_REFRESH -> {
                stopAutoRefreshAndShutdown()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startOrRestartTimerLoop() {
        refreshTimerJob?.cancel()
        refreshTimerJob = serviceScope.launch {
            var nextTriggerAtMs = SystemClock.elapsedRealtime() + activeIntervalSeconds * 1000L
            SharedWebViewManager.updateCountdown(activeIntervalSeconds)

            while (isActive && SharedWebViewManager.sessionState.value.isAutoRefreshActive) {
                val nowMs = SystemClock.elapsedRealtime()
                val remainingMs = nextTriggerAtMs - nowMs

                if (remainingMs <= 0L) {
                    // Trigger reload on main thread while advancing schedule cleanly without drift.
                    SharedWebViewManager.reloadNow(
                        context = this@BackgroundRefreshService,
                        isAutoRefreshTick = true,
                        isBackground = isInBackgroundMode
                    )
                    val intervalMs = activeIntervalSeconds * 1000L
                    nextTriggerAtMs = SystemClock.elapsedRealtime() + intervalMs
                    SharedWebViewManager.updateCountdown(activeIntervalSeconds)
                    updateNotification()
                    delay(minOf(1000L, intervalMs))
                } else {
                    val remainingSeconds = ceil(remainingMs / 1000.0).toInt()
                        .coerceIn(1, activeIntervalSeconds)
                    SharedWebViewManager.updateCountdown(remainingSeconds)
                    updateNotification()
                    delay(minOf(1000L, remainingMs))
                }
            }
        }
    }

    private fun stopAutoRefreshAndShutdown() {
        refreshTimerJob?.cancel()
        refreshTimerJob = null
        isInBackgroundMode = false

        SharedWebViewManager.detachFromBackgroundOverlay()
        releaseWakeLock()
        SharedWebViewManager.updateAutoRefreshState(
            isActive = false,
            intervalSeconds = activeIntervalSeconds,
            secondsRemaining = activeIntervalSeconds,
            isServiceRunning = false,
            isWakeLockHeld = false
        )
        SharedWebViewManager.appendLog("Auto-refresh stopped", isBackground = false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing != null && existing.isHeld) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = SharedWebViewManager.sessionState.value
        val displayHost = UrlUtils.compactDisplayHost(state.currentUrl)
        val fullUrl = state.currentUrl.ifBlank { displayHost }

        val title = when {
            isInBackgroundMode && state.hostMode == WebViewHostMode.BACKGROUND_OVERLAY ->
                getString(R.string.notif_title_background)
            isInBackgroundMode ->
                getString(R.string.notif_title_no_overlay)
            else ->
                getString(R.string.notif_title_foreground)
        }

        val hostLabel = when (state.hostMode) {
            WebViewHostMode.BACKGROUND_OVERLAY -> getString(R.string.diagnostics_host_overlay)
            WebViewHostMode.FOREGROUND_UI -> getString(R.string.diagnostics_host_foreground)
            WebViewHostMode.DETACHED -> getString(R.string.diagnostics_host_detached)
        }

        val summaryText = getString(
            R.string.notif_summary_format,
            state.refreshCount,
            state.secondsUntilNextRefresh,
            displayHost
        )

        val expandedText = getString(
            R.string.notif_expanded_format,
            fullUrl,
            state.refreshCount,
            state.intervalSeconds,
            state.secondsUntilNextRefresh,
            hostLabel
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BackgroundRefreshService::class.java).apply {
            action = ACTION_STOP_REFRESH
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reloadNowIntent = Intent(this, BackgroundRefreshService::class.java).apply {
            action = ACTION_RELOAD_NOW
        }
        val reloadPendingIntent = PendingIntent.getService(
            this,
            REQUEST_RELOAD,
            reloadNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_pulse)
            .setContentTitle(title)
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                getString(R.string.notif_action_stop),
                stopPendingIntent
            )
            .addAction(
                0,
                getString(R.string.notif_action_reload),
                reloadPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        refreshTimerJob?.cancel()
        SharedWebViewManager.detachFromBackgroundOverlay()
        releaseWakeLock()
        SharedWebViewManager.updateAutoRefreshState(
            isActive = false,
            intervalSeconds = activeIntervalSeconds,
            secondsRemaining = activeIntervalSeconds,
            isServiceRunning = false,
            isWakeLockHeld = false
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pulseweb_background_refresh"
        private const val NOTIFICATION_ID = 4109
        private const val WAKE_LOCK_TAG = "PulseWeb:BackgroundRefreshWakeLock"

        private const val REQUEST_OPEN_APP = 101
        private const val REQUEST_STOP = 102
        private const val REQUEST_RELOAD = 103

        const val ACTION_START_REFRESH = "com.example.action.START_REFRESH"
        const val ACTION_STOP_REFRESH = "com.example.action.STOP_REFRESH"
        const val ACTION_ENTER_BACKGROUND_OVERLAY = "com.example.action.ENTER_BACKGROUND_OVERLAY"
        const val ACTION_ENTER_FOREGROUND_UI = "com.example.action.ENTER_FOREGROUND_UI"
        const val ACTION_UPDATE_INTERVAL = "com.example.action.UPDATE_INTERVAL"
        const val ACTION_RELOAD_NOW = "com.example.action.RELOAD_NOW"

        const val EXTRA_INTERVAL_SECONDS = "extra_interval_seconds"
        const val EXTRA_BACKGROUND_MODE = "extra_background_mode"

        fun startAutoRefresh(
            context: Context,
            intervalSeconds: Int,
            inBackground: Boolean = false
        ) {
            val intent = Intent(context, BackgroundRefreshService::class.java).apply {
                action = ACTION_START_REFRESH
                putExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds)
                putExtra(EXTRA_BACKGROUND_MODE, inBackground)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                SharedWebViewManager.appendLog(
                    "Unable to start foreground service: ${it.localizedMessage}",
                    isBackground = inBackground
                )
            }
        }

        fun enterBackgroundOverlay(context: Context) {
            val intent = Intent(context, BackgroundRefreshService::class.java).apply {
                action = ACTION_ENTER_BACKGROUND_OVERLAY
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                SharedWebViewManager.appendLog(
                    "Background handoff notice: ${it.localizedMessage}",
                    isBackground = true
                )
            }
        }

        fun enterForegroundUi(context: Context) {
            if (!SharedWebViewManager.sessionState.value.isServiceRunning) {
                SharedWebViewManager.detachFromBackgroundOverlay()
                return
            }
            val intent = Intent(context, BackgroundRefreshService::class.java).apply {
                action = ACTION_ENTER_FOREGROUND_UI
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                SharedWebViewManager.detachFromBackgroundOverlay()
            }
        }

        fun updateInterval(context: Context, intervalSeconds: Int) {
            if (!SharedWebViewManager.sessionState.value.isServiceRunning) return
            val intent = Intent(context, BackgroundRefreshService::class.java).apply {
                action = ACTION_UPDATE_INTERVAL
                putExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds)
            }
            runCatching { context.startService(intent) }
        }

        fun stopAutoRefresh(context: Context) {
            if (!SharedWebViewManager.sessionState.value.isServiceRunning) {
                SharedWebViewManager.detachFromBackgroundOverlay()
                SharedWebViewManager.updateAutoRefreshState(
                    isActive = false,
                    isServiceRunning = false,
                    isWakeLockHeld = false
                )
                return
            }
            val intent = Intent(context, BackgroundRefreshService::class.java).apply {
                action = ACTION_STOP_REFRESH
            }
            runCatching { context.startService(intent) }
        }
    }
}
