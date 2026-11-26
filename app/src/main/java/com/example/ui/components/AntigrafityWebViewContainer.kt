package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import kotlin.math.abs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.R
import com.example.webview.AndroidBridge
import com.example.webview.WebHostPolicy
import com.example.webview.WebViewController
import com.example.webview.WebViewUiState

@Composable
fun AntigrafityWebViewContainer(
    controller: WebViewController,
    uiState: WebViewUiState,
    onShowFileChooser: (
        params: WebChromeClient.FileChooserParams?,
        callback: ValueCallback<Array<Uri>>,
    ) -> Boolean,
    onHttpsDocumentPreview: (
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) -> Unit,
    onBlobOrDataDocumentPreview: (
        base64DataUrl: String,
        mimeType: String?,
        fileName: String?,
    ) -> Unit,
    onRichContentPasted: (Uri, String?) -> Unit,
    onAgentProcessUpdate: (isRunning: Boolean, parentStep: String, finalSummary: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()
    val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()

    DisposableEffect(controller) {
        onDispose {
            controller.detachMainWebView()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("antigrafity_webview"),
            factory = { ctx ->
                val density = ctx.resources.displayMetrics.density
                val swipeLayout = SmartWebSwipeRefreshLayout(
                    context = ctx,
                    topPullZonePx = 96f * density,
                ).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setColorSchemeColors(primaryArgb)
                    setProgressBackgroundColorSchemeColor(surfaceArgb)
                    setDistanceToTriggerSync((128f * density).toInt())
                }

                val bridge = AndroidBridge(
                    context = ctx.applicationContext,
                    currentOriginProvider = { controller.currentTrustedUrl },
                    onPullToRefreshAllowedChanged = { allowed ->
                        controller.updateWebAtTop(allowed)
                    },
                    onBlobFileReady = { dataUrl, mimeType, fileName ->
                        onBlobOrDataDocumentPreview(dataUrl, mimeType, fileName)
                    },
                    onAgentProcessUpdate = { isRunning, parentStep, finalSummary ->
                        onAgentProcessUpdate(isRunning, parentStep, finalSummary)
                    },
                )

                val webView = WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundColor(backgroundArgb)
                    WebViewController.configureCommonWebView(this, isDarkTheme)

                    addJavascriptInterface(bridge, AndroidBridge.BRIDGE_NAME)

                    // Intercept rich image/file paste directly from Gboard / Samsung Keyboard clipboard
                    ViewCompat.setOnReceiveContentListener(
                        this,
                        arrayOf("image/*", "application/pdf"),
                    ) { _, payload ->
                        val clip = payload.clip
                        var handledUri = false
                        for (i in 0 until clip.itemCount) {
                            val uri = clip.getItemAt(i).uri
                            if (uri != null) {
                                val mime = ctx.contentResolver.getType(uri)
                                onRichContentPasted(uri, mime)
                                handledUri = true
                            }
                        }
                        if (handledUri) null else payload
                    }

                    // Handle PDFs, Office documents, blob downloads, and attachments cleanly
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        when {
                            url.startsWith("blob:", ignoreCase = true) -> {
                                val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                controller.fetchBlobUrl(url, mimeType, guessedName)
                            }
                            WebHostPolicy.isSafePreviewDataUrl(url) -> {
                                val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                onBlobOrDataDocumentPreview(url, mimeType, guessedName)
                            }
                            WebHostPolicy.isSafeHttpsUrl(url) -> {
                                onHttpsDocumentPreview(url, userAgent, contentDisposition, mimeType)
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val destination = request?.url?.toString() ?: return true
                            if (destination.startsWith("blob:", ignoreCase = true)) {
                                controller.fetchBlobUrl(destination)
                                return true
                            }
                            if (WebHostPolicy.isSafePreviewDataUrl(destination)) {
                                onBlobOrDataDocumentPreview(destination, null, "document")
                                return true
                            }
                            if (!WebHostPolicy.isSafeHttpsUrl(destination)) {
                                // Block unsafe schemes (javascript:, file:, content:, intent://)
                                return true
                            }
                            if (WebHostPolicy.isLikelyDocumentUrl(destination)) {
                                onHttpsDocumentPreview(
                                    destination,
                                    view?.settings?.userAgentString,
                                    null,
                                    null,
                                )
                                return true
                            }
                            if (WebHostPolicy.shouldKeepInApp(destination)) {
                                return false
                            }
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, destination.toUri()).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                ctx.startActivity(intent)
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            controller.onPageStarted(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            controller.onPageFinished(url)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                controller.onMainFrameError(error?.description?.toString())
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            controller.onProgressChanged(newProgress)
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?,
                        ): Boolean {
                            if (filePathCallback == null) return false
                            return onShowFileChooser(fileChooserParams, filePathCallback)
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            if (request == null) return
                            val originUrl = request.origin?.toString()
                            if (WebHostPolicy.isTrustedBridgeOrigin(originUrl)) {
                                val allowedResources = request.resources.filter { res ->
                                    res == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                                        res == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                                }.toTypedArray()
                                if (allowedResources.isNotEmpty()) {
                                    request.grant(allowedResources)
                                    return
                                }
                            }
                            request.deny()
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?,
                        ): Boolean {
                            if (resultMsg == null) return false
                            controller.onPopupRequested(resultMsg)
                            return true
                        }
                    }
                }

                swipeLayout.addView(webView)
                swipeLayout.setOnChildScrollUpCallback { _, _ ->
                    webView.scrollY > 0 || !controller.isWebAtTop
                }
                swipeLayout.setOnRefreshListener {
                    controller.reload(fromPullToRefresh = true)
                }

                controller.attachMainWebView(webView)
                webView.loadUrl(WebHostPolicy.HOME_URL)
                swipeLayout
            },
            update = { swipeLayout ->
                swipeLayout.isRefreshing = uiState.isRefreshing
                swipeLayout.setColorSchemeColors(primaryArgb)
                swipeLayout.setProgressBackgroundColorSchemeColor(surfaceArgb)
                val webView = swipeLayout.getChildAt(0) as? WebView
                if (webView != null) {
                    webView.setBackgroundColor(backgroundArgb)
                    WebViewController.applyThemeSync(webView, isDarkTheme)
                }
            },
        )

        // Slim top progress bar during navigation
        AnimatedVisibility(
            visible = uiState.isLoading && uiState.progress in 1..99 && !uiState.hasError,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            LinearProgressIndicator(
                progress = { uiState.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            )
        }

        // Offline / load error recovery view
        AnimatedVisibility(
            visible = uiState.hasError,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier.widthIn(max = 420.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(64.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.WifiOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.offline_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.offline_subtitle, uiState.currentUrl),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (!uiState.errorDescription.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.errorDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { controller.reload() },
                            modifier = Modifier.testTag("offline_retry_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(text = stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }

        // In-app OAuth / Google Sign-In popup WebView overlay
        val popupMessage = uiState.activePopupMessage
        if (popupMessage != null) {
            OAuthPopupOverlay(
                resultMessage = popupMessage,
                controller = controller,
                isDarkTheme = isDarkTheme,
                onHttpsDocumentPreview = onHttpsDocumentPreview,
                onBlobOrDataDocumentPreview = onBlobOrDataDocumentPreview,
                onDismiss = { controller.closePopup() },
            )
        }
    }
}

@Composable
private fun OAuthPopupOverlay(
    resultMessage: Message,
    controller: WebViewController,
    isDarkTheme: Boolean,
    onHttpsDocumentPreview: (String, String?, String?, String?) -> Unit,
    onBlobOrDataDocumentPreview: (String, String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("oauth_popup_overlay"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.oauth_dialog_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.oauth_secure_connection),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("oauth_popup_close_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close_popup),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val popupWebView = WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setBackgroundColor(backgroundArgb)
                        WebViewController.configureCommonWebView(this, isDarkTheme)

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val destination = request?.url?.toString() ?: return true
                                if (destination.startsWith("blob:", ignoreCase = true)) {
                                    controller.fetchBlobUrl(destination)
                                    onDismiss()
                                    return true
                                }
                                if (WebHostPolicy.isSafePreviewDataUrl(destination)) {
                                    onBlobOrDataDocumentPreview(destination, null, "document")
                                    onDismiss()
                                    return true
                                }
                                if (WebHostPolicy.isLikelyDocumentUrl(destination)) {
                                    onHttpsDocumentPreview(
                                        destination,
                                        view?.settings?.userAgentString,
                                        null,
                                        null,
                                    )
                                    onDismiss()
                                    return true
                                }
                                return !WebHostPolicy.isSafeHttpsUrl(destination)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                controller.flushCookies()
                            }
                        }

                        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            when {
                                url.startsWith("blob:", ignoreCase = true) -> {
                                    val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                    controller.fetchBlobUrl(url, mimeType, guessedName)
                                    onDismiss()
                                }
                                WebHostPolicy.isSafePreviewDataUrl(url) -> {
                                    val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                                    onBlobOrDataDocumentPreview(url, mimeType, guessedName)
                                    onDismiss()
                                }
                                WebHostPolicy.isSafeHttpsUrl(url) -> {
                                    onHttpsDocumentPreview(url, userAgent, contentDisposition, mimeType)
                                    onDismiss()
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onCloseWindow(window: WebView?) {
                                super.onCloseWindow(window)
                                onDismiss()
                            }
                        }
                    }

                    controller.attachPopupWebView(popupWebView)
                    val transport = resultMessage.obj as? WebView.WebViewTransport
                    transport?.webView = popupWebView
                    resultMessage.sendToTarget()
                    popupWebView
                },
            )
        }
    }
}

/**
 * Custom SwipeRefreshLayout that only permits pull-to-refresh when the drag starts in the
 * top edge zone and moves predominantly downward, preventing accidental reloads when scrolling
 * up inside nested scrollable SPA containers (e.g. chat/process feeds).
 */
private class SmartWebSwipeRefreshLayout(
    context: Context,
    private val topPullZonePx: Float,
) : SwipeRefreshLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0f
    private var initialY = 0f
    private var allowRefreshGesture = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.x
                initialY = ev.y
                allowRefreshGesture = initialY <= topPullZonePx
            }
            MotionEvent.ACTION_MOVE -> {
                if (!allowRefreshGesture) {
                    return false
                }
                val dx = abs(ev.x - initialX)
                val dy = ev.y - initialY
                if (dy < 0f || (dx > touchSlop && dx > dy)) {
                    allowRefreshGesture = false
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                allowRefreshGesture = false
            }
        }
        return allowRefreshGesture && super.onInterceptTouchEvent(ev)
    }
}
