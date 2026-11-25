package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.BrowserBottomTelemetryBar
import com.example.ui.components.DiagnosticsBottomSheet
import com.example.ui.components.SetupReadinessBanner
import com.example.ui.components.TopControlBar
import com.example.ui.components.WebViewHostContainer

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshPermissionStatuses()
    }

    // Re-check Overlay, Notification, and Battery Optimization status whenever returning from Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeSnackbar()
    }

    // Handle Android Back press inside WebView navigation history when available.
    BackHandler(enabled = uiState.session.canGoBack) {
        viewModel.goBack()
    }

    val requestNotificationsAction: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (uiState.permissions.notificationsGranted) {
                openAppNotificationSettings(context)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            openAppNotificationSettings(context)
        }
    }

    val openOverlaySettingsAction: () -> Unit = {
        openOverlayPermissionSettings(context)
    }

    val openBatterySettingsAction: () -> Unit = {
        openBatteryOptimizationSettings(context)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopControlBar(
                urlInput = uiState.urlInput,
                intervalInput = uiState.intervalInput,
                isAutoRefreshActive = uiState.session.isAutoRefreshActive,
                isLoading = uiState.session.isLoading,
                loadProgress = uiState.session.loadProgress,
                secondsUntilNextRefresh = uiState.session.secondsUntilNextRefresh,
                refreshCount = uiState.session.refreshCount,
                onUrlInputChanged = viewModel::onUrlInputChanged,
                onUrlFocusChanged = viewModel::onUrlFocusChanged,
                onClearUrl = viewModel::clearUrlInput,
                onNavigate = { viewModel.navigateToCurrentInput() },
                onIntervalInputChanged = viewModel::onIntervalInputChanged,
                onToggleAutoRefresh = viewModel::toggleAutoRefresh
            )
        },
        bottomBar = {
            BrowserBottomTelemetryBar(
                session = uiState.session,
                permissions = uiState.permissions,
                onBack = viewModel::goBack,
                onForward = viewModel::goForward,
                onReloadOrStop = viewModel::manualReloadOrStop,
                onOpenDiagnostics = { viewModel.setDiagnosticsSheetOpen(true) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SetupReadinessBanner(
                permissions = uiState.permissions,
                isDismissed = uiState.isSetupBannerDismissed,
                onRequestNotifications = requestNotificationsAction,
                onOpenOverlaySettings = openOverlaySettingsAction,
                onOpenBatterySettings = openBatterySettingsAction,
                onDismiss = { viewModel.setSetupBannerDismissed(true) },
                modifier = Modifier.fillMaxWidth()
            )

            WebViewHostContainer(
                hasLoadedPage = uiState.session.hasLoadedPage,
                onSelectQuickTarget = viewModel::loadPresetUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }

    if (uiState.isDiagnosticsSheetOpen) {
        DiagnosticsBottomSheet(
            session = uiState.session,
            permissions = uiState.permissions,
            onDismiss = { viewModel.setDiagnosticsSheetOpen(false) },
            onRequestNotifications = requestNotificationsAction,
            onOpenOverlaySettings = openOverlaySettingsAction,
            onOpenBatterySettings = openBatterySettingsAction,
            onResetCounter = viewModel::resetRefreshCounter
        )
    }
}

private fun openOverlayPermissionSettings(context: Context) {
    val overlayIntent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(overlayIntent) }
        .onFailure { openAppDetailsSettings(context) }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val batteryListIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(batteryListIntent) }
        .onFailure { openAppDetailsSettings(context) }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetailsSettings(context) }
}

private fun openAppDetailsSettings(context: Context) {
    val detailsIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(detailsIntent) }
}
