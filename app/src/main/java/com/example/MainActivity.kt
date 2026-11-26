package com.example

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.documents.DocumentPreviewManager
import com.example.documents.DocumentPreviewState
import com.example.notifications.NotificationHelper
import com.example.ui.MainViewModel
import com.example.ui.components.AiFaceMatrixOverlay
import com.example.ui.components.AntigrafityWebViewContainer
import com.example.ui.components.DocumentPreviewOverlay
import com.example.ui.components.FloatingControlPill
import com.example.ui.components.NotificationSettingsSheet
import com.example.ui.components.VoiceCommandOverlay
import com.example.ui.theme.AntigrafityTheme
import com.example.voice.AiVoiceAssistantController
import com.example.voice.VoiceCommandManager
import com.example.voice.VoiceStatus
import com.example.webview.WebViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val webViewController = WebViewController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AntigrafityTheme {
                AntigrafityRemoteApp(
                    viewModel = viewModel,
                    webViewController = webViewController,
                    onExitApp = { finish() },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webViewController.flushCookies()
    }
}

@Composable
private fun AntigrafityRemoteApp(
    viewModel: MainViewModel,
    webViewController: WebViewController,
    onExitApp: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val webUiState by webViewController.uiState.collectAsStateWithLifecycle()
    val controlsState by viewModel.controlsState.collectAsStateWithLifecycle()
    val notificationSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()
    val voiceLanguage by viewModel.voiceLanguage.collectAsStateWithLifecycle()

    val documentPreviewManager = remember(context) {
        DocumentPreviewManager(context.applicationContext)
    }
    val documentPreviewState by documentPreviewManager.state.collectAsStateWithLifecycle()

    var hasNotifPermission by remember {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }
    var lastBackPressedAtMs by remember { mutableLongStateOf(0L) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingSaveSourceFile by remember { mutableStateOf<File?>(null) }
    var pendingOpenAiAssistantAfterPermission by remember { mutableStateOf(false) }
    var hasAutoPromptedGoogleAccount by remember { mutableStateOf(false) }

    val voiceManager = remember(context, webViewController) {
        VoiceCommandManager(
            context = context,
            onTranscriptReady = { transcript ->
                webViewController.injectVoiceText(transcript) { injected ->
                    val feedback = if (injected) {
                        context.getString(R.string.voice_injected)
                    } else {
                        context.getString(R.string.voice_no_input_found)
                    }
                    viewModel.emitSnackbar(feedback)
                }
            },
        )
    }
    val voiceState by voiceManager.state.collectAsStateWithLifecycle()

    val aiAssistantController = remember(context, webViewController) {
        AiVoiceAssistantController(
            context = context,
            onSendChatMessage = { spoken, onResult ->
                webViewController.sendVoiceChatMessage(spoken, onResult)
            },
            onObserverEnabledChanged = { enabled ->
                webViewController.setAgentProcessObserverEnabled(enabled)
            },
        )
    }
    val aiAssistantState by aiAssistantController.state.collectAsStateWithLifecycle()

    DisposableEffect(voiceManager, aiAssistantController) {
        onDispose {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            pendingSaveSourceFile = null
            voiceManager.destroy()
            webViewController.setAgentProcessObserverEnabled(false)
            aiAssistantController.destroy()
        }
    }

    // Collect one-shot snackbar events
    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short,
            )
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val openAiMode = pendingOpenAiAssistantAfterPermission
        pendingOpenAiAssistantAfterPermission = false
        if (granted) {
            if (openAiMode) {
                voiceManager.cancelAndDismiss()
                aiAssistantController.open()
            } else {
                voiceManager.startListening(voiceLanguage)
            }
        } else {
            viewModel.emitSnackbar(context.getString(R.string.voice_permission_denied))
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotifPermission = granted || NotificationHelper.hasNotificationPermission(context)
        if (!granted) {
            viewModel.emitSnackbar(context.getString(R.string.notif_permission_needed))
        }
    }

    // Zero-permission Android Photo Picker for single image uploads
    val singleImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    // Zero-permission Android Photo Picker for multiple image uploads
    val multiImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    // Zero-permission Storage Access Framework picker for documents, PDFs, and mixed uploads
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@rememberLauncherForActivityResult

        if (result.resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            return@rememberLauncherForActivityResult
        }

        val data = result.data
        val uris = buildList {
            val clipData = data?.clipData
            if (clipData != null && clipData.itemCount > 0) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i).uri?.let { add(it) }
                }
            } else {
                data?.data?.let { add(it) }
            }
        }
        callback.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    // Zero-permission SAF folder/file destination chooser for downloading from Document Preview
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val sourceFile = pendingSaveSourceFile
        pendingSaveSourceFile = null
        val destinationUri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || sourceFile == null || destinationUri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val saved = documentPreviewManager.saveFileToUri(destinationUri, sourceFile)
            viewModel.emitSnackbar(
                context.getString(
                    if (saved) R.string.doc_save_success else R.string.doc_save_failed,
                ),
            )
        }
    }

    // Zero-permission native Android device Google account picker (no GET_ACCOUNTS needed)
    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val chosenEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
        if (chosenEmail.isNotEmpty()) {
            webViewController.applyChosenGoogleAccount(chosenEmail)
            viewModel.emitSnackbar(context.getString(R.string.google_account_applied, chosenEmail))
        }
    }

    fun launchDeviceGoogleAccountPicker() {
        val intent = AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            null,
            null,
            null,
            null,
        )
        runCatching {
            googleAccountPickerLauncher.launch(intent)
        }.onFailure {
            viewModel.emitSnackbar(context.getString(R.string.google_account_picker_unavailable))
        }
    }

    // Automatically offer the device's signed-in Google accounts on first Google Sign-In page load
    val isAnyGoogleSignInVisible = webUiState.isOnGoogleSignInPage || webUiState.isPopupOnGoogleSignInPage
    LaunchedEffect(isAnyGoogleSignInVisible, webUiState.isLoading) {
        if (!isAnyGoogleSignInVisible) {
            hasAutoPromptedGoogleAccount = false
        } else if (!hasAutoPromptedGoogleAccount && !webUiState.isLoading) {
            kotlinx.coroutines.delay(350L)
            if (!hasAutoPromptedGoogleAccount) {
                hasAutoPromptedGoogleAccount = true
                launchDeviceGoogleAccountPicker()
            }
        }
    }

    fun launchSaveDocumentToFolder(file: File, fileName: String, mimeType: String) {
        pendingSaveSourceFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_TITLE, fileName.ifBlank { file.name })
        }
        runCatching {
            createDocumentLauncher.launch(intent)
        }.onFailure {
            pendingSaveSourceFile = null
            viewModel.emitSnackbar(context.getString(R.string.doc_save_failed))
        }
    }

    fun pasteUriIntoWebView(uri: Uri, providedMime: String?) {
        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                readUriWithCap(context, uri, providedMime, MAX_PASTE_IMAGE_BYTES)
            }
            when (payload) {
                is PasteReadResult.Success -> {
                    webViewController.pasteBinaryIntoWeb(
                        bytes = payload.bytes,
                        mimeType = payload.mimeType,
                        fileName = payload.fileName,
                    ) { pasted ->
                        viewModel.emitSnackbar(
                            context.getString(
                                if (pasted) R.string.paste_image_success else R.string.paste_image_failed,
                            ),
                        )
                    }
                }
                PasteReadResult.TooLarge -> {
                    viewModel.emitSnackbar(context.getString(R.string.paste_image_too_large))
                }
                PasteReadResult.Failed -> {
                    viewModel.emitSnackbar(context.getString(R.string.paste_image_failed))
                }
            }
        }
    }

    fun pasteFromSystemClipboard() {
        viewModel.collapsePill()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            viewModel.emitSnackbar(context.getString(R.string.paste_clipboard_empty))
            return
        }

        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val mime = context.contentResolver.getType(uri)
            pasteUriIntoWebView(uri, mime)
            return
        }

        val text = item.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            webViewController.injectVoiceText(text) { injected ->
                viewModel.emitSnackbar(
                    context.getString(
                        if (injected) R.string.voice_injected else R.string.paste_image_failed,
                    ),
                )
            }
        } else {
            viewModel.emitSnackbar(context.getString(R.string.paste_clipboard_empty))
        }
    }

    fun handleShowFileChooser(
        params: WebChromeClient.FileChooserParams?,
        callback: ValueCallback<Array<Uri>>,
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback

        val acceptTypes = params?.acceptTypes
            ?.flatMap { it.split(",") }
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val allowMultiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val isImageOnly = acceptTypes.isNotEmpty() && acceptTypes.all {
            it.startsWith("image/", ignoreCase = true) ||
                it.equals(".png", ignoreCase = true) ||
                it.equals(".jpg", ignoreCase = true) ||
                it.equals(".jpeg", ignoreCase = true) ||
                it.equals(".webp", ignoreCase = true) ||
                it.equals(".gif", ignoreCase = true)
        }

        return runCatching {
            if (isImageOnly) {
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                if (allowMultiple) {
                    multiImagePickerLauncher.launch(request)
                } else {
                    singleImagePickerLauncher.launch(request)
                }
            } else {
                val normalizedMimes = acceptTypes
                    .mapNotNull { normalizeAcceptTypeToMime(it) }
                    .distinct()
                    .toTypedArray()

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (normalizedMimes.size == 1) normalizedMimes[0] else "*/*"
                    if (normalizedMimes.size > 1) {
                        putExtra(Intent.EXTRA_MIME_TYPES, normalizedMimes)
                    }
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                }
                documentPickerLauncher.launch(
                    Intent.createChooser(intent, context.getString(R.string.file_upload_chooser_title)),
                )
            }
            true
        }.getOrElse {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            false
        }
    }

    fun launchVoiceCommand() {
        viewModel.collapsePill()
        aiAssistantController.close()
        pendingOpenAiAssistantAfterPermission = false
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudioPermission) {
            voiceManager.startListening(voiceLanguage)
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun launchAiTalkAssistant() {
        viewModel.collapsePill()
        voiceManager.cancelAndDismiss()
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudioPermission) {
            aiAssistantController.open()
        } else {
            pendingOpenAiAssistantAfterPermission = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
        }
    }

    // Predictive & hardware Back button handling + double-back exit on home screen
    BackHandler(enabled = true) {
        when {
            documentPreviewState !is DocumentPreviewState.Hidden -> {
                documentPreviewManager.dismiss()
            }
            aiAssistantState.isVisible -> {
                aiAssistantController.close()
            }
            webUiState.activePopupMessage != null -> {
                webViewController.handlePopupBack()
            }
            controlsState.isNotificationSheetOpen -> {
                viewModel.closeNotificationSheet()
            }
            voiceState.isVisible -> {
                voiceManager.cancelAndDismiss()
            }
            controlsState.isPillExpanded -> {
                viewModel.collapsePill()
            }
            webViewController.goBack() -> {
                // Handled by WebView history navigation
            }
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressedAtMs <= 2_000L) {
                    onExitApp()
                } else {
                    lastBackPressedAtMs = now
                    viewModel.emitSnackbar(context.getString(R.string.press_back_again_to_exit))
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Full-screen edge-to-edge WebView + File Upload + PDF/Doc Preview + Paste + OAuth Popup
            AntigrafityWebViewContainer(
                controller = webViewController,
                uiState = webUiState,
                onShowFileChooser = { params, callback ->
                    handleShowFileChooser(params, callback)
                },
                onHttpsDocumentPreview = { url, userAgent, disposition, mime ->
                    scope.launch {
                        documentPreviewManager.previewHttpsDocument(url, userAgent, disposition, mime)
                    }
                },
                onBlobOrDataDocumentPreview = { dataUrl, mime, fileName ->
                    scope.launch {
                        documentPreviewManager.previewDataOrBlobDocument(dataUrl, mime, fileName)
                    }
                },
                onRichContentPasted = { uri, mime ->
                    pasteUriIntoWebView(uri, mime)
                },
                onAgentProcessUpdate = { isRunning, parentStep, finalSummary ->
                    aiAssistantController.onAgentProcessUpdate(isRunning, parentStep, finalSummary)
                },
                onPickDeviceGoogleAccount = {
                    launchDeviceGoogleAccountPicker()
                },
            )

            // 2. Single draggable & edge-hideable Floating Control Pill
            FloatingControlPill(
                isExpanded = controlsState.isPillExpanded,
                isTucked = controlsState.isPillTucked,
                canGoBack = webUiState.canGoBack,
                isVoiceActive = voiceState.status == VoiceStatus.LISTENING,
                isAiAssistantActive = aiAssistantState.isVisible,
                notificationsEnabled = notificationSettings.enabled,
                onToggleExpand = { viewModel.togglePillExpanded() },
                onTuck = { viewModel.tuckPillToEdge() },
                onUntuck = { viewModel.untuckPill() },
                onBack = {
                    viewModel.collapsePill()
                    if (!webViewController.goBack()) {
                        webViewController.goHome()
                    }
                },
                onHome = {
                    viewModel.collapsePill()
                    webViewController.goHome()
                },
                onAiTalkClick = { launchAiTalkAssistant() },
                onVoiceClick = { launchVoiceCommand() },
                onNotificationsClick = {
                    hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                    viewModel.openNotificationSheet()
                },
                onRefreshClick = {
                    viewModel.collapsePill()
                    webViewController.reload()
                },
                onPasteImageClick = { pasteFromSystemClipboard() },
            )

            // 3. Minimal Voice Command HUD overlay
            VoiceCommandOverlay(
                voiceState = voiceState,
                selectedLanguage = voiceLanguage,
                onSelectLanguage = { newLang ->
                    viewModel.selectVoiceLanguage(newLang)
                    if (voiceState.status == VoiceStatus.LISTENING) {
                        voiceManager.startListening(newLang)
                    }
                },
                onRetryListen = { launchVoiceCommand() },
                onStopAndSend = { voiceManager.stopListening() },
                onCancel = { voiceManager.cancelAndDismiss() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // 4. Native in-app PDF / Image / Text Document Preview Overlay
            DocumentPreviewOverlay(
                state = documentPreviewState,
                onDownloadSave = { file, fileName, mime ->
                    launchSaveDocumentToFolder(file, fileName, mime)
                },
                onOpenExternal = { file, mime ->
                    val opened = documentPreviewManager.openExternally(context, file, mime)
                    if (!opened) {
                        viewModel.emitSnackbar(context.getString(R.string.doc_preview_no_app))
                    }
                },
                onShare = { file, mime ->
                    documentPreviewManager.shareFile(context, file, mime)
                },
                onDismiss = { documentPreviewManager.dismiss() },
            )

            // 5. Full-screen Indonesian AI Face Matrix conversational overlay
            AiFaceMatrixOverlay(
                state = aiAssistantState,
                onToggleMute = { aiAssistantController.toggleMute() },
                onMicClick = { aiAssistantController.onMicButtonPressed() },
                onClose = { aiAssistantController.close() },
            )
        }
    }

    // 5. Local Notification Settings Bottom Sheet
    if (controlsState.isNotificationSheetOpen) {
        NotificationSettingsSheet(
            currentSettings = notificationSettings,
            hasNotificationPermission = hasNotifPermission,
            onRequestNotificationPermission = { requestPostNotificationsPermission() },
            onTestNotification = { title, msg -> viewModel.sendTestNotification(title, msg) },
            onSave = { updatedSettings -> viewModel.saveNotificationSettings(updatedSettings) },
            onDismiss = { viewModel.closeNotificationSheet() },
        )
    }
}

private const val MAX_PASTE_IMAGE_BYTES = 15 * 1024 * 1024 // 15 MB

private sealed interface PasteReadResult {
    data class Success(val bytes: ByteArray, val mimeType: String, val fileName: String) : PasteReadResult
    data object TooLarge : PasteReadResult
    data object Failed : PasteReadResult
}

private fun readUriWithCap(
    context: Context,
    uri: Uri,
    providedMime: String?,
    maxBytes: Int,
): PasteReadResult {
    return runCatching {
        val resolver = context.contentResolver
        val mime = providedMime?.takeIf { it.isNotBlank() }
            ?: resolver.getType(uri)
            ?: "image/png"
        val fileName = queryDisplayName(context, uri)
            ?: when {
                mime.contains("jpeg", ignoreCase = true) -> "pasted_image.jpg"
                mime.contains("webp", ignoreCase = true) -> "pasted_image.webp"
                mime.contains("gif", ignoreCase = true) -> "pasted_image.gif"
                mime.contains("pdf", ignoreCase = true) -> "pasted_document.pdf"
                else -> "pasted_image.png"
            }

        val stream = resolver.openInputStream(uri) ?: return PasteReadResult.Failed
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    return PasteReadResult.TooLarge
                }
                out.write(buffer, 0, read)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) PasteReadResult.Failed else PasteReadResult.Success(bytes, mime, fileName)
    }.getOrDefault(PasteReadResult.Failed)
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
            } else {
                null
            }
        }
    }.getOrNull()
}

private fun normalizeAcceptTypeToMime(raw: String): String? {
    val clean = raw.trim().lowercase()
    if (clean.isBlank() || clean == "*/*") return "*/*"
    if (clean.contains('/')) return clean
    return when (clean.removePrefix(".")) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        else -> null
    }
}
