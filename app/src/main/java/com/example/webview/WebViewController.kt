package com.example.webview

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WebViewUiState(
    val currentUrl: String = WebHostPolicy.HOME_URL,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val hasError: Boolean = false,
    val errorDescription: String? = null,
    val activePopupMessage: Message? = null,
    val isOnGoogleSignInPage: Boolean = false,
    val isPopupOnGoogleSignInPage: Boolean = false,
)

class WebViewController {

    private val _uiState = MutableStateFlow(WebViewUiState())
    val uiState: StateFlow<WebViewUiState> = _uiState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingVoiceSendCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()

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
        pendingVoiceSendCallbacks.clear()
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
        _uiState.update { it.copy(isPopupOnGoogleSignInPage = false) }
    }

    fun onPageStarted(url: String?) {
        val safeUrl = url ?: currentTrustedUrl
        currentTrustedUrl = safeUrl
        isWebAtTop = true
        val onGoogleSignIn = WebHostPolicy.isGoogleSignInUrl(safeUrl)
        _uiState.update {
            it.copy(
                currentUrl = safeUrl,
                isLoading = true,
                hasError = false,
                errorDescription = null,
                canGoBack = mainWebView?.canGoBack() == true,
                isOnGoogleSignInPage = onGoogleSignIn,
            )
        }
    }

    fun onPageFinished(url: String?) {
        val safeUrl = url ?: currentTrustedUrl
        currentTrustedUrl = safeUrl
        flushCookies()
        mainWebView?.let { injectScrollGuard(it) }
        val onGoogleSignIn = WebHostPolicy.isGoogleSignInUrl(safeUrl)
        _uiState.update {
            it.copy(
                currentUrl = safeUrl,
                isLoading = false,
                isRefreshing = false,
                progress = 100,
                canGoBack = mainWebView?.canGoBack() == true,
                isOnGoogleSignInPage = onGoogleSignIn,
            )
        }
    }

    fun onPopupUrlChanged(url: String?) {
        val onGoogleSignIn = WebHostPolicy.isGoogleSignInUrl(url)
        _uiState.update { it.copy(isPopupOnGoogleSignInPage = onGoogleSignIn) }
    }

    /**
     * Applies a Google account chosen from Android's native device account picker directly onto
     * Google's sign-in page (either AccountChooser tile click or email identifier prefill + Next).
     * If not currently on accounts.google.com, navigates smoothly into Google's sign-in flow with
     * `Email` pre-selected and `continue=https://antigravity.google.com`.
     */
    fun applyChosenGoogleAccount(
        email: String,
        onApplied: (Boolean) -> Unit = {},
    ) {
        val cleanEmail = email.trim()
        if (cleanEmail.isEmpty()) {
            onApplied(false)
            return
        }

        val targetWebView = when {
            popupWebView != null && WebHostPolicy.isGoogleSignInUrl(popupWebView?.url) -> popupWebView
            mainWebView != null && WebHostPolicy.isGoogleSignInUrl(mainWebView?.url) -> mainWebView
            popupWebView != null -> popupWebView
            else -> mainWebView
        }

        if (targetWebView == null) {
            onApplied(false)
            return
        }

        val currentUrl = targetWebView.url.orEmpty()
        if (!WebHostPolicy.isGoogleSignInUrl(currentUrl)) {
            val encodedEmail = runCatching { URLEncoder.encode(cleanEmail, "UTF-8") }.getOrDefault(cleanEmail)
            val encodedContinue = runCatching { URLEncoder.encode(WebHostPolicy.HOME_URL, "UTF-8") }
                .getOrDefault(WebHostPolicy.HOME_URL)
            val loginUrl = "https://accounts.google.com/ServiceLogin?Email=$encodedEmail&continue=$encodedContinue"
            targetWebView.post {
                targetWebView.loadUrl(loginUrl)
                onApplied(true)
            }
            return
        }

        val quotedEmail = JSONObject.quote(cleanEmail)
        val script = """
            (function(chosenEmail) {
              function trySelectOrFill() {
                try {
                  var lower = chosenEmail.toLowerCase();
                  // 1. If Google's AccountChooser already shows this account, click it directly
                  var tiles = document.querySelectorAll('[data-identifier], [data-email]');
                  for (var i = 0; i < tiles.length; i++) {
                    var idVal = (tiles[i].getAttribute('data-identifier') || tiles[i].getAttribute('data-email') || '').trim().toLowerCase();
                    if (idVal === lower) {
                      tiles[i].click();
                      return 'CLICKED_TILE';
                    }
                  }

                  // 2. Otherwise fill the email identifier input and advance
                  var emailInput = document.querySelector('#identifierId, input[type="email"], input[name="identifier"], input[name="Email"]');
                  if (emailInput && !emailInput.disabled) {
                    var rect = emailInput.getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0) {
                      emailInput.focus();
                      var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                      if (desc && desc.set) {
                        desc.set.call(emailInput, chosenEmail);
                      } else {
                        emailInput.value = chosenEmail;
                      }
                      emailInput.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                      emailInput.dispatchEvent(new Event('change', { bubbles: true, composed: true }));

                      setTimeout(function() {
                        var nextBtn = document.querySelector('#identifierNext button, #identifierNext, button[jsname="LgbsSe"], #next, button[type="submit"]');
                        if (nextBtn) {
                          nextBtn.click();
                        } else {
                          emailInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
                        }
                      }, 180);
                      return 'FILLED_EMAIL';
                    }
                  }

                  // 3. If on AccountChooser showing "Use another account", click it then fill on next screen
                  var useAnother = document.querySelector('[data-identifier=""], [jsname="rwl3qc"]');
                  if (useAnother) {
                    useAnother.click();
                    setTimeout(trySelectOrFill, 450);
                    return 'SWITCHING';
                  }
                } catch (e) {}
                return 'NOT_READY';
              }

              var first = trySelectOrFill();
              if (first === 'NOT_READY') {
                setTimeout(trySelectOrFill, 450);
              }
              return first;
            })($quotedEmail);
        """.trimIndent()

        targetWebView.post {
            targetWebView.evaluateJavascript(script) { result ->
                val ok = result != null && !result.contains("NOT_READY")
                onApplied(ok)
            }
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
        _uiState.update {
            it.copy(
                activePopupMessage = null,
                isPopupOnGoogleSignInPage = false,
            )
        }
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
                isOnGoogleSignInPage = false,
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

    fun onVoiceChatSendResultFromBridge(requestId: String, sent: Boolean) {
        val callback = pendingVoiceSendCallbacks.remove(requestId) ?: return
        mainHandler.post { callback(sent) }
    }

    /**
     * Safely injects recognized voice text into the currently active chat/prompt input
     * across document, nested Shadow DOMs, same-origin iframes, contenteditable="plaintext-only",
     * Lexical/ProseMirror/CodeMirror/Angular/React editors.
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
              try {
                $SHARED_DOM_COMPOSER_HELPERS

                var target = findBestEditable();
                if (!target) return false;
                return insertTextIntoElement(target, spokenText, true);
              } catch (e) {
                return false;
              }
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
                $SHARED_DOM_COMPOSER_HELPERS

                var binStr = atob(b64);
                var len = binStr.length;
                var arr = new Uint8Array(len);
                for (var i = 0; i < len; i++) {
                  arr[i] = binStr.charCodeAt(i);
                }
                var file = new File([arr], name, { type: mime, lastModified: Date.now() });

                function findFileInput() {
                  var roots = collectAllRoots(document);
                  for (var r = 0; r < roots.length; r++) {
                    var inputs = roots[r].querySelectorAll('input[type="file"]');
                    if (inputs.length > 0) return inputs[0];
                  }
                  return null;
                }

                var target = findBestEditable() || document.body;
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

                if (!notCancelled) {
                  return true;
                }

                var fileInput = findFileInput();
                if (fileInput) {
                  var inputDt = new DataTransfer();
                  inputDt.items.add(file);
                  fileInput.files = inputDt.files;
                  fileInput.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                  fileInput.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                  return true;
                }

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
     * Inserts the user's spoken Indonesian prompt into Antigravity's chat composer (searching main DOM,
     * Shadow DOMs, same-origin iframes, contenteditable="plaintext-only", Lexical/ProseMirror, and
     * even auto-clicking collapsed chat placeholders if needed) and automatically sends the message.
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

        val requestId = UUID.randomUUID().toString()
        pendingVoiceSendCallbacks[requestId] = onResult

        // Safety fallback so callback always settles even if the page redirects mid-retry
        mainHandler.postDelayed({
            val timedOut = pendingVoiceSendCallbacks.remove(requestId)
            timedOut?.invoke(false)
        }, 1_600L)

        val quotedText = JSONObject.quote(transcript.trim())
        val quotedReqId = JSONObject.quote(requestId)

        val script = """
            (function(spokenText, reqId) {
              function reportResult(ok) {
                try {
                  if (window.AndroidBridge && typeof window.AndroidBridge.onVoiceChatSendResult === 'function') {
                    window.AndroidBridge.onVoiceChatSendResult(reqId, !!ok);
                  }
                } catch (e) {}
              }

              try {
                $SHARED_DOM_COMPOSER_HELPERS

                function findSendButtonNear(targetEl) {
                  var sendSel = 'button[type="submit"], button[aria-label*="send" i], button[aria-label*="kirim" i], button[aria-label*="submit" i], button[aria-label*="run" i], button[aria-label*="ask" i], button[title*="send" i], button[title*="kirim" i], [data-testid*="send" i], [data-testid*="submit" i], [role="button"][aria-label*="send" i], [role="button"][aria-label*="kirim" i]';

                  function pickEnabled(root) {
                    if (!root || !root.querySelectorAll) return null;
                    var btns = root.querySelectorAll(sendSel);
                    for (var i = btns.length - 1; i >= 0; i--) {
                      var b = btns[i];
                      if (b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
                      var r = b.getBoundingClientRect();
                      if (r.width > 0 && r.height > 0) return b;
                    }
                    return null;
                  }

                  // 1. Check composer ancestor container first
                  var container = targetEl;
                  for (var step = 0; step < 6 && container; step++) {
                    var found = pickEnabled(container);
                    if (found) return found;
                    container = container.parentElement || (container.getRootNode && container.getRootNode().host);
                  }

                  // 2. Check all roots (document, shadowRoots, same-origin iframes)
                  var roots = collectAllRoots(document);
                  for (var j = 0; j < roots.length; j++) {
                    var globalBtn = pickEnabled(roots[j]);
                    if (globalBtn) return globalBtn;
                  }

                  // 3. Fallback: icon-only enabled button positioned immediately to the right of targetEl
                  if (targetEl && targetEl.getBoundingClientRect) {
                    var tRect = targetEl.getBoundingClientRect();
                    var parentBox = targetEl.closest('form, [role="form"], footer, section, div') || targetEl.parentElement;
                    if (parentBox) {
                      var nearBtns = parentBox.querySelectorAll('button, [role="button"]');
                      for (var k = nearBtns.length - 1; k >= 0; k--) {
                        var nb = nearBtns[k];
                        if (nb.disabled || nb.getAttribute('aria-disabled') === 'true') continue;
                        var br = nb.getBoundingClientRect();
                        if (br.width >= 24 && br.height >= 24 && br.left >= tRect.left && Math.abs(br.top - tRect.top) < 80) {
                          return nb;
                        }
                      }
                    }
                  }
                  return null;
                }

                function submitTarget(targetEl) {
                  var inserted = insertTextIntoElement(targetEl, spokenText, false);
                  if (!inserted) return false;

                  var tDoc = targetEl.ownerDocument || document;
                  var tWin = tDoc.defaultView || window;
                  var KbdEvent = tWin.KeyboardEvent || KeyboardEvent;

                  setTimeout(function() {
                    try {
                      var btn = findSendButtonNear(targetEl);
                      if (btn) {
                        btn.click();
                      } else {
                        var init = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true, composed: true };
                        targetEl.dispatchEvent(new KbdEvent('keydown', init));
                        targetEl.dispatchEvent(new KbdEvent('keypress', init));
                        targetEl.dispatchEvent(new KbdEvent('keyup', init));
                      }
                    } catch (e) {}
                  }, 170);

                  return true;
                }

                function tryOpenCollapsedChatTrigger() {
                  var roots = collectAllRoots(document);
                  var triggerRe = /(new chat|start chat|ask|prompt|message|chat|mulai|tanya|percakapan|agent|composer)/i;
                  var bestBtn = null;
                  var bestScore = -1;
                  for (var i = 0; i < roots.length; i++) {
                    var els = roots[i].querySelectorAll('button, [role="button"], [tabindex="0"], [data-placeholder]');
                    for (var j = 0; j < els.length; j++) {
                      var el = els[j];
                      if (el.disabled) continue;
                      var rect = el.getBoundingClientRect();
                      if (rect.width < 24 || rect.height < 20) continue;
                      var label = (
                        (el.getAttribute('aria-label') || '') + ' ' +
                        (el.getAttribute('placeholder') || '') + ' ' +
                        (el.getAttribute('data-placeholder') || '') + ' ' +
                        (el.innerText || el.textContent || '').slice(0, 60)
                      ).trim();
                      if (triggerRe.test(label)) {
                        var score = rect.top > (window.innerHeight * 0.4) ? 20 : 10;
                        if (score > bestScore) {
                          bestScore = score;
                          bestBtn = el;
                        }
                      }
                    }
                  }
                  if (bestBtn) {
                    bestBtn.click();
                    return true;
                  }
                  return false;
                }

                var target = findBestEditable();
                if (target) {
                  var okNow = submitTarget(target);
                  reportResult(okNow);
                  return okNow ? 'SENT_SYNC' : 'FAILED_INSERT';
                }

                // If chat composer is collapsed or waiting for a tap to mount, click trigger and retry once
                var clickedTrigger = tryOpenCollapsedChatTrigger();
                if (clickedTrigger) {
                  setTimeout(function() {
                    try {
                      var retryTarget = findBestEditable();
                      if (retryTarget) {
                        reportResult(submitTarget(retryTarget));
                      } else {
                        reportResult(false);
                      }
                    } catch (e) {
                      reportResult(false);
                    }
                  }, 380);
                  return 'RETRYING_ASYNC';
                }

                reportResult(false);
                return 'NO_EDITABLE';
              } catch (e) {
                reportResult(false);
                return 'ERROR';
              }
            })($quotedText, $quotedReqId);
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(script) { evalResult ->
                val raw = evalResult?.trim('"') ?: ""
                if (raw == "SENT_SYNC") {
                    val cb = pendingVoiceSendCallbacks.remove(requestId)
                    cb?.invoke(true)
                } else if (raw == "NO_EDITABLE" || raw == "FAILED_INSERT" || raw == "ERROR") {
                    val cb = pendingVoiceSendCallbacks.remove(requestId)
                    cb?.invoke(false)
                }
                // For RETRYING_ASYNC, AndroidBridge.onVoiceChatSendResult resolves the callback cleanly
            }
        }
    }

    /**
     * Starts or stops a lightweight parent-process-only DOM observer across document, Shadow DOMs,
     * and accessible same-origin iframes inside Antigravity.
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

              function collectRoots(startRoot) {
                var visited = [];
                var queue = [startRoot];
                while (queue.length > 0 && visited.length < 180) {
                  var cur = queue.shift();
                  if (!cur || visited.indexOf(cur) !== -1) continue;
                  visited.push(cur);
                  try {
                    var all = cur.querySelectorAll('*');
                    for (var i = 0; i < all.length; i++) {
                      var node = all[i];
                      if (node.shadowRoot) queue.push(node.shadowRoot);
                      if ((node.tagName || '').toLowerCase() === 'iframe') {
                        try {
                          if (node.contentDocument) queue.push(node.contentDocument);
                        } catch (e) {}
                      }
                    }
                  } catch (e) {}
                }
                return visited;
              }

              function isNestedSubItem(el) {
                if (!el) return true;
                if (el.closest('pre, code, nav, header, footer, script, style, ul ul, ol ol')) return true;
                var ariaLevel = el.getAttribute('aria-level');
                if (ariaLevel && parseInt(ariaLevel, 10) > 1) return true;
                var detailsParent = el.closest('details');
                if (detailsParent && !el.closest('summary')) return true;
                return false;
              }

              function collectBusySignal(roots) {
                var stopSel = 'button[aria-label*="stop" i], button[aria-label*="cancel" i], button[aria-label*="hentikan" i], [role="progressbar"], [aria-busy="true"]';
                for (var r = 0; r < roots.length; r++) {
                  var busyNodes = roots[r].querySelectorAll(stopSel);
                  for (var i = 0; i < busyNodes.length; i++) {
                    var rect = busyNodes[i].getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0 && !busyNodes[i].disabled) return true;
                  }
                }
                return false;
              }

              function extractLatestParentStep(roots) {
                var candidates = [];
                var sel = 'details > summary, [role="status"], [aria-live="polite"], [aria-live="assertive"], h2, h3, h4, [data-step-title], [data-testid*="step" i], [data-testid*="status" i]';
                for (var r = 0; r < roots.length; r++) {
                  var list = roots[r].querySelectorAll(sel);
                  for (var i = 0; i < list.length; i++) {
                    var el = list[i];
                    if (isNestedSubItem(el)) continue;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) continue;
                    var txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (txt.length >= 4 && txt.length <= 160) {
                      candidates.push(txt);
                    }
                  }
                }
                return candidates.length > 0 ? candidates[candidates.length - 1] : '';
              }

              function extractLatestSummary(roots) {
                var blocks = [];
                var sel = 'article p, [role="log"] p, main p, [data-message-author="model"] p, [data-message-author="assistant"] p';
                for (var r = 0; r < roots.length; r++) {
                  var list = roots[r].querySelectorAll(sel);
                  for (var i = 0; i < list.length; i++) {
                    var el = list[i];
                    if (isNestedSubItem(el)) continue;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) continue;
                    var txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (txt.length >= 8) blocks.push(txt);
                  }
                }
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
                var roots = collectRoots(document);
                var busyWidget = collectBusySignal(roots);
                var recentlyMutated = (Date.now() - lastMutationAt) < 2800;
                var isRunning = busyWidget || (wasRunning && recentlyMutated);
                var parentStep = extractLatestParentStep(roots);
                var summary = isRunning ? '' : extractLatestSummary(roots);

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
        /**
         * Shared JS helpers for finding chat composers across document, Shadow DOMs,
         * same-origin iframes, contenteditable (including plaintext-only / bare attribute),
         * Lexical / ProseMirror / CodeMirror / Angular / React editors.
         */
        private val SHARED_DOM_COMPOSER_HELPERS = """
            function collectAllRoots(startRoot) {
              var visited = [];
              var queue = [startRoot];
              while (queue.length > 0 && visited.length < 220) {
                var cur = queue.shift();
                if (!cur || visited.indexOf(cur) !== -1) continue;
                visited.push(cur);
                try {
                  var nodes = cur.querySelectorAll('*');
                  for (var i = 0; i < nodes.length; i++) {
                    var n = nodes[i];
                    if (n.shadowRoot) queue.push(n.shadowRoot);
                    if ((n.tagName || '').toLowerCase() === 'iframe') {
                      try {
                        if (n.contentDocument) queue.push(n.contentDocument);
                      } catch (e) {}
                    }
                  }
                } catch (e) {}
              }
              return visited;
            }

            function isContentEditableEl(el) {
              if (!el) return false;
              if (el.isContentEditable) return true;
              var ce = el.getAttribute && el.getAttribute('contenteditable');
              if (ce !== null && ce !== undefined) {
                var low = String(ce).toLowerCase();
                return low === '' || low === 'true' || low === 'plaintext-only';
              }
              return false;
            }

            function isEditableEl(el) {
              if (!el || el.nodeType !== 1) return false;
              var tag = (el.tagName || '').toLowerCase();
              if (tag === 'textarea') return !el.disabled && !el.readOnly;
              if (tag === 'input') {
                var type = (el.type || 'text').toLowerCase();
                var blocked = ['hidden', 'checkbox', 'radio', 'button', 'submit', 'reset', 'file', 'image', 'range', 'color', 'password'];
                return blocked.indexOf(type) === -1 && !el.disabled && !el.readOnly;
              }
              if (isContentEditableEl(el)) return true;
              var role = (el.getAttribute('role') || '').toLowerCase();
              if (role === 'textbox' || role === 'combobox') return el.getAttribute('aria-disabled') !== 'true';
              if (el.getAttribute('aria-multiline') === 'true') return true;
              return false;
            }

            function isVisibleForInput(el) {
              if (!el) return false;
              var doc = el.ownerDocument || document;
              var win = doc.defaultView || window;
              try {
                var style = win.getComputedStyle(el);
                if (!style || style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                  return false;
                }
              } catch (e) {}
              var rect = el.getBoundingClientRect();
              if (rect.width >= 20 && rect.height >= 8) return true;
              // Empty contenteditable nodes often collapse to small height inside a visible composer wrapper
              var p = el.parentElement;
              if (p) {
                var pr = p.getBoundingClientRect();
                if (pr.width >= 40 && pr.height >= 16) return true;
              }
              return false;
            }

            function scoreEditableCandidate(el) {
              var score = 0;
              var tag = (el.tagName || '').toLowerCase();
              var rect = el.getBoundingClientRect();
              var vh = window.innerHeight || 800;

              var meta = (
                (el.getAttribute('aria-label') || '') + ' ' +
                (el.getAttribute('placeholder') || '') + ' ' +
                (el.getAttribute('data-placeholder') || '') + ' ' +
                (el.getAttribute('title') || '') + ' ' +
                (el.getAttribute('name') || '') + ' ' +
                (el.id || '') + ' ' +
                (typeof el.className === 'string' ? el.className : '')
              ).toLowerCase();

              if (/(prompt|ask|chat|message|pesan|tanya|kirim|command|agent|antigravity|reply|balas|composer|instruct|write|type)/.test(meta)) {
                score += 120;
              }
              if (tag === 'textarea' || isContentEditableEl(el) || el.getAttribute('role') === 'textbox' || el.getAttribute('aria-multiline') === 'true') {
                score += 55;
              }
              if (rect.top >= vh * 0.42) {
                score += 40;
              }
              if (rect.width >= 150) {
                score += 25;
              }
              if (/(search|filter|cari)/.test(meta) || (tag === 'input' && (el.type || '').toLowerCase() === 'search')) {
                score -= 90;
              }
              if (tag === 'input' && ((el.type || '').toLowerCase() === 'email' || meta.indexOf('identifier') !== -1)) {
                score -= 150;
              }
              return score;
            }

            function findBestEditable() {
              var roots = collectAllRoots(document);
              var sel = 'textarea, [contenteditable], [role="textbox"], [aria-multiline="true"], .ProseMirror, .cm-content, [data-lexical-editor], rich-textarea, input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="button"]):not([type="submit"]):not([type="file"]):not([type="password"])';
              var best = null;
              var bestScore = -9999;

              for (var r = 0; r < roots.length; r++) {
                var root = roots[r];
                try {
                  var active = root.activeElement;
                  if (isEditableEl(active) && isVisibleForInput(active)) {
                    var aScore = scoreEditableCandidate(active) + 30;
                    if (aScore > bestScore) {
                      bestScore = aScore;
                      best = active;
                    }
                  }
                } catch (e) {}

                var list = root.querySelectorAll(sel);
                for (var i = 0; i < list.length; i++) {
                  var el = list[i];
                  if (!isEditableEl(el) || !isVisibleForInput(el)) continue;
                  // Skip inner child elements if an outer contenteditable parent is already a candidate
                  if (el.parentElement && isContentEditableEl(el.parentElement) && !isContentEditableEl(el)) continue;
                  var sc = scoreEditableCandidate(el);
                  if (sc > bestScore) {
                    bestScore = sc;
                    best = el;
                  }
                }
              }
              return best;
            }

            function insertTextIntoElement(target, spokenText, appendMode) {
              if (!target) return false;
              var doc = target.ownerDocument || document;
              var win = doc.defaultView || window;
              try { target.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch (e) {}
              try { target.click(); } catch (e) {}
              try { target.focus(); } catch (e) {}

              var tag = (target.tagName || '').toLowerCase();
              if (tag === 'input' || tag === 'textarea') {
                var proto = tag === 'textarea' ? win.HTMLTextAreaElement.prototype : win.HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                var existing = target.value || '';
                var nextValue = appendMode && existing.length > 0
                  ? (/\s$/.test(existing) ? existing + spokenText : existing + ' ' + spokenText)
                  : spokenText;
                if (desc && desc.set) {
                  desc.set.call(target, nextValue);
                } else {
                  target.value = nextValue;
                }
                target.dispatchEvent(new (win.Event || Event)('input', { bubbles: true, composed: true }));
                target.dispatchEvent(new (win.Event || Event)('change', { bubbles: true, composed: true }));
                return true;
              }

              // Rich contenteditable / Lexical / ProseMirror / Angular / React textbox
              try {
                if (!appendMode) {
                  var sel = win.getSelection && win.getSelection();
                  if (sel) {
                    var range = doc.createRange();
                    range.selectNodeContents(target);
                    sel.removeAllRanges();
                    sel.addRange(range);
                  }
                  try { doc.execCommand('selectAll', false, null); } catch (e) {}
                }
              } catch (e) {}

              var inserted = false;
              try {
                inserted = doc.execCommand('insertText', false, spokenText);
              } catch (e) {
                inserted = false;
              }

              var currentContent = (target.innerText || target.textContent || '').trim();
              if (!inserted || currentContent.indexOf(spokenText.slice(0, 12)) === -1) {
                // Fallback 1: synthetic paste event (respected by Lexical / Slate / ProseMirror)
                try {
                  var dt = new (win.DataTransfer || DataTransfer)();
                  dt.setData('text/plain', spokenText);
                  var pasteEvt = new (win.ClipboardEvent || ClipboardEvent)('paste', {
                    clipboardData: dt,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                  });
                  target.dispatchEvent(pasteEvt);
                } catch (e) {}
              }

              currentContent = (target.innerText || target.textContent || '').trim();
              if (currentContent.indexOf(spokenText.slice(0, 12)) === -1) {
                // Fallback 2: direct textContent + beforeinput/input events
                var nextTxt = appendMode && currentContent.length > 0
                  ? (currentContent + ' ' + spokenText)
                  : spokenText;
                target.textContent = nextTxt;
              }

              try {
                var InputEvt = win.InputEvent || InputEvent;
                target.dispatchEvent(new InputEvt('beforeinput', { bubbles: true, composed: true, data: spokenText, inputType: 'insertText' }));
                target.dispatchEvent(new InputEvt('input', { bubbles: true, composed: true, data: spokenText, inputType: 'insertText' }));
              } catch (e) {
                target.dispatchEvent(new (win.Event || Event)('input', { bubbles: true, composed: true }));
              }
              return true;
            }
        """.trimIndent()

        @SuppressLint("SetJavaScriptEnabled")
        fun configureCommonWebView(
            webView: WebView,
            isDarkTheme: Boolean,
        ) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            webView.isVerticalScrollBarEnabled = true
            webView.isHorizontalScrollBarEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            }

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

                // Clean User-Agent so Google OAuth recognizes the Android Chrome WebView cleanly
                val defaultUa = userAgentString ?: ""
                userAgentString = defaultUa
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
            }

            // Enable native Android Passkey / Credential Manager support in WebView when available
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                runCatching {
                    WebSettingsCompat.setWebAuthenticationSupport(
                        webView.settings,
                        WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP,
                    )
                }
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
