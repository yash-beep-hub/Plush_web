package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide singleton that owns the single WebView instance and coordinates smooth
 * host transitions between the Compose UI container and an invisible 0x0 WindowManager
 * TYPE_APPLICATION_OVERLAY view so logged-in sessions and JS timers stay active in background.
 */
object SharedWebViewManager {

    const val MOBILE_CHROME_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"

    private const val MAX_LOG_ENTRIES = 24

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logIdGenerator = AtomicLong(1L)

    private var appContext: Context? = null
    private var contextWrapper: MutableContextWrapper? = null
    private var webView: WebView? = null

    private var overlayWindowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null

    private val _sessionState = MutableStateFlow(BrowserSessionState())
    val sessionState: StateFlow<BrowserSessionState> = _sessionState.asStateFlow()

    private fun runOnMainSyncOrPost(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(context: Context): WebView {
        val existing = webView
        if (existing != null) return existing

        val application = context.applicationContext
        appContext = application
        val wrapper = MutableContextWrapper(application)
        contextWrapper = wrapper

        val created = WebView(wrapper).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportZoom(true)
                userAgentString = MOBILE_CHROME_USER_AGENT
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val scheme = request?.url?.scheme?.lowercase()
                    return scheme != "http" && scheme != "https" && scheme != "about"
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    _sessionState.update { state ->
                        state.copy(
                            currentUrl = url ?: state.currentUrl,
                            isLoading = true,
                            hasLoadedPage = true,
                            canGoBack = view?.canGoBack() == true,
                            canGoForward = view?.canGoForward() == true,
                            lastErrorMessage = null
                        )
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    runCatching { CookieManager.getInstance().flush() }
                    _sessionState.update { state ->
                        state.copy(
                            currentUrl = url ?: state.currentUrl,
                            pageTitle = view?.title.orEmpty(),
                            isLoading = false,
                            loadProgress = 100,
                            hasLoadedPage = !url.isNullOrBlank() && url != "about:blank",
                            canGoBack = view?.canGoBack() == true,
                            canGoForward = view?.canGoForward() == true
                        )
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val description = error?.description?.toString().orEmpty()
                        _sessionState.update { state ->
                            state.copy(
                                isLoading = false,
                                lastErrorMessage = description.ifBlank { "Failed to load webpage" }
                            )
                        }
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    _sessionState.update { state ->
                        state.copy(
                            loadProgress = newProgress,
                            isLoading = newProgress in 1..99
                        )
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrBlank()) {
                        _sessionState.update { it.copy(pageTitle = title) }
                    }
                }
            }
        }

        webView = created
        return created
    }

    /**
     * Smoothly detaches the shared WebView from any background 0x0 overlay or old container
     * and attaches it into the Activity's Compose FrameLayout without reloading or losing state.
     */
    fun attachToForegroundContainer(container: FrameLayout, activityContext: Context) {
        runOnMainSyncOrPost {
            val wv = ensureWebView(activityContext)

            // Detach cleanly from the 0x0 invisible overlay host first if active.
            detachFromBackgroundOverlayInternal()

            if (wv.parent !== container) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                contextWrapper?.baseContext = activityContext
                container.removeAllViews()
                container.addView(
                    wv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                contextWrapper?.baseContext = activityContext
            }

            wv.onResume()
            wv.resumeTimers()
            wv.requestLayout()
            wv.invalidate()

            val wasBackground = _sessionState.value.hostMode == WebViewHostMode.BACKGROUND_OVERLAY
            _sessionState.update { state ->
                state.copy(
                    hostMode = WebViewHostMode.FOREGROUND_UI,
                    canGoBack = wv.canGoBack(),
                    canGoForward = wv.canGoForward()
                )
            }
            if (wasBackground) {
                appendLog("Reattached live session to foreground UI (no reload)", isBackground = false)
            }
        }
    }

    /**
     * Detaches the WebView from a disposed Compose FrameLayout container safely
     * without leaking the Activity context.
     */
    fun detachFromForegroundContainer(container: FrameLayout) {
        runOnMainSyncOrPost {
            val wv = webView ?: return@runOnMainSyncOrPost
            if (wv.parent === container) {
                container.removeView(wv)
                appContext?.let { contextWrapper?.baseContext = it }
                if (_sessionState.value.hostMode == WebViewHostMode.FOREGROUND_UI) {
                    _sessionState.update { it.copy(hostMode = WebViewHostMode.DETACHED) }
                }
            }
        }
    }

    /**
     * Detaches the shared WebView from the Activity UI and attaches it to a 0px x 0px invisible,
     * non-focusable, non-touchable WindowManager TYPE_APPLICATION_OVERLAY view so Chromium's
     * rendering engine and JavaScript timers stay fully active in the background.
     */
    fun attachToBackgroundOverlay(serviceContext: Context): Boolean {
        val wv = ensureWebView(serviceContext)
        runCatching { CookieManager.getInstance().flush() }

        if (!Settings.canDrawOverlays(serviceContext)) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            appContext?.let { contextWrapper?.baseContext = it }
            _sessionState.update { it.copy(hostMode = WebViewHostMode.DETACHED) }
            appendLog(
                "Overlay permission missing; running background reload without 0x0 host",
                isBackground = true
            )
            return false
        }

        if (overlayContainer != null && wv.parent === overlayContainer) {
            wv.onResume()
            wv.resumeTimers()
            _sessionState.update { it.copy(hostMode = WebViewHostMode.BACKGROUND_OVERLAY) }
            return true
        }

        return try {
            detachFromBackgroundOverlayInternal()
            (wv.parent as? ViewGroup)?.removeView(wv)
            contextWrapper?.baseContext = serviceContext

            val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val hostFrame = FrameLayout(serviceContext).apply {
                alpha = 0f
                isClickable = false
                isFocusable = false
                addView(
                    wv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }

            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                0,
                0,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                alpha = 0f
            }

            wm.addView(hostFrame, params)
            overlayWindowManager = wm
            overlayContainer = hostFrame

            wv.onResume()
            wv.resumeTimers()

            _sessionState.update { it.copy(hostMode = WebViewHostMode.BACKGROUND_OVERLAY) }
            appendLog("Attached WebView to 0x0 invisible overlay host", isBackground = true)
            true
        } catch (e: Exception) {
            appContext?.let { contextWrapper?.baseContext = it }
            _sessionState.update {
                it.copy(
                    hostMode = WebViewHostMode.DETACHED,
                    lastErrorMessage = "Overlay attach error: ${e.localizedMessage ?: e.javaClass.simpleName}"
                )
            }
            appendLog("Overlay attach failed: ${e.localizedMessage}", isBackground = true)
            false
        }
    }

    fun detachFromBackgroundOverlay() {
        runOnMainSyncOrPost {
            detachFromBackgroundOverlayInternal()
        }
    }

    private fun detachFromBackgroundOverlayInternal() {
        val container = overlayContainer
        val wm = overlayWindowManager
        val wv = webView

        if (container != null) {
            if (wv != null && wv.parent === container) {
                container.removeView(wv)
            }
            if (wm != null) {
                runCatching { wm.removeViewImmediate(container) }
                    .onFailure { runCatching { wm.removeView(container) } }
            }
            overlayContainer = null
            overlayWindowManager = null
            appContext?.let { contextWrapper?.baseContext = it }

            if (_sessionState.value.hostMode == WebViewHostMode.BACKGROUND_OVERLAY) {
                _sessionState.update { it.copy(hostMode = WebViewHostMode.DETACHED) }
            }
        }
    }

    fun loadUrl(context: Context, rawUrl: String): String? {
        val normalized = UrlUtils.normalizeUrl(rawUrl) ?: return null
        runOnMainSyncOrPost {
            val wv = ensureWebView(context)
            _sessionState.update {
                it.copy(
                    currentUrl = normalized,
                    hasLoadedPage = true,
                    isLoading = true,
                    loadProgress = 10,
                    lastErrorMessage = null
                )
            }
            wv.loadUrl(normalized)
            appendLog("Loaded ${UrlUtils.compactDisplayHost(normalized)}", isBackground = false)
        }
        return normalized
    }

    fun reloadNow(context: Context, isAutoRefreshTick: Boolean, isBackground: Boolean) {
        runOnMainSyncOrPost {
            val wv = ensureWebView(context)
            wv.onResume()
            wv.resumeTimers()

            val activeUrl = wv.url?.takeIf { it.isNotBlank() && it != "about:blank" }
                ?: _sessionState.value.currentUrl.takeIf { it.isNotBlank() }

            if (activeUrl == null) return@runOnMainSyncOrPost

            if (wv.url.isNullOrBlank() || wv.url == "about:blank") {
                wv.loadUrl(activeUrl)
            } else {
                wv.reload()
            }

            if (isAutoRefreshTick) {
                val now = System.currentTimeMillis()
                val newCount = _sessionState.value.refreshCount + 1
                _sessionState.update { state ->
                    state.copy(
                        refreshCount = newCount,
                        lastRefreshedAtMillis = now,
                        isLoading = true
                    )
                }
                val modeLabel = if (isBackground) "BG 0x0" else "FG"
                appendLog(
                    "#$newCount auto-reload ($modeLabel) • ${UrlUtils.compactDisplayHost(activeUrl)}",
                    isBackground = isBackground
                )
            } else {
                appendLog(
                    "Manual reload • ${UrlUtils.compactDisplayHost(activeUrl)}",
                    isBackground = isBackground
                )
            }
        }
    }

    fun stopLoading() {
        runOnMainSyncOrPost {
            webView?.stopLoading()
            _sessionState.update { it.copy(isLoading = false) }
        }
    }

    fun goBack() {
        runOnMainSyncOrPost {
            val wv = webView ?: return@runOnMainSyncOrPost
            if (wv.canGoBack()) {
                wv.goBack()
                _sessionState.update {
                    it.copy(
                        canGoBack = wv.canGoBack(),
                        canGoForward = wv.canGoForward()
                    )
                }
            }
        }
    }

    fun goForward() {
        runOnMainSyncOrPost {
            val wv = webView ?: return@runOnMainSyncOrPost
            if (wv.canGoForward()) {
                wv.goForward()
                _sessionState.update {
                    it.copy(
                        canGoBack = wv.canGoBack(),
                        canGoForward = wv.canGoForward()
                    )
                }
            }
        }
    }

    fun updateAutoRefreshState(
        isActive: Boolean,
        intervalSeconds: Int = _sessionState.value.intervalSeconds,
        secondsRemaining: Int = intervalSeconds,
        isServiceRunning: Boolean = _sessionState.value.isServiceRunning,
        isWakeLockHeld: Boolean = _sessionState.value.isWakeLockHeld
    ) {
        _sessionState.update { state ->
            state.copy(
                isAutoRefreshActive = isActive,
                intervalSeconds = intervalSeconds,
                secondsUntilNextRefresh = secondsRemaining,
                isServiceRunning = isServiceRunning,
                isWakeLockHeld = isWakeLockHeld
            )
        }
    }

    fun updateCountdown(secondsRemaining: Int) {
        _sessionState.update { it.copy(secondsUntilNextRefresh = secondsRemaining.coerceAtLeast(0)) }
    }

    fun resetRefreshCounter() {
        _sessionState.update { it.copy(refreshCount = 0) }
        appendLog("Refresh counter reset to 0", isBackground = false)
    }

    fun clearError() {
        _sessionState.update { it.copy(lastErrorMessage = null) }
    }

    fun appendLog(message: String, isBackground: Boolean) {
        val entry = RefreshLogEntry(
            id = logIdGenerator.getAndIncrement(),
            timestampMillis = System.currentTimeMillis(),
            message = message,
            isBackground = isBackground
        )
        _sessionState.update { state ->
            state.copy(activityLog = (listOf(entry) + state.activityLog).take(MAX_LOG_ENTRIES))
        }
    }
}
