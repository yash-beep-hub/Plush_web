package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.browser.SharedWebViewManager
import com.example.service.BackgroundRefreshService
import com.example.ui.BrowserScreen
import com.example.ui.theme.PulseWebTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SharedWebViewManager.ensureWebView(this)
        setContent {
            PulseWebTheme {
                BrowserScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Transition the running service back to foreground UI mode and detach any 0x0 overlay
        // so WebViewHostContainer smoothly reattaches the exact same WebView without reloading.
        BackgroundRefreshService.enterForegroundUi(this)
    }

    override fun onStop() {
        super.onStop()
        // When Auto-Refresh is active and the user leaves/minimizes the app (ignoring config changes),
        // hand the live WebView session to the Foreground Service's 0x0 invisible overlay host.
        if (!isChangingConfigurations && SharedWebViewManager.sessionState.value.isAutoRefreshActive) {
            BackgroundRefreshService.enterBackgroundOverlay(this)
        }
    }
}
