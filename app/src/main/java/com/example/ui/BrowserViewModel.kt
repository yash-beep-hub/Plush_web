package com.example.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.browser.BrowserSessionState
import com.example.browser.PermissionReadiness
import com.example.browser.SharedWebViewManager
import com.example.browser.UrlUtils
import com.example.data.BrowserPreferencesRepository
import com.example.service.BackgroundRefreshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserUiState(
    val urlInput: String = "",
    val isUrlFieldFocused: Boolean = false,
    val intervalInput: String = UrlUtils.DEFAULT_INTERVAL_SECONDS.toString(),
    val session: BrowserSessionState = BrowserSessionState(),
    val permissions: PermissionReadiness = PermissionReadiness(),
    val isSetupBannerDismissed: Boolean = false,
    val isDiagnosticsSheetOpen: Boolean = false,
    val snackbarMessage: String? = null
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val preferencesRepository = BrowserPreferencesRepository(appContext)

    private val _uiState = MutableStateFlow(
        BrowserUiState(
            session = SharedWebViewManager.sessionState.value,
            permissions = readPermissionReadiness()
        )
    )
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = preferencesRepository.savedConfig.first()
            val liveSession = SharedWebViewManager.sessionState.value
            val initialUrl = liveSession.currentUrl.ifBlank { saved.lastUrl }
            val initialInterval = if (liveSession.isAutoRefreshActive) {
                liveSession.intervalSeconds
            } else {
                saved.intervalSeconds
            }
            _uiState.update { state ->
                state.copy(
                    urlInput = initialUrl,
                    intervalInput = initialInterval.toString()
                )
            }
            if (!liveSession.isAutoRefreshActive) {
                SharedWebViewManager.updateAutoRefreshState(
                    isActive = false,
                    intervalSeconds = initialInterval,
                    secondsRemaining = initialInterval
                )
            }
        }

        viewModelScope.launch {
            SharedWebViewManager.sessionState.collect { session ->
                _uiState.update { current ->
                    val shouldSyncUrl = !current.isUrlFieldFocused &&
                        session.currentUrl.isNotBlank() &&
                        session.currentUrl != current.urlInput
                    current.copy(
                        session = session,
                        urlInput = if (shouldSyncUrl) session.currentUrl else current.urlInput
                    )
                }
            }
        }
    }

    fun refreshPermissionStatuses() {
        _uiState.update { it.copy(permissions = readPermissionReadiness()) }
    }

    private fun readPermissionReadiness(): PermissionReadiness {
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val overlayGranted = Settings.canDrawOverlays(appContext)

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true

        return PermissionReadiness(
            notificationsGranted = notificationsGranted,
            overlayGranted = overlayGranted,
            batteryOptimizationIgnored = batteryIgnored
        )
    }

    fun onUrlInputChanged(newValue: String) {
        _uiState.update { it.copy(urlInput = newValue) }
    }

    fun onUrlFocusChanged(isFocused: Boolean) {
        _uiState.update { state ->
            val synced = if (!isFocused && state.urlInput.isBlank() && state.session.currentUrl.isNotBlank()) {
                state.session.currentUrl
            } else {
                state.urlInput
            }
            state.copy(isUrlFieldFocused = isFocused, urlInput = synced)
        }
    }

    fun clearUrlInput() {
        _uiState.update { it.copy(urlInput = "") }
    }

    fun onIntervalInputChanged(rawValue: String) {
        val digitsOnly = rawValue.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(intervalInput = digitsOnly) }

        val parsed = UrlUtils.parseIntervalSeconds(digitsOnly) ?: return
        viewModelScope.launch {
            preferencesRepository.saveIntervalSeconds(parsed)
        }
        if (_uiState.value.session.isAutoRefreshActive) {
            BackgroundRefreshService.updateInterval(appContext, parsed)
        } else {
            SharedWebViewManager.updateAutoRefreshState(
                isActive = false,
                intervalSeconds = parsed,
                secondsRemaining = parsed
            )
        }
    }

    fun navigateToCurrentInput(): Boolean {
        val normalized = UrlUtils.normalizeUrl(_uiState.value.urlInput)
        if (normalized == null) {
            showMessage(appContext.getString(R.string.error_empty_url))
            return false
        }
        loadNormalizedUrl(normalized)
        return true
    }

    fun loadPresetUrl(url: String) {
        val normalized = UrlUtils.normalizeUrl(url) ?: return
        loadNormalizedUrl(normalized)
    }

    private fun loadNormalizedUrl(normalizedUrl: String) {
        _uiState.update { it.copy(urlInput = normalizedUrl, isUrlFieldFocused = false) }
        SharedWebViewManager.loadUrl(appContext, normalizedUrl)
        viewModelScope.launch {
            preferencesRepository.saveLastUrl(normalizedUrl)
        }
    }

    fun toggleAutoRefresh() {
        val current = _uiState.value
        if (current.session.isAutoRefreshActive) {
            BackgroundRefreshService.stopAutoRefresh(appContext)
            return
        }

        val intervalSeconds = UrlUtils.parseIntervalSeconds(current.intervalInput)
        if (intervalSeconds == null) {
            showMessage(appContext.getString(R.string.error_invalid_interval))
            return
        }

        // Ensure a webpage is loaded or load whatever the user typed into the URL field.
        val targetUrl = UrlUtils.normalizeUrl(current.urlInput)
            ?: current.session.currentUrl.takeIf { it.isNotBlank() }

        if (targetUrl == null) {
            showMessage(appContext.getString(R.string.error_empty_url))
            return
        }

        if (!current.session.hasLoadedPage || targetUrl != current.session.currentUrl) {
            loadNormalizedUrl(targetUrl)
        }

        viewModelScope.launch {
            preferencesRepository.saveIntervalSeconds(intervalSeconds)
            preferencesRepository.saveLastUrl(targetUrl)
        }

        BackgroundRefreshService.startAutoRefresh(
            context = appContext,
            intervalSeconds = intervalSeconds,
            inBackground = false
        )

        if (!current.permissions.overlayGranted) {
            showMessage(appContext.getString(R.string.overlay_missing_hint))
        }
    }

    fun goBack() = SharedWebViewManager.goBack()

    fun goForward() = SharedWebViewManager.goForward()

    fun manualReloadOrStop() {
        if (_uiState.value.session.isLoading) {
            SharedWebViewManager.stopLoading()
        } else if (_uiState.value.session.hasLoadedPage) {
            SharedWebViewManager.reloadNow(
                context = appContext,
                isAutoRefreshTick = false,
                isBackground = false
            )
        } else {
            navigateToCurrentInput()
        }
    }

    fun resetRefreshCounter() = SharedWebViewManager.resetRefreshCounter()

    fun setSetupBannerDismissed(dismissed: Boolean) {
        _uiState.update { it.copy(isSetupBannerDismissed = dismissed) }
    }

    fun setDiagnosticsSheetOpen(open: Boolean) {
        if (open) refreshPermissionStatuses()
        _uiState.update { it.copy(isDiagnosticsSheetOpen = open) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
