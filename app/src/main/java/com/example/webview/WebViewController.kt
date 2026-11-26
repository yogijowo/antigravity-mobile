package com.example.webview

import android.annotation.SuppressLint
import android.os.Build
import android.os.Message
import android.util.Base64
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

    @Volatile
    var isWebAtTop: Boolean = true
        private set

    fun updateWebAtTop(atTop: Boolean) {
        isWebAtTop = atTop
    }

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
        isWebAtTop = true
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
        mainWebView?.let { injectScrollGuard(it) }
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

    private fun injectScrollGuard(webView: WebView) {
        val script = """
            (function() {
              if (window.__antigravityScrollGuardInstalled) return;
              window.__antigravityScrollGuardInstalled = true;
              var lastAllowed = null;

              function notify(allowed) {
                if (lastAllowed === allowed) return;
                lastAllowed = allowed;
                try {
                  if (window.AndroidBridge && typeof window.AndroidBridge.setPullToRefreshAllowed === 'function') {
                    window.AndroidBridge.setPullToRefreshAllowed(!!allowed);
                  }
                } catch (e) {}
              }

              function isPageAtTop(target) {
                if ((window.scrollY || 0) > 1) return false;
                if (document.documentElement && document.documentElement.scrollTop > 1) return false;
                if (document.body && document.body.scrollTop > 1) return false;
                var node = target;
                while (node) {
                  if (node.nodeType === 1 && node.scrollTop > 1) {
                    return false;
                  }
                  if (node.parentNode) {
                    node = node.parentNode;
                  } else if (node.host) {
                    node = node.host;
                  } else {
                    break;
                  }
                }
                return true;
              }

              window.addEventListener('touchstart', function(e) {
                notify(isPageAtTop(e.target));
              }, { capture: true, passive: true });

              window.addEventListener('scroll', function(e) {
                notify(isPageAtTop(e.target));
              }, { capture: true, passive: true });
            })();
        """.trimIndent()
        webView.post {
            webView.evaluateJavascript(script, null)
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

    /**
     * Fetches a `blob:` URL inside WebView memory and passes its base64 data URL to
     * `window.AndroidBridge.onBlobFileReady(...)` so PDFs/documents generated as blobs can be previewed natively.
     */
    fun fetchBlobUrl(
        blobUrl: String,
        suggestedMimeType: String? = null,
        suggestedFileName: String? = null,
    ) {
        val webView = mainWebView ?: return
        val quotedUrl = JSONObject.quote(blobUrl)
        val quotedMime = JSONObject.quote(suggestedMimeType.orEmpty())
        val quotedName = JSONObject.quote(suggestedFileName.orEmpty())

        val script = """
            (function(url, fallbackMime, fallbackName) {
              fetch(url)
                .then(function(res) { return res.blob(); })
                .then(function(blob) {
                  var reader = new FileReader();
                  reader.onloadend = function() {
                    if (typeof reader.result === 'string' && window.AndroidBridge && window.AndroidBridge.onBlobFileReady) {
                      window.AndroidBridge.onBlobFileReady(
                        reader.result,
                        blob.type || fallbackMime || 'application/octet-stream',
                        fallbackName || 'document'
                      );
                    }
                  };
                  reader.readAsDataURL(blob);
                })
                .catch(function() {});
            })($quotedUrl, $quotedMime, $quotedName);
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Pastes an image or binary file from Android clipboard / rich keyboard content directly into
     * the web page by synthesizing a real ClipboardEvent('paste') with a DataTransfer File, with
     * fallbacks for drop zones and file inputs.
     */
    fun pasteBinaryIntoWeb(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        onResult: (Boolean) -> Unit,
    ) {
        val webView = mainWebView
        if (webView == null || bytes.isEmpty()) {
            onResult(false)
            return
        }

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val quotedBase64 = JSONObject.quote(base64)
        val quotedMime = JSONObject.quote(mimeType.ifBlank { "image/png" })
        val quotedName = JSONObject.quote(fileName.ifBlank { "pasted_image.png" })

        val script = """
            (function(b64, mime, name) {
              try {
                var binStr = atob(b64);
                var len = binStr.length;
                var arr = new Uint8Array(len);
                for (var i = 0; i < len; i++) {
                  arr[i] = binStr.charCodeAt(i);
                }
                var file = new File([arr], name, { type: mime, lastModified: Date.now() });

                function getDeepActive(root) {
                  var active = root.activeElement;
                  while (active && active.shadowRoot && active.shadowRoot.activeElement) {
                    active = active.shadowRoot.activeElement;
                  }
                  return active;
                }

                function findEditable(root) {
                  var sel = 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"], input[type="search"]';
                  var list = root.querySelectorAll(sel);
                  for (var i = 0; i < list.length; i++) {
                    var r = list[i].getBoundingClientRect();
                    if (r.width > 24 && r.height > 16) return list[i];
                  }
                  var all = root.querySelectorAll('*');
                  for (var j = 0; j < all.length; j++) {
                    if (all[j].shadowRoot) {
                      var nested = findEditable(all[j].shadowRoot);
                      if (nested) return nested;
                    }
                  }
                  return null;
                }

                function findFileInput(root) {
                  var inputs = root.querySelectorAll('input[type="file"]');
                  if (inputs.length > 0) return inputs[0];
                  var all = root.querySelectorAll('*');
                  for (var j = 0; j < all.length; j++) {
                    if (all[j].shadowRoot) {
                      var nested = findFileInput(all[j].shadowRoot);
                      if (nested) return nested;
                    }
                  }
                  return null;
                }

                var target = getDeepActive(document);
                if (!target || target === document.body || target === document.documentElement) {
                  target = findEditable(document) || document.body;
                }
                if (target && typeof target.focus === 'function') {
                  target.focus();
                }

                var dt = new DataTransfer();
                dt.items.add(file);

                var pasteEvent = new ClipboardEvent('paste', {
                  clipboardData: dt,
                  bubbles: true,
                  cancelable: true,
                  composed: true
                });
                var notCancelled = target.dispatchEvent(pasteEvent);

                // If the web app handled paste (called preventDefault), we are done!
                if (!notCancelled) {
                  return true;
                }

                // Fallback 1: attach directly to an available <input type="file">
                var fileInput = findFileInput(document);
                if (fileInput) {
                  var inputDt = new DataTransfer();
                  inputDt.items.add(file);
                  fileInput.files = inputDt.files;
                  fileInput.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                  fileInput.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                  return true;
                }

                // Fallback 2: dispatch a drop event on the active prompt container
                var dropDt = new DataTransfer();
                dropDt.items.add(file);
                var dropEvent = new DragEvent('drop', {
                  dataTransfer: dropDt,
                  bubbles: true,
                  cancelable: true,
                  composed: true
                });
                target.dispatchEvent(dropEvent);
                return true;
              } catch (e) {
                return false;
              }
            })($quotedBase64, $quotedMime, $quotedName);
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { result ->
                onResult(result == "true")
            }
        }
    }

    /**
     * Inserts the user's spoken Indonesian prompt into Antigravity's active input and automatically
     * submits/sends the chat message (clicking the Send button or dispatching Enter).
     */
    fun sendVoiceChatMessage(
        transcript: String,
        onResult: (Boolean) -> Unit,
    ) {
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
                  return (type === 'text' || type === 'search' || type === '') && !el.disabled && !el.readOnly;
                }
                return el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.getAttribute('role') === 'textbox';
              }

              function findEditable(root) {
                var active = root.activeElement;
                while (active && active.shadowRoot && active.shadowRoot.activeElement) {
                  active = active.shadowRoot.activeElement;
                }
                if (isEditable(active)) return active;
                var sel = 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"], input[type="search"]';
                var list = root.querySelectorAll(sel);
                for (var i = 0; i < list.length; i++) {
                  var r = list[i].getBoundingClientRect();
                  if (isEditable(list[i]) && r.width > 24 && r.height > 16) return list[i];
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                  if (all[j].shadowRoot) {
                    var nested = findEditable(all[j].shadowRoot);
                    if (nested) return nested;
                  }
                }
                return null;
              }

              function findSendButton(root) {
                var selectors = 'button[type="submit"], button[aria-label*="send" i], button[aria-label*="kirim" i], button[aria-label*="submit" i], button[aria-label*="run" i], [data-testid*="send" i]';
                var btns = root.querySelectorAll(selectors);
                for (var i = 0; i < btns.length; i++) {
                  var b = btns[i];
                  var r = b.getBoundingClientRect();
                  if (!b.disabled && r.width > 0 && r.height > 0) return b;
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                  if (all[j].shadowRoot) {
                    var nested = findSendButton(all[j].shadowRoot);
                    if (nested) return nested;
                  }
                }
                return null;
              }

              var target = findEditable(document);
              if (!target) return false;

              target.focus();
              var tag = (target.tagName || '').toLowerCase();
              if (tag === 'input' || tag === 'textarea') {
                var proto = tag === 'textarea' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) {
                  desc.set.call(target, spokenText);
                } else {
                  target.value = spokenText;
                }
                target.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                target.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
              } else {
                target.textContent = spokenText;
                target.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, data: spokenText, inputType: 'insertText' }));
              }

              setTimeout(function() {
                var btn = findSendButton(document);
                if (btn) {
                  btn.click();
                } else {
                  target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true, composed: true }));
                  target.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, composed: true }));
                }
              }, 120);

              return true;
            })($quotedText);
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { result ->
                onResult(result == "true")
            }
        }
    }

    /**
     * Starts or stops a lightweight parent-process-only DOM observer inside Antigravity.
     * Ignores nested sub-steps/code blocks and reports only top-level parent process headlines
     * and completion status back to AndroidBridge.onAgentProcessUpdate(...).
     */
    fun setAgentProcessObserverEnabled(enabled: Boolean) {
        val webView = mainWebView ?: return
        val script = if (!enabled) {
            """
            (function() {
              if (window.__antigravityAgentTimer) {
                clearInterval(window.__antigravityAgentTimer);
                window.__antigravityAgentTimer = null;
              }
              if (window.__antigravityAgentObserver) {
                window.__antigravityAgentObserver.disconnect();
                window.__antigravityAgentObserver = null;
              }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
              if (window.__antigravityAgentTimer) {
                clearInterval(window.__antigravityAgentTimer);
              }
              if (window.__antigravityAgentObserver) {
                window.__antigravityAgentObserver.disconnect();
              }

              var lastMutationAt = Date.now();
              var wasRunning = false;
              var lastReportedStep = '';
              var lastReportedState = null;

              function isNestedSubItem(el) {
                if (!el) return true;
                if (el.closest('pre, code, nav, header, footer, script, style, ul ul, ol ol')) return true;
                var ariaLevel = el.getAttribute('aria-level');
                if (ariaLevel && parseInt(ariaLevel, 10) > 1) return true;
                // Skip children inside expanded details (keep only parent <summary>)
                var detailsParent = el.closest('details');
                if (detailsParent && !el.closest('summary')) return true;
                return false;
              }

              function collectBusySignal(root) {
                var stopSel = 'button[aria-label*="stop" i], button[aria-label*="cancel" i], button[aria-label*="hentikan" i], [role="progressbar"], [aria-busy="true"]';
                var busyNodes = root.querySelectorAll(stopSel);
                for (var i = 0; i < busyNodes.length; i++) {
                  var r = busyNodes[i].getBoundingClientRect();
                  if (r.width > 0 && r.height > 0 && !busyNodes[i].disabled) return true;
                }
                var all = root.querySelectorAll('*');
                for (var j = 0; j < all.length; j++) {
                  if (all[j].shadowRoot && collectBusySignal(all[j].shadowRoot)) return true;
                }
                return false;
              }

              function extractLatestParentStep(root) {
                var candidates = [];
                function scan(scope) {
                  var sel = 'details > summary, [role="status"], [aria-live="polite"], [aria-live="assertive"], h2, h3, h4, [data-step-title], [data-testid*="step" i], [data-testid*="status" i]';
                  var list = scope.querySelectorAll(sel);
                  for (var i = 0; i < list.length; i++) {
                    var el = list[i];
                    if (isNestedSubItem(el)) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) continue;
                    var txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (txt.length >= 4 && txt.length <= 160) {
                      candidates.push(txt);
                    }
                  }
                  var all = scope.querySelectorAll('*');
                  for (var j = 0; j < all.length; j++) {
                    if (all[j].shadowRoot) scan(all[j].shadowRoot);
                  }
                }
                scan(root);
                return candidates.length > 0 ? candidates[candidates.length - 1] : '';
              }

              function extractLatestSummary(root) {
                var blocks = [];
                function scan(scope) {
                  var sel = 'article p, [role="log"] p, main p, [data-message-author="model"] p, [data-message-author="assistant"] p';
                  var list = scope.querySelectorAll(sel);
                  for (var i = 0; i < list.length; i++) {
                    var el = list[i];
                    if (isNestedSubItem(el)) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) continue;
                    var txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (txt.length >= 8) blocks.push(txt);
                  }
                  var all = scope.querySelectorAll('*');
                  for (var j = 0; j < all.length; j++) {
                    if (all[j].shadowRoot) scan(all[j].shadowRoot);
                  }
                }
                scan(root);
                if (blocks.length === 0) return '';
                var latest = blocks[blocks.length - 1];
                var firstSentence = latest.split(/(?<=[.!?])\s+/)[0] || latest;
                return firstSentence.slice(0, 220);
              }

              window.__antigravityAgentObserver = new MutationObserver(function(mutations) {
                for (var i = 0; i < mutations.length; i++) {
                  if (mutations[i].type === 'childList' || mutations[i].type === 'characterData') {
                    lastMutationAt = Date.now();
                    break;
                  }
                }
              });

              if (document.body) {
                window.__antigravityAgentObserver.observe(document.body, {
                  childList: true,
                  subtree: true,
                  characterData: true
                });
              }

              window.__antigravityAgentTimer = setInterval(function() {
                var busyWidget = collectBusySignal(document);
                var recentlyMutated = (Date.now() - lastMutationAt) < 2800;
                var isRunning = busyWidget || (wasRunning && recentlyMutated);
                var parentStep = extractLatestParentStep(document);
                var summary = isRunning ? '' : extractLatestSummary(document);

                if (isRunning) {
                  wasRunning = true;
                }

                if (lastReportedState !== isRunning || (isRunning && parentStep && parentStep !== lastReportedStep)) {
                  lastReportedState = isRunning;
                  if (parentStep) lastReportedStep = parentStep;
                  if (!isRunning) wasRunning = false;
                  try {
                    if (window.AndroidBridge && window.AndroidBridge.onAgentProcessUpdate) {
                      window.AndroidBridge.onAgentProcessUpdate(!!isRunning, parentStep || '', summary || '');
                    }
                  } catch (e) {}
                }
              }, 650);
            })();
            """.trimIndent()
        }

        webView.post {
            webView.evaluateJavascript(script, null)
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
