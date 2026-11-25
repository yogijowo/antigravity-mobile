package com.example.webview

import android.annotation.SuppressLint
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class WebViewUiState(
    val currentUrl: String = WebHostPolicy.HOME_URL,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val hasError: Boolean = false,
    val errorDescription: String? = null,
    val activePopupMessage: Message? = null,
)

class WebViewController {

    private val _uiState = MutableStateFlow(WebViewUiState())
    val uiState: StateFlow<WebViewUiState> = _uiState.asStateFlow()

    @Volatile
    private var mainWebView: WebView? = null

    @Volatile
    private var popupWebView: WebView? = null

    @Volatile
    var currentTrustedUrl: String = WebHostPolicy.HOME_URL
        private set

    fun attachMainWebView(webView: WebView) {
        mainWebView = webView
    }

    fun detachMainWebView() {
        flushCookies()
        mainWebView = null
    }

    fun attachPopupWebView(webView: WebView) {
        popupWebView = webView
    }

    fun detachPopupWebView() {
        popupWebView?.apply {
            stopLoading()
            destroy()
        }
        popupWebView = null
    }

    fun onPageStarted(url: String?) {
        val safeUrl = url ?: currentTrustedUrl
        currentTrustedUrl = safeUrl
        _uiState.update {
            it.copy(
                currentUrl = safeUrl,
                isLoading = true,
                hasError = false,
                errorDescription = null,
                canGoBack = mainWebView?.canGoBack() == true,
            )
        }
    }

    fun onPageFinished(url: String?) {
        val safeUrl = url ?: currentTrustedUrl
        currentTrustedUrl = safeUrl
        flushCookies()
        _uiState.update {
            it.copy(
                currentUrl = safeUrl,
                isLoading = false,
                isRefreshing = false,
                progress = 100,
                canGoBack = mainWebView?.canGoBack() == true,
            )
        }
    }

    fun onProgressChanged(newProgress: Int) {
        _uiState.update {
            it.copy(
                progress = newProgress.coerceIn(0, 100),
                isLoading = newProgress < 100,
                canGoBack = mainWebView?.canGoBack() == true,
            )
        }
    }

    fun onMainFrameError(description: String?) {
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                hasError = true,
                errorDescription = description,
            )
        }
    }

    fun onPopupRequested(resultMsg: Message) {
        _uiState.update { it.copy(activePopupMessage = resultMsg) }
    }

    fun closePopup() {
        detachPopupWebView()
        flushCookies()
        _uiState.update { it.copy(activePopupMessage = null) }
    }

    fun handlePopupBack(): Boolean {
        val popup = popupWebView ?: return false
        return if (popup.canGoBack()) {
            popup.goBack()
            true
        } else {
            closePopup()
            true
        }
    }

    fun goBack(): Boolean {
        val webView = mainWebView ?: return false
        return if (webView.canGoBack()) {
            webView.goBack()
            _uiState.update { it.copy(canGoBack = webView.canGoBack()) }
            true
        } else {
            false
        }
    }

    fun goHome() {
        currentTrustedUrl = WebHostPolicy.HOME_URL
        _uiState.update {
            it.copy(
                currentUrl = WebHostPolicy.HOME_URL,
                hasError = false,
                errorDescription = null,
                isLoading = true,
            )
        }
        mainWebView?.loadUrl(WebHostPolicy.HOME_URL)
    }

    fun reload(fromPullToRefresh: Boolean = false) {
        val webView = mainWebView ?: return
        _uiState.update {
            it.copy(
                hasError = false,
                errorDescription = null,
                isLoading = true,
                isRefreshing = fromPullToRefresh,
            )
        }
        if (webView.url.isNullOrBlank()) {
            webView.loadUrl(WebHostPolicy.HOME_URL)
        } else {
            webView.reload()
        }
    }

    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /**
     * Safely injects recognized voice text into the currently active input/textarea/contenteditable
     * element (including Shadow DOM and modern reactive web frameworks) via evaluateJavascript.
     */
    fun injectVoiceText(transcript: String, onResult: (Boolean) -> Unit) {
        val webView = mainWebView
        if (webView == null || transcript.isBlank()) {
            onResult(false)
            return
        }

        val quotedText = JSONObject.quote(transcript.trim())
        val script = """
            (function(spokenText) {
              function isEditable(el) {
                if (!el) return false;
                var tag = (el.tagName || '').toLowerCase();
                if (tag === 'textarea') return !el.disabled && !el.readOnly;
                if (tag === 'input') {
                  var type = (el.type || 'text').toLowerCase();
                  var allowed = ['text', 'search', 'url', 'email', 'tel', ''];
                  return allowed.indexOf(type) !== -1 && !el.disabled && !el.readOnly;
                }
                if (el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.getAttribute('role') === 'textbox') {
                  return true;
                }
                return false;
              }

              function getDeepActiveElement(root) {
                var active = root.activeElement;
                while (active && active.shadowRoot && active.shadowRoot.activeElement) {
                  active = active.shadowRoot.activeElement;
                }
                return active;
              }

              function findFallbackInput(root) {
                var selectors = 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"], input[type="search"], input:not([type])';
                var candidates = root.querySelectorAll(selectors);
                for (var i = 0; i < candidates.length; i++) {
                  var c = candidates[i];
                  var rect = c.getBoundingClientRect();
                  if (isEditable(c) && rect.width > 24 && rect.height > 16) {
                    return c;
                  }
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                  if (all[j].shadowRoot) {
                    var nested = findFallbackInput(all[j].shadowRoot);
                    if (nested) return nested;
                  }
                }
                return null;
              }

              var target = getDeepActiveElement(document);
              if (!isEditable(target)) {
                target = findFallbackInput(document);
              }
              if (!target) return false;

              target.focus();
              var tag = (target.tagName || '').toLowerCase();
              if (tag === 'input' || tag === 'textarea') {
                var proto = tag === 'textarea' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
                var existing = target.value || '';
                var nextValue = existing.length > 0 && !/\s$/.test(existing) ? (existing + ' ' + spokenText) : (existing + spokenText);
                if (descriptor && descriptor.set) {
                  descriptor.set.call(target, nextValue);
                } else {
                  target.value = nextValue;
                }
                target.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                target.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                return true;
              }

              if (target.isContentEditable || target.getAttribute('contenteditable') === 'true' || target.getAttribute('role') === 'textbox') {
                var inserted = false;
                try {
                  inserted = document.execCommand('insertText', false, spokenText);
                } catch (e) {
                  inserted = false;
                }
                if (!inserted) {
                  var current = target.textContent || '';
                  target.textContent = current.length > 0 && !/\s$/.test(current) ? (current + ' ' + spokenText) : (current + spokenText);
                }
                target.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, data: spokenText, inputType: 'insertText' }));
                return true;
              }
              return false;
            })($quotedText);
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { result ->
                onResult(result == "true")
            }
        }
    }

    companion object {
        @SuppressLint("SetJavaScriptEnabled")
        fun configureCommonWebView(
            webView: WebView,
            isDarkTheme: Boolean,
        ) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            webView.isVerticalScrollBarEnabled = true
            webView.isHorizontalScrollBarEnabled = false

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false

                // Security hardening
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }

                // Clean User-Agent so Google OAuth recognizes the Chromium WebView cleanly
                val defaultUa = userAgentString ?: ""
                userAgentString = defaultUa
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
            }

            applyThemeSync(webView, isDarkTheme)
        }

        fun applyThemeSync(webView: WebView, isDarkTheme: Boolean) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDarkTheme)
            }
        }
    }
}
