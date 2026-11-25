package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notifications.NotificationHelper
import com.example.ui.MainViewModel
import com.example.ui.components.AntigrafityWebViewContainer
import com.example.ui.components.FloatingControlPill
import com.example.ui.components.NotificationSettingsSheet
import com.example.ui.components.VoiceCommandOverlay
import com.example.ui.theme.AntigrafityTheme
import com.example.voice.VoiceCommandManager
import com.example.voice.VoiceStatus
import com.example.webview.WebViewController

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
    val snackbarHostState = remember { SnackbarHostState() }

    val webUiState by webViewController.uiState.collectAsStateWithLifecycle()
    val controlsState by viewModel.controlsState.collectAsStateWithLifecycle()
    val notificationSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()
    val voiceLanguage by viewModel.voiceLanguage.collectAsStateWithLifecycle()

    var hasNotifPermission by remember {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }
    var lastBackPressedAtMs by remember { mutableLongStateOf(0L) }

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

    DisposableEffect(voiceManager) {
        onDispose {
            voiceManager.destroy()
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
        if (granted) {
            voiceManager.startListening(voiceLanguage)
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

    fun launchVoiceCommand() {
        viewModel.collapsePill()
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
            // 1. Full-screen edge-to-edge WebView + Pull-to-Refresh + In-App OAuth Popup
            AntigrafityWebViewContainer(
                controller = webViewController,
                uiState = webUiState,
            )

            // 2. Single draggable & edge-hideable Floating Control Pill
            FloatingControlPill(
                isExpanded = controlsState.isPillExpanded,
                isTucked = controlsState.isPillTucked,
                canGoBack = webUiState.canGoBack,
                isVoiceActive = voiceState.status == VoiceStatus.LISTENING,
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
                onVoiceClick = { launchVoiceCommand() },
                onNotificationsClick = {
                    hasNotifPermission = NotificationHelper.hasNotificationPermission(context)
                    viewModel.openNotificationSheet()
                },
                onRefreshClick = {
                    viewModel.collapsePill()
                    webViewController.reload()
                },
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
        }
    }

    // 4. Local Notification Settings Bottom Sheet
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
